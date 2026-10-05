/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.validation;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.xtext.diagnostics.Severity;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.SyntaxErrorMessage;
import org.eclipse.xtext.parser.IParseResult;
import org.eclipse.xtext.parser.IParser;
import org.eclipse.xtext.resource.IResourceFactory;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.util.CancelIndicator;
import org.eclipse.xtext.validation.CheckMode;
import org.eclipse.xtext.validation.IResourceValidator;
import org.eclipse.xtext.validation.Issue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.polischuk.edt.prl.edt.EdtResourceSets;
import com.polischuk.edt.prl.edt.V8Access;
import com.polischuk.edt.prl.tools.McpTool;

/**
 * Перевірка тексту запиту 1С через QL-інфраструктуру EDT (Xtext-мови "ql" і "qldcs").
 *
 * <p>Два рівні:
 * <ol>
 * <li>синтаксис (завжди доступний) — IParser з інжектора мови, помилки з
 * IParseResult.getSyntaxErrors() з позицією (рядок/колонка);</li>
 * <li>семантика (якщо задано project) — Xtext-ресурс у BM-aware ResourceSet проєкту
 * + IResourceValidator: до синтаксису додаються перевірки імен таблиць і полів проти
 * метаданих конфігурації.</li>
 * </ol>
 *
 * <p>QL-сервіси беруться з реєстру Xtext за розширенням ресурсу — компільної залежності
 * від бандлів com._1c.g5.v8.dt.ql* немає, потрібні лише org.eclipse.xtext*.
 *
 * <p><b>Чому semantic ніколи не деградує мовчки.</b> Раніше ResourceSet брався з
 * UI-сервіса {@code IResourceSetProvider}, який до підняття UI-бандла мови повертає null;
 * NPE ловився і відповідь тихо ставала синтаксичною з {@code valid:true} — тобто запит із
 * неіснуючим полем оголошувався коректним. Крім того, до синхронізації BM-моделі скоуп
 * порожній, і кожна таблиця «не знайдена» — тобто коректний запит оголошувався хибним.
 * Тепер ResourceSet будується через BM-aware набір EDT, перед перевіркою чекаємо
 * синхронізації моделі, а якщо семантика все одно недоступна — інструмент падає з
 * поясненням замість того, щоб віддати неправдиве {@code valid}.
 */
public final class ValidateQueryTool implements McpTool {

    /** Розширення ресурсу вибирає мову в реєстрі Xtext: "ql" — запит 1С, "qldcs" — запит СКД. */
    private static final String DUMMY_QL = "__mcp_validate_query__.ql"; //$NON-NLS-1$
    private static final String DUMMY_QL_DCS = "__mcp_validate_query__.qldcs"; //$NON-NLS-1$

    /** Код діагностики EDT для нерозпізнаної таблиці — ознака непорахованого скоупа. */
    private static final String TABLE_NOT_FOUND_CODE = "Table not found"; //$NON-NLS-1$

    private static final long DERIVED_DATA_WAIT_MS = 15_000L;
    private static final long DERIVED_DATA_RETRY_WAIT_MS = 60_000L;

    @Override
    public String name() {
        return "validate_query"; //$NON-NLS-1$
    }

    @Override
    public String description() {
        return "Перевірка тексту запиту 1С мовою запитів EDT. Без project — лише синтаксис; " //$NON-NLS-1$
                + "з project — синтаксис + семантика (імена таблиць і полів проти конфігурації). " //$NON-NLS-1$
                + "isDcs=true — мова запитів СКД (блоки {ВЫБРАТЬ …}, {ГДЕ …}). " //$NON-NLS-1$
                + "Результат: valid, level, errors[{severity, line, column, offset, length, code, message}]."; //$NON-NLS-1$
    }

