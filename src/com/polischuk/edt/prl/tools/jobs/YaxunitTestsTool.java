/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.jobs;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.RuntimeExecutionArguments;
import com.e1c.g5.dt.applications.ApplicationUpdateType;
import com.e1c.g5.dt.applications.ExecutionContext;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.polischuk.edt.prl.edt.EdtServices;
import com.polischuk.edt.prl.edt.V8Access;
import com.polischuk.edt.prl.edt.WriteGate;
import com.polischuk.edt.prl.tools.McpTool;

/**
 * Запуск юніт-тестів YAxUnit і розбір JUnit-звіту.
 *
 * <p>Механіка YAxUnit: 1С:Підприємство стартує з параметром запуску
 * {@code RunUnitTests=<шлях до json>}; розширення YAxUnit ловить цей параметр, проганяє
 * тести за фільтром із файлу, пише звіт і закривається. Формат файла звірений із самим
 * YAxUnit (модулі {@code ЮТФабрика.ПараметрыЗапуска} і {@code ЮТПараметрыЗапускаСлужебный}),
 * а не вгаданий.
 *
 * <p>Запуск іде рідним для EDT шляхом — {@code IApplicationManager.start} із
 * {@code RuntimeExecutionArguments.setStartupOption}, тому працюють ті самі налаштування
 * інформбази, що й у кнопки «Запустити» в EDT: не треба ані шукати 1cv8.exe, ані складати
 * рядок з'єднання.
 */
public final class YaxunitTestsTool implements McpTool {

    private static final int DEFAULT_TIMEOUT_SECONDS = 300;
    private static final long POLL_INTERVAL_MS = 1000;
    private static final int MAX_FAILURES = 50;
    private static final int MAX_MESSAGE_CHARS = 2000;

    @Override
    public String name() {
        return "yaxunit_tests"; //$NON-NLS-1$
    }

    @Override
    public String description() {
        return "Прогін юніт-тестів YAxUnit: стартує 1С:Підприємство з параметром RunUnitTests, " //$NON-NLS-1$
                + "чекає звіт і повертає підсумок (пройдено/впало/пропущено, стектрейси). " //$NON-NLS-1$
                + "Фільтри через кому: extensions, modules, tests (Модуль.Метод), suites, tags, contexts. " //$NON-NLS-1$
                + "wait=false — не чекати, повернути jobId і шлях до звіту. " //$NON-NLS-1$
                + "Потрібне розширення YAxUnit у базі та підключена до проєкту ІБ (list_applications)."; //$NON-NLS-1$
    }