    @Override
    public JsonObject inputSchema() {
        return JsonParser.parseString("""
                {"type":"object","properties":{
                  "query":{"type":"string","description":"Текст запиту 1С (ВЫБРАТЬ …)"},
                  "project":{"type":"string","description":"Ім'я проєкту EDT: вмикає семантичну перевірку проти конфігурації"},
                  "isDcs":{"type":"boolean","default":false,"description":"Запит СКД: дозволені блоки {ВЫБРАТЬ …}, {ГДЕ …} і параметри віртуальних таблиць"}
                },"required":["query"]}""").getAsJsonObject(); //$NON-NLS-1$
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
        String query = required(arguments, "query"); //$NON-NLS-1$
        String projectName = arguments.has("project") && !arguments.get("project").getAsString().isBlank() //$NON-NLS-1$ //$NON-NLS-2$
                ? arguments.get("project").getAsString() : null; //$NON-NLS-1$
        boolean isDcs = arguments.has("isDcs") && arguments.get("isDcs").getAsBoolean(); //$NON-NLS-1$ //$NON-NLS-2$

        try {
            if (projectName == null) {
                JsonObject result = validateSyntax(query, isDcs);
                result.addProperty("isDcs", isDcs); //$NON-NLS-1$
                return result;
            }
            JsonObject result = validateSemantic(query, projectName, isDcs);
            result.addProperty("isDcs", isDcs); //$NON-NLS-1$
            return result;
        } catch (NoClassDefFoundError e) {
            throw new IllegalStateException(
                    "Xtext/QL-інфраструктура EDT недоступна в runtime: " + e.getMessage() //$NON-NLS-1$
                    + ". Перевірте Import-Package org.eclipse.xtext.* у MANIFEST.MF.", e); //$NON-NLS-1$
        }
    }

    /** Рівень 1: лише синтаксис — парсер мови без прив'язки до проєкту. */
    private static JsonObject validateSyntax(String query, boolean isDcs) {
        IParser parser = service(IParser.class, isDcs);
        IParseResult parseResult = parser.parse(new StringReader(query));

        JsonArray errors = new JsonArray();
        for (INode node : parseResult.getSyntaxErrors()) {
            SyntaxErrorMessage syntaxError = node.getSyntaxErrorMessage();
            JsonObject item = issueJson("error", node.getStartLine(), //$NON-NLS-1$
                    columnOf(query, node.getOffset()),
                    syntaxError == null ? "Синтаксична помилка" : syntaxError.getMessage()); //$NON-NLS-1$
            item.addProperty("offset", node.getOffset()); //$NON-NLS-1$
            item.addProperty("length", node.getLength()); //$NON-NLS-1$
            if (syntaxError != null && syntaxError.getIssueCode() != null) {
                item.addProperty("code", syntaxError.getIssueCode()); //$NON-NLS-1$
            }
            errors.add(item);
        }
        return resultJson("syntax", errors, new JsonArray()); //$NON-NLS-1$
    }

    /**
     * Рівень 2: синтаксис + семантика. Ресурс створюється фабрикою мови в BM-aware
     * ResourceSet проєкту (platform-URI всередині проєкту), щоб скоупінг QL бачив
     * метадані конфігурації; IResourceValidator повертає і синтаксичні, і
     * лінкінгові/семантичні проблеми.
     */
    private static JsonObject validateSemantic(String query, String projectName, boolean isDcs)
            throws Exception {
        IProject project = V8Access.resolveEclipseProject(projectName);
        IResourceServiceProvider provider = provider(isDcs);

        // BM-модель і derived data (серед них — модель таблиць БД) можуть ще
        // рахуватись: поки вони не готові, скоуп порожній і КОЖНА таблиця
        // «не знайдена» — тобто коректний запит став би хибним.
        EdtResourceSets.waitModelSynchronization(project);
        boolean scopeReady = EdtResourceSets.waitDerivedData(project, DERIVED_DATA_WAIT_MS);

        JsonObject result = validateOnce(query, project, provider, isDcs, scopeReady);
        if (!scopeReady && hasTableNotFound(result)) {
            // «Таблиця не знайдена» на непорахованому скоупі нічого не означає:
            // чекаємо довше і перевіряємо ще раз, щоб не видати хибну помилку.
            scopeReady = EdtResourceSets.waitDerivedData(project, DERIVED_DATA_RETRY_WAIT_MS);
            result = validateOnce(query, project, provider, isDcs, scopeReady);
            if (!scopeReady && hasTableNotFound(result)) {
                throw new IllegalStateException(semanticUnavailable(
                        "модель таблиць БД проєкту ще не обчислена, тому «таблиця не знайдена» " //$NON-NLS-1$
                        + "могло б бути хибним")); //$NON-NLS-1$
            }
        }
        return result;
    }

    /** Одна спроба: BM-aware набір EDT, потім набір з інжектора мови. */
    private static JsonObject validateOnce(String query, IProject project,
            IResourceServiceProvider provider, boolean isDcs, boolean scopeReady) throws Exception {
        // Якщо не спрацював жоден набір — інструмент падає: мовчазна деградація
        // до синтаксису вже коштувала нам хибного valid:true.
        Exception firstFailure = null;
        for (ResourceSet resourceSet : new ResourceSet[] {
                EdtResourceSets.bmAware(project),
                EdtResourceSets.fromLanguageProvider(project, provider)}) {
            if (resourceSet == null) {
                continue;
            }
            try {
                return validateIn(resourceSet, provider, query, project, isDcs, scopeReady);
            } catch (Exception | LinkageError e) {
                if (firstFailure == null) {
                    firstFailure = e instanceof Exception exception ? exception
                            : new IllegalStateException(e.toString(), e);
                }
            }
        }
        throw new IllegalStateException(semanticUnavailable(firstFailure == null
                ? "не вдалося створити ResourceSet проєкту" //$NON-NLS-1$
                : firstFailure.getClass().getSimpleName() + ": " + firstFailure.getMessage()), //$NON-NLS-1$
                firstFailure);
    }

    /** Чи є серед помилок «таблиця не знайдена» — ознака, що скоуп міг бути порожній. */
    private static boolean hasTableNotFound(JsonObject result) {
        JsonArray errors = result.getAsJsonArray("errors"); //$NON-NLS-1$
        if (errors == null) {
            return false;
        }
        for (JsonElement element : errors) {
            JsonObject error = element.getAsJsonObject();
            if (error.has("code") //$NON-NLS-1$
                    && TABLE_NOT_FOUND_CODE.equals(error.get("code").getAsString())) { //$NON-NLS-1$
                return true;
            }
        }
        return false;
    }

    private static JsonObject validateIn(ResourceSet resourceSet, IResourceServiceProvider provider,
            String query, IProject project, boolean isDcs, boolean scopeReady) throws Exception {
        URI uri = URI.createPlatformResourceURI(
                project.getName() + "/" + dummyName(isDcs), true); //$NON-NLS-1$
        Resource resource = createResource(provider, resourceSet, uri);
        try {
            resource.load(new ByteArrayInputStream(query.getBytes(StandardCharsets.UTF_8)),
                    Map.of(XtextResource.OPTION_ENCODING, StandardCharsets.UTF_8.name()));

            IResourceValidator validator = provider.getResourceValidator();
            if (validator == null) {
                throw new IllegalStateException("інжектор мови не надає IResourceValidator"); //$NON-NLS-1$
            }
            List<Issue> issues = validator.validate(resource, CheckMode.ALL, CancelIndicator.NullImpl);

            JsonArray errors = new JsonArray();
            JsonArray warnings = new JsonArray();
            for (Issue issue : issues) {
                JsonObject item = issueJson(
                        issue.getSeverity() == Severity.ERROR ? "error" //$NON-NLS-1$
                                : issue.getSeverity() == Severity.WARNING ? "warning" : "info", //$NON-NLS-1$ //$NON-NLS-2$
                        issue.getLineNumber() == null ? 0 : issue.getLineNumber().intValue(),
                        issue.getColumn() == null ? 0 : issue.getColumn().intValue(),
                        issue.getMessage());
                if (issue.getOffset() != null) {
                    item.addProperty("offset", issue.getOffset()); //$NON-NLS-1$
                }
                if (issue.getLength() != null) {
                    item.addProperty("length", issue.getLength()); //$NON-NLS-1$
                }
                if (issue.getCode() != null) {
                    item.addProperty("code", issue.getCode()); //$NON-NLS-1$
                }
                if (issue.getSeverity() == Severity.ERROR) {
                    errors.add(item);
                } else if (issue.getSeverity() == Severity.WARNING) {
                    warnings.add(item);
                }
            }
            JsonObject result = resultJson("semantic", errors, warnings); //$NON-NLS-1$
            result.addProperty("project", project.getName()); //$NON-NLS-1$
            result.addProperty("scopeReady", scopeReady); //$NON-NLS-1$
            return result;
        } finally {
            resource.unload();
            resourceSet.getResources().remove(resource);
        }
    }