    @Override
    public JsonObject inputSchema() {
        return JsonParser.parseString("""
                {"type":"object","properties":{
                  "project":{"type":"string","description":"Ім'я проєкту EDT (необов'язково, якщо проєкт один)"},
                  "application":{"type":"string","description":"Ім'я застосунку (ІБ) зі list_applications; без нього — застосунок за замовчуванням"},
                  "extensions":{"type":"string","description":"Імена розширень через кому; без них YAxUnit бере своє типове"},
                  "modules":{"type":"string","description":"Імена загальних модулів із тестами, через кому"},
                  "tests":{"type":"string","description":"Повні імена тестів через кому: Модуль.Метод або Модуль.Метод.Контекст"},
                  "suites":{"type":"string","description":"Імена тестових наборів через кому"},
                  "tags":{"type":"string","description":"Теги тестів через кому"},
                  "contexts":{"type":"string","description":"Контексти виконання через кому: Server, Client, ExternalConnection"},
                  "runSettingsPath":{"type":"string","description":"Готовий JSON налаштувань YAxUnit — замість фільтрів вище"},
                  "logLevel":{"type":"string","enum":["info","debug","trace"],"default":"debug"},
                  "clientType":{"type":"string","enum":["thin","thick"],"default":"thin","description":"Тип клієнта 1С для прогону"},
                  "updateBeforeLaunch":{"type":"boolean","default":true,"description":"Оновити конфігурацію ІБ перед прогоном"},
                  "wait":{"type":"boolean","default":true,"description":"Чекати завершення й повернути підсумок; false — одразу jobId"},
                  "timeoutSeconds":{"type":"integer","default":300}
                }}""").getAsJsonObject(); //$NON-NLS-1$
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
        WriteGate.check(); // тести виконують код у робочій базі та змінюють дані

        String projectName = arguments.has("project") ? arguments.get("project").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$
        boolean wait = !arguments.has("wait") || arguments.get("wait").getAsBoolean(); //$NON-NLS-1$ //$NON-NLS-2$
        int timeoutSeconds = arguments.has("timeoutSeconds") //$NON-NLS-1$
                ? Math.max(10, arguments.get("timeoutSeconds").getAsInt()) : DEFAULT_TIMEOUT_SECONDS; //$NON-NLS-1$

        IProject project = V8Access.resolveEclipseProject(projectName);
        IApplicationManager manager = EdtServices.require(IApplicationManager.class);
        IApplication application = RunApplicationTool.resolveApplication(manager, project,
                arguments.has("application") ? arguments.get("application").getAsString() : null); //$NON-NLS-1$ //$NON-NLS-2$

        Path sessionDir = createSessionDir();
        Path reportPath = sessionDir.resolve("report.xml"); //$NON-NLS-1$
        Path logPath = sessionDir.resolve("yaxunit.log"); //$NON-NLS-1$
        Path settingsPath = writeRunSettings(arguments, sessionDir, reportPath, logPath);

        if (!arguments.has("updateBeforeLaunch") || arguments.get("updateBeforeLaunch").getAsBoolean()) { //$NON-NLS-1$ //$NON-NLS-2$
            // без оновлення 1С при старті покаже модальне вікно про застарілу конфігурацію
            // і прогін зависне на ньому назавжди
            manager.update(application, ApplicationUpdateType.INCREMENTAL,
                    new ExecutionContext(), new NullProgressMonitor());
        }

        RuntimeExecutionArguments runtimeArguments = new RuntimeExecutionArguments();
        runtimeArguments.setStartupOption("RunUnitTests=" + settingsPath); //$NON-NLS-1$
        runtimeArguments.setDisableStartupMessages(true);
        ExecutionContext context = new ExecutionContext();
        context.setProperty(IApplication.CONTEXT_CLIENT_TYPE, clientTypeId(arguments));
        context.setProperty(IApplication.CONTEXT_CLIENT_ARGUMENTS, runtimeArguments);

        Optional<Process> process = manager.start(application, context, new NullProgressMonitor());
        JsonObject job = process.isPresent()
                ? JobManager.wrapProcess(process.get(), "yaxunit_tests — " + application.getName(), //$NON-NLS-1$
                        java.nio.charset.Charset.forName("CP866")) //$NON-NLS-1$
                : new JsonObject();

        JsonObject result = new JsonObject();
        result.addProperty("project", project.getName()); //$NON-NLS-1$
        result.addProperty("application", application.getName()); //$NON-NLS-1$
        result.addProperty("runSettings", settingsPath.toString()); //$NON-NLS-1$
        result.addProperty("reportPath", reportPath.toString()); //$NON-NLS-1$
        result.addProperty("logPath", logPath.toString()); //$NON-NLS-1$
        if (job.has("jobId")) { //$NON-NLS-1$
            result.addProperty("jobId", job.get("jobId").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!wait) {
            result.addProperty("waiting", false); //$NON-NLS-1$
            result.addProperty("hint", "Прогін запущено. Стан процесу — get_job_status, " //$NON-NLS-1$ //$NON-NLS-2$
                    + "звіт з'явиться у reportPath; повторіть виклик із wait=true або читайте файл."); //$NON-NLS-1$
            return result;
        }

        if (!awaitReport(reportPath, timeoutSeconds)) {
            result.addProperty("completed", false); //$NON-NLS-1$
            result.addProperty("timeoutSeconds", timeoutSeconds); //$NON-NLS-1$
            result.addProperty("message", "Звіт не з'явився за " + timeoutSeconds //$NON-NLS-1$
                    + " с. 1С могла показати модальне вікно або тести ще йдуть. " //$NON-NLS-1$
                    + "Подивіться get_job_status і лог у logPath; звіт лишається у reportPath."); //$NON-NLS-1$
            return result;
        }
        result.addProperty("completed", true); //$NON-NLS-1$
        addReport(result, reportPath);
        return result;
    }

    /**
     * Ідентифікатор типу клієнта для {@code ExecutionContext}. Без нього
     * {@code IApplicationManager.start} падає з «Контекст выполнения не предоставляет
     * тип клиента для запуска». Значення — id типів компонент платформи з розширення
     * {@code runtimeComponentExecutors} (див. plugin.xml бандла platform.services.core).
     */
    static String clientTypeId(JsonObject arguments) {
        boolean thick = arguments.has("clientType") //$NON-NLS-1$
                && "thick".equalsIgnoreCase(arguments.get("clientType").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        return thick
                ? "com._1c.g5.v8.dt.platform.services.core.componentTypes.ThickClient" //$NON-NLS-1$
                : "com._1c.g5.v8.dt.platform.services.core.componentTypes.ThinClient"; //$NON-NLS-1$
    }

    /** Каталог сесії прогону — щоб звіти різних прогонів не перетирали один одного. */
    private static Path createSessionDir() throws IOException {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")); //$NON-NLS-1$
        Path dir = Path.of(System.getProperty("user.home"), ".edt-mcp", "yaxunit", stamp); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Files.createDirectories(dir);
        return dir;
    }

    /**
     * Файл параметрів запуску YAxUnit. Ключі — з {@code ЮТФабрика.ПараметрыЗапуска}:
     * reportPath, reportFormat, closeAfterTests, showReport, filter{...}, logging{...}.
     * Порожні фільтри не пишемо: YAxUnit розрізняє «немає фільтра» і «порожній список».
     */
    private static Path writeRunSettings(JsonObject arguments, Path sessionDir, Path reportPath,
            Path logPath) throws IOException {
        if (arguments.has("runSettingsPath") //$NON-NLS-1$
                && !arguments.get("runSettingsPath").getAsString().isBlank()) { //$NON-NLS-1$
            Path ready = Path.of(arguments.get("runSettingsPath").getAsString()); //$NON-NLS-1$
            if (!Files.isRegularFile(ready)) {
                throw new IllegalArgumentException("Файл налаштувань не знайдено: " + ready); //$NON-NLS-1$
            }
            return ready;
        }

        JsonObject filter = new JsonObject();
        addFilter(filter, arguments, "extensions"); //$NON-NLS-1$
        addFilter(filter, arguments, "modules"); //$NON-NLS-1$
        addFilter(filter, arguments, "suites"); //$NON-NLS-1$
        addFilter(filter, arguments, "tags"); //$NON-NLS-1$
        addFilter(filter, arguments, "contexts"); //$NON-NLS-1$
        addFilter(filter, arguments, "tests"); //$NON-NLS-1$

        JsonObject logging = new JsonObject();
        logging.addProperty("file", logPath.toString()); //$NON-NLS-1$
        logging.addProperty("console", false); //$NON-NLS-1$
        logging.addProperty("level", arguments.has("logLevel") //$NON-NLS-1$ //$NON-NLS-2$
                ? arguments.get("logLevel").getAsString() : "debug"); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject settings = new JsonObject();
        settings.addProperty("reportPath", reportPath.toString()); //$NON-NLS-1$
        settings.addProperty("reportFormat", "jUnit"); //$NON-NLS-1$ //$NON-NLS-2$
        settings.addProperty("closeAfterTests", true); //$NON-NLS-1$
        settings.addProperty("showReport", false); //$NON-NLS-1$
        if (filter.size() > 0) {
            settings.add("filter", filter); //$NON-NLS-1$
        }
        settings.add("logging", logging); //$NON-NLS-1$

        Path settingsPath = sessionDir.resolve("run-settings.json"); //$NON-NLS-1$
        Files.writeString(settingsPath, settings.toString(), StandardCharsets.UTF_8);
        return settingsPath;
    }

    private static void addFilter(JsonObject filter, JsonObject arguments, String name) {
        if (!arguments.has(name) || arguments.get(name).getAsString().isBlank()) {
            return;
        }
        JsonArray values = new JsonArray();
        for (String value : arguments.get(name).getAsString().split(",")) { //$NON-NLS-1$
            String clean = value.strip();
            if (!clean.isEmpty()) {
                values.add(clean);
            }
        }
        if (values.size() > 0) {
            filter.add(name, values);
        }
    }

    /** Чекає появи звіту; файл вважається готовим, коли перестав рости. */
    private static boolean awaitReport(Path reportPath, int timeoutSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        long lastSize = -1;
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(reportPath)) {
                try {
                    long size = Files.size(reportPath);
                    if (size > 0 && size == lastSize) {
                        return true;
                    }
                    lastSize = size;
                } catch (IOException e) {
                    // файл саме перезаписується — просто чекаємо далі
                }
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        return false;
    }

    /** Розбір JUnit-звіту: підсумки й перелік падінь із повідомленнями. */
    private static void addReport(JsonObject result, Path reportPath) throws Exception {
        String xml = Files.readString(reportPath, StandardCharsets.UTF_8);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false); //$NON-NLS-1$
        DocumentBuilder builder = factory.newDocumentBuilder();
        Element root = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                .getDocumentElement();

        List<Element> suites = new ArrayList<>();
        if ("testsuite".equals(root.getTagName())) { //$NON-NLS-1$
            suites.add(root);
        } else {
            collect(root, "testsuite", suites); //$NON-NLS-1$
        }

        int total = 0;
        int failures = 0;
        int errors = 0;
        int skipped = 0;
        double time = 0;
        JsonArray failed = new JsonArray();
        JsonArray suitesJson = new JsonArray();
        for (Element suite : suites) {
            int suiteTotal = intAttr(suite, "tests"); //$NON-NLS-1$
            int suiteFailures = intAttr(suite, "failures"); //$NON-NLS-1$
            int suiteErrors = intAttr(suite, "errors"); //$NON-NLS-1$
            int suiteSkipped = intAttr(suite, "skipped"); //$NON-NLS-1$
            total += suiteTotal;
            failures += suiteFailures;
            errors += suiteErrors;
            skipped += suiteSkipped;
            time += doubleAttr(suite, "time"); //$NON-NLS-1$

            JsonObject suiteJson = new JsonObject();
            suiteJson.addProperty("name", suite.getAttribute("name")); //$NON-NLS-1$ //$NON-NLS-2$
            suiteJson.addProperty("tests", suiteTotal); //$NON-NLS-1$
            suiteJson.addProperty("failures", suiteFailures + suiteErrors); //$NON-NLS-1$
            suiteJson.addProperty("skipped", suiteSkipped); //$NON-NLS-1$
            suitesJson.add(suiteJson);

            List<Element> cases = new ArrayList<>();
            collect(suite, "testcase", cases); //$NON-NLS-1$
            for (Element testCase : cases) {
                List<Element> problems = new ArrayList<>();
                collect(testCase, "failure", problems); //$NON-NLS-1$
                collect(testCase, "error", problems); //$NON-NLS-1$
                if (problems.isEmpty() || failed.size() >= MAX_FAILURES) {
                    continue;
                }
                JsonObject entry = new JsonObject();
                entry.addProperty("suite", suite.getAttribute("name")); //$NON-NLS-1$ //$NON-NLS-2$
                entry.addProperty("test", testCase.getAttribute("name")); //$NON-NLS-1$ //$NON-NLS-2$
                entry.addProperty("kind", problems.get(0).getTagName()); //$NON-NLS-1$
                entry.addProperty("message", problems.get(0).getAttribute("message")); //$NON-NLS-1$ //$NON-NLS-2$
                String details = problems.get(0).getTextContent();
                if (details != null && !details.isBlank()) {
                    entry.addProperty("details", details.length() > MAX_MESSAGE_CHARS //$NON-NLS-1$
                            ? details.substring(0, MAX_MESSAGE_CHARS) + "…" : details.strip()); //$NON-NLS-1$
                }
                failed.add(entry);
            }
        }

        JsonObject summary = new JsonObject();
        summary.addProperty("total", total); //$NON-NLS-1$
        summary.addProperty("passed", Math.max(0, total - failures - errors - skipped)); //$NON-NLS-1$
        summary.addProperty("failures", failures); //$NON-NLS-1$
        summary.addProperty("errors", errors); //$NON-NLS-1$
        summary.addProperty("skipped", skipped); //$NON-NLS-1$
        summary.addProperty("timeSeconds", Math.round(time * 1000) / 1000.0); //$NON-NLS-1$
        result.add("summary", summary); //$NON-NLS-1$
        result.addProperty("success", failures == 0 && errors == 0); //$NON-NLS-1$
        result.add("suites", suitesJson); //$NON-NLS-1$
        result.add("failed", failed); //$NON-NLS-1$
        if (failed.size() >= MAX_FAILURES) {
            result.addProperty("failedTruncated", true); //$NON-NLS-1$
        }
    }

    private static void collect(Element parent, String tagName, List<Element> target) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element) {
                if (tagName.equals(element.getTagName())) {
                    target.add(element);
                } else {
                    collect(element, tagName, target);
                }
            }
        }
    }

    private static int intAttr(Element element, String name) {
        try {
            String value = element.getAttribute(name);
            return value.isEmpty() ? 0 : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double doubleAttr(Element element, String name) {
        try {
            String value = element.getAttribute(name);
            return value.isEmpty() ? 0 : Double.parseDouble(value.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

}