    /**
     * Ресурс потрібної мови: фабрика з інжектора мови (а не
     * {@code resourceSet.createResource}) — так мова визначена однозначно,
     * незалежно від стану глобального реєстру фабрик.
     */
    private static Resource createResource(IResourceServiceProvider provider, ResourceSet resourceSet,
            URI uri) {
        IResourceFactory factory = provider.get(IResourceFactory.class);
        if (factory != null) {
            Resource resource = factory.createResource(uri);
            if (resource != null) {
                resourceSet.getResources().add(resource);
                return resource;
            }
        }
        Resource resource = resourceSet.createResource(uri);
        if (resource == null) {
            throw new IllegalStateException("ResourceSet не створив ресурс " + uri.lastSegment()); //$NON-NLS-1$
        }
        return resource;
    }

    private static String dummyName(boolean isDcs) {
        return isDcs ? DUMMY_QL_DCS : DUMMY_QL;
    }

    /** Провайдер сервісів мови з реєстру Xtext за розширенням ресурсу. */
    private static IResourceServiceProvider provider(boolean isDcs) {
        URI uri = URI.createURI(dummyName(isDcs));
        IResourceServiceProvider provider =
                IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(uri);
        if (provider == null) {
            throw new IllegalStateException("Мова запитів не зареєстрована в Xtext-реєстрі " //$NON-NLS-1$
                    + "(розширення '" + (isDcs ? "qldcs" : "ql") + "'). Бандл " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    + (isDcs ? "com._1c.g5.v8.dt.ql.dcs.ui" : "com._1c.g5.v8.dt.ql.ui") //$NON-NLS-1$ //$NON-NLS-2$
                    + " ще не активований або відсутній."); //$NON-NLS-1$
        }
        return provider;
    }

    private static <T> T service(Class<T> type, boolean isDcs) {
        T service = provider(isDcs).get(type);
        if (service == null) {
            throw new IllegalStateException("Інжектор мови запитів не надає сервіс: " + type.getName()); //$NON-NLS-1$
        }
        return service;
    }

    private static String semanticUnavailable(String reason) {
        return "Семантична перевірка запиту недоступна: " + reason //$NON-NLS-1$
                + ". Відповідь із самою лише синтаксичною перевіркою була б оманливою " //$NON-NLS-1$
                + "(запит із неіснуючим полем виглядав би валідним), тому виклик відхилено. " //$NON-NLS-1$
                + "Повторіть за хвилину — інфраструктура EDT могла ще не піднятись; " //$NON-NLS-1$
                + "або викличте без параметра 'project', щоб свідомо отримати лише синтаксис."; //$NON-NLS-1$
    }

    /** Колонка (1-based) за зсувом у тексті — INode не має getColumn. */
    private static int columnOf(String text, int offset) {
        int safeOffset = Math.max(0, Math.min(offset, text.length()));
        int lineStart = text.lastIndexOf('\n', safeOffset - 1);
        return safeOffset - lineStart;
    }

    private static JsonObject issueJson(String severity, int line, int column, String message) {
        JsonObject item = new JsonObject();
        item.addProperty("severity", severity); //$NON-NLS-1$
        item.addProperty("line", line); //$NON-NLS-1$
        item.addProperty("column", column); //$NON-NLS-1$
        item.addProperty("message", message); //$NON-NLS-1$
        return item;
    }

    private static JsonObject resultJson(String level, JsonArray errors, JsonArray warnings) {
        JsonObject result = new JsonObject();
        result.addProperty("valid", errors.isEmpty()); //$NON-NLS-1$
        result.addProperty("level", level); //$NON-NLS-1$
        result.addProperty("errorCount", errors.size()); //$NON-NLS-1$
        result.addProperty("warningCount", warnings.size()); //$NON-NLS-1$
        result.add("errors", errors); //$NON-NLS-1$
        if (!warnings.isEmpty()) {
            result.add("warnings", warnings); //$NON-NLS-1$
        }
        return result;
    }

    private static String required(JsonObject arguments, String name) {
        if (!arguments.has(name) || arguments.get(name).getAsString().isBlank()) {
            throw new IllegalArgumentException("Обов'язковий параметр відсутній: " + name); //$NON-NLS-1$
        }
        return arguments.get(name).getAsString();
    }
}
