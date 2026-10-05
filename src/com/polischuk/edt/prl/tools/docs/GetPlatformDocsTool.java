/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.docs;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.polischuk.edt.prl.edt.EdtServices;
import com.polischuk.edt.prl.edt.V8Access;
import com.polischuk.edt.prl.tools.McpTool;

import com._1c.g5.v8.dt.platform.doc.IFullTextSearchResult;
import com._1c.g5.v8.dt.platform.doc.IFullTextSearchResults;
import com._1c.g5.v8.dt.platform.doc.PlatformDocProvider;
import com._1c.g5.v8.dt.platform.doc.PlatformDocTree;
import com._1c.g5.v8.dt.platform.doc.PlatformDocTreeLoader;
import com._1c.g5.v8.dt.platform.doc.PlatformDocTreeNode;
import com._1c.g5.v8.dt.platform.version.IRuntimeVersionSupport;
import com._1c.g5.v8.dt.platform.version.Version;

/**
 * Пошук у довідці платформи 1С (синтакс-помічник): глобальні методи, об'єкти платформи,
 * їхні методи/властивості/події/конструктори, мова запитів.
 *
 * Джерело — бандл com._1c.g5.v8.dt.platform.doc (satree.xml + HTML-сторінки nl/{lang}/html
 * + Lucene-індекс nl/{lang}/saindex). Публічний API бандла: {@link PlatformDocProvider}.
 * Провайдер в EDT створюється Guice-інжектором bsl.ui і не публікується в OSGi-реєстрі,
 * тому тут він збирається вручну: внутрішній PlatformDocLoader (публічний конструктор без
 * параметрів) — через class loader бандла, package-private конструктор
 * {@link PlatformDocTreeLoader} — через рефлексію.
 */
public final class GetPlatformDocsTool implements McpTool {

    private static final String DOC_BUNDLE_ID = "com._1c.g5.v8.dt.platform.doc"; //$NON-NLS-1$
    private static final String DOC_LOADER_CLASS = "com._1c.g5.v8.dt.internal.platform.doc.PlatformDocLoader"; //$NON-NLS-1$

    private static final int MAX_PAGES_WITH_TEXT = 3;
    private static final int MAX_MATCHES_LISTED = 50;
    private static final int MAX_MEMBERS_LISTED = 300;
    private static final int MAX_TEXT_LENGTH = 15000;
    private static final int MAX_SEARCH_RESULTS = 15;

    /** Лінива ініціалізація — стан спільний для всіх викликів інструмента. */
    private static final Object LOCK = new Object();
    private static PlatformDocProvider provider;
    private static Object docLoader;
    private static Method getHtmlInputStream;

    @Override
    public String name() {
        return "get_platform_docs"; //$NON-NLS-1$
    }

    @Override
    public String description() {
        return "Довідка платформи 1С (синтакс-помічник): глобальні методи, типи платформи та їхні " //$NON-NLS-1$
                + "методи/властивості, мова запитів. Пошук за російським або англійським іменем."; //$NON-NLS-1$
    }

    @Override
    public JsonObject inputSchema() {
        return JsonParser.parseString("""
                {"type":"object","properties":{
                  "query":{"type":"string","description":"Ім'я елемента: глобальний метод (СтрНайти), тип платформи (ТаблицаЗначений), функція мови запитів; або шлях сторінки з попереднього результату (path)"},
                  "member":{"type":"string","description":"Ім'я метода/властивості/події типу з query (напр. Найти для ТаблицаЗначений)"},
                  "project":{"type":"string","description":"Проєкт EDT — для версії платформи; без нього береться версія єдиного проєкту або остання відома"},
                  "lang":{"type":"string","description":"Мова сторінок довідки: ru (типово) або en"}
                },"required":["query"]}""").getAsJsonObject(); //$NON-NLS-1$
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
        String query = arguments.get("query").getAsString().strip(); //$NON-NLS-1$
        String member = optional(arguments, "member"); //$NON-NLS-1$
        String projectName = optional(arguments, "project"); //$NON-NLS-1$
        String lang = optional(arguments, "lang"); //$NON-NLS-1$
        if (lang == null || (!"en".equals(lang) && !"ru".equals(lang))) { //$NON-NLS-1$ //$NON-NLS-2$
            lang = "ru"; //$NON-NLS-1$
        }

        PlatformDocProvider docProvider = provider();
        Version version = resolveVersion(projectName);

        JsonObject result = new JsonObject();
        result.addProperty("query", query); //$NON-NLS-1$
        result.addProperty("platformVersion", version.toString()); //$NON-NLS-1$

        // Запит у формі шляху сторінки (з попередньої відповіді) — читаємо сторінку напряму.
        if (query.indexOf('/') >= 0) {
            String text = loadPageText(version, lang, query);
            if (text == null) {
                throw new IllegalArgumentException("Сторінку довідки не знайдено за шляхом: " + query); //$NON-NLS-1$
            }
            JsonObject page = new JsonObject();
            page.addProperty("path", query); //$NON-NLS-1$
            page.addProperty("text", text); //$NON-NLS-1$
            JsonArray pages = new JsonArray();
            pages.add(page);
            result.add("matches", pages); //$NON-NLS-1$
            return result;
        }

        PlatformDocTree tree = docProvider.getTree(version);
        List<PlatformDocTreeNode> matches = findByName(tree.getRootNode(), query);

        // Пошук члена типу всередині знайдених вузлів.
        if (member != null && !matches.isEmpty()) {
            List<PlatformDocTreeNode> memberMatches = new ArrayList<>();
            for (PlatformDocTreeNode node : matches) {
                memberMatches.addAll(findByName(node, member));
            }
            if (memberMatches.isEmpty()) {
                result.addProperty("note", "Елемент '" + member + "' не знайдено серед членів '" + query //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        + "'. Нижче — сам об'єкт із переліком його членів."); //$NON-NLS-1$
            } else {
                matches = memberMatches;
            }
        }

        if (!matches.isEmpty()) {
            boolean withText = matches.size() <= MAX_PAGES_WITH_TEXT;
            JsonArray matchesJson = new JsonArray();
            int listed = 0;
            for (PlatformDocTreeNode node : matches) {
                if (listed++ >= MAX_MATCHES_LISTED) {
                    break;
                }
                matchesJson.add(describeNode(node, version, lang, withText));
            }
            result.add("matches", matchesJson); //$NON-NLS-1$
            if (!withText) {
                result.addProperty("hint", "Збігів декілька — текст сторінок не завантажено. " //$NON-NLS-1$ //$NON-NLS-2$
                        + "Повторіть виклик із query = потрібний path."); //$NON-NLS-1$
            }
            return result;
        }

        // Точного збігу немає — повнотекстовий пошук (первинний індекс імен + Lucene).
        fullTextSearch(docProvider, query, version, lang, result);
        return result;
    }

    // ---------------------------------------------------------------- пошук у дереві

    /** Обхід дерева: вузли, чиє ru/en ім'я (або ім'я елемента) точно збігається із запитом. */
    private static List<PlatformDocTreeNode> findByName(PlatformDocTreeNode root, String query) {
        String needle = normalize(query);
        List<PlatformDocTreeNode> matches = new ArrayList<>();
        if (root == null) {
            return matches;
        }
        Deque<PlatformDocTreeNode> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            PlatformDocTreeNode node = stack.pop();
            if (node != root && nameMatches(node, needle)) {
                matches.add(node);
            }
            for (PlatformDocTreeNode child : node.getChildren()) {
                stack.push(child);
            }
        }
        return matches;
    }

    private static boolean nameMatches(PlatformDocTreeNode node, String needle) {
        return needle.equals(normalize(node.getName("ru"))) //$NON-NLS-1$
                || needle.equals(normalize(node.getName("en"))) //$NON-NLS-1$
                || needle.equals(normalize(node.getElementName(true)))
                || needle.equals(normalize(node.getElementName(false)));
    }

    private static String normalize(String value) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        return value.toLowerCase(Locale.ROOT).replace("ё", "е").strip(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ---------------------------------------------------------------- опис вузла

    private JsonObject describeNode(PlatformDocTreeNode node, Version version, String lang, boolean withText) {
        JsonObject json = new JsonObject();
        json.addProperty("ru", node.getName("ru")); //$NON-NLS-1$ //$NON-NLS-2$
        json.addProperty("en", node.getName("en")); //$NON-NLS-1$ //$NON-NLS-2$
        json.addProperty("path", node.getPath()); //$NON-NLS-1$
        json.addProperty("context", breadcrumb(node)); //$NON-NLS-1$
        String kind = kindOf(node);
        if (kind != null) {
            json.addProperty("kind", kind); //$NON-NLS-1$
        }
        if (withText && node.getPath() != null) {
            String text = loadPageText(version, lang, node.getPath());
            if (text == null && !"en".equals(lang)) { //$NON-NLS-1$
                text = loadPageText(version, "en", node.getPath()); //$NON-NLS-1$
            }
            if (text != null) {
                json.addProperty("text", text); //$NON-NLS-1$
            }
        }
        if (withText && node.hasChildren()) {
            json.add("members", listMembers(node)); //$NON-NLS-1$
        }
        return json;
    }

    /** Ланцюжок батьків — контекст на кшталт "Прикладные объекты / ТаблицаЗначений". */
    private static String breadcrumb(PlatformDocTreeNode node) {
        List<String> names = new ArrayList<>();
        for (PlatformDocTreeNode parent = node.getParent(); parent != null; parent = parent.getParent()) {
            String name = parent.getName("ru"); //$NON-NLS-1$
            if (name != null && !name.isBlank()) {
                names.add(0, name);
            }
        }
        return String.join(" / ", names); //$NON-NLS-1$
    }

    private static String kindOf(PlatformDocTreeNode node) {
        String path = node.getPath();
        if (path == null) {
            return Boolean.TRUE.equals(node.getIsCatalog()) ? "розділ" : null; //$NON-NLS-1$
        }
        if (path.contains("/methods/")) { //$NON-NLS-1$
            return "метод"; //$NON-NLS-1$
        }
        if (path.contains("/properties/")) { //$NON-NLS-1$
            return "властивість"; //$NON-NLS-1$
        }
        if (path.contains("/events/")) { //$NON-NLS-1$
            return "подія"; //$NON-NLS-1$
        }
        if (path.contains("/ctors/")) { //$NON-NLS-1$
            return "конструктор"; //$NON-NLS-1$
        }
        if (path.startsWith("SyntaxHelperQueries")) { //$NON-NLS-1$
            return "мова запитів"; //$NON-NLS-1$
        }
        return null;
    }

    /** Члени об'єкта: дочірні розділи (Методи/Властивості/...) та їхні елементи — лише імена. */
    private static JsonArray listMembers(PlatformDocTreeNode node) {
        JsonArray members = new JsonArray();
        int count = 0;
        for (PlatformDocTreeNode group : node.getChildren()) {
            if (!group.hasChildren()) {
                count = addMember(members, group, null, count);
                continue;
            }
            for (PlatformDocTreeNode child : group.getOrderedChildren("ru")) { //$NON-NLS-1$
                count = addMember(members, child, group.getName("ru"), count); //$NON-NLS-1$
                if (count >= MAX_MEMBERS_LISTED) {
                    return members;
                }
            }
        }
        return members;
    }

    private static int addMember(JsonArray members, PlatformDocTreeNode node, String group, int count) {
        if (count >= MAX_MEMBERS_LISTED) {
            return count;
        }
        JsonObject json = new JsonObject();
        json.addProperty("ru", node.getName("ru")); //$NON-NLS-1$ //$NON-NLS-2$
        json.addProperty("en", node.getName("en")); //$NON-NLS-1$ //$NON-NLS-2$
        if (group != null) {
            json.addProperty("group", group); //$NON-NLS-1$
        }
        if (node.getPath() != null) {
            json.addProperty("path", node.getPath()); //$NON-NLS-1$
        }
        members.add(json);
        return count + 1;
    }

    // ---------------------------------------------------------------- повнотекстовий пошук

    private static void fullTextSearch(PlatformDocProvider docProvider, String query, Version version,
            String lang, JsonObject result) {
        try {
            IFullTextSearchResults results = docProvider.doFullTextSearch(query, new NullProgressMonitor(),
                    version, lang, "ru".equals(lang)); //$NON-NLS-1$
            List<? extends IFullTextSearchResult> found =
                    results.doSearch(0, MAX_SEARCH_RESULTS, new NullProgressMonitor());
            JsonArray foundJson = new JsonArray();
            for (IFullTextSearchResult item : found) {
                JsonObject json = new JsonObject();
                json.addProperty("title", item.getTitle()); //$NON-NLS-1$
                json.addProperty("path", item.getPath()); //$NON-NLS-1$
                json.addProperty("summary", item.getSummary()); //$NON-NLS-1$
                foundJson.add(json);
            }
            result.addProperty("totalFound", results.getCount()); //$NON-NLS-1$
            result.add("searchResults", foundJson); //$NON-NLS-1$
            result.addProperty("note", "Точного збігу за іменем немає — показано результати повнотекстового " //$NON-NLS-1$ //$NON-NLS-2$
                    + "пошуку. Для тексту сторінки повторіть виклик із query = потрібний path."); //$NON-NLS-1$
        } catch (RuntimeException e) {
            result.addProperty("note", "Точного збігу немає; повнотекстовий пошук недоступний: " + e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    // ---------------------------------------------------------------- сторінки довідки

    /** Текст HTML-сторінки довідки або null, якщо сторінки немає. */
    private static String loadPageText(Version version, String lang, String path) {
        try {
            InputStream stream = (InputStream) getHtmlInputStream.invoke(docLoader, version, lang, path);
            if (stream == null) {
                return null;
            }
            try (stream) {
                return htmlToText(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof IOException) {
                return null; // сторінки немає в цій мові/версії
            }
            throw new IllegalStateException("Не вдалося прочитати сторінку довідки: " + path, e.getCause()); //$NON-NLS-1$
        } catch (ReflectiveOperationException | IOException e) {
            throw new IllegalStateException("Не вдалося прочитати сторінку довідки: " + path, e); //$NON-NLS-1$
        }
    }

    private static String htmlToText(String html) {
        String text = html
                .replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ") //$NON-NLS-1$ //$NON-NLS-2$
                .replaceAll("(?i)<br\\s*/?>", "\n") //$NON-NLS-1$ //$NON-NLS-2$
                .replaceAll("(?i)</(p|div|h[1-6]|li|tr)>", "\n") //$NON-NLS-1$ //$NON-NLS-2$
                .replaceAll("<[^>]+>", " ") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                .replace("&quot;", "\"").replace("&amp;", "&") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                .replaceAll("[ \\t]+", " ") //$NON-NLS-1$ //$NON-NLS-2$
                .replaceAll("\\n\\s*\\n+", "\n\n") //$NON-NLS-1$ //$NON-NLS-2$
                .strip();
        return text.length() > MAX_TEXT_LENGTH ? text.substring(0, MAX_TEXT_LENGTH) + "…" : text; //$NON-NLS-1$
    }

    // ---------------------------------------------------------------- ініціалізація

    /** Версія платформи: з указаного проєкту; якщо проєкт не вказано і його не вдалося
     *  визначити однозначно — остання відома платформі EDT версія. */
    private static Version resolveVersion(String projectName) {
        try {
            return EdtServices.require(IRuntimeVersionSupport.class)
                    .getRuntimeVersion(V8Access.resolveEclipseProject(projectName));
        } catch (RuntimeException e) {
            if (projectName != null && !projectName.isBlank()) {
                throw e; // явно вказаний проєкт має існувати
            }
            return Version.LATEST;
        }
    }

    /**
     * Збирає {@link PlatformDocProvider} так само, як це робить Guice у bsl.ui:
     * new PlatformDocProvider(new PlatformDocLoader(), new PlatformDocTreeLoader(loader)).
     */
    private static PlatformDocProvider provider() {
        synchronized (LOCK) {
            if (provider != null) {
                return provider;
            }
            Bundle bundle = Platform.getBundle(DOC_BUNDLE_ID);
            if (bundle == null) {
                throw new IllegalStateException("Бандл довідки платформи відсутній: " + DOC_BUNDLE_ID); //$NON-NLS-1$
            }
            try {
                Class<?> loaderClass = bundle.loadClass(DOC_LOADER_CLASS);
                Object loader = loaderClass.getConstructor().newInstance();
                Constructor<PlatformDocTreeLoader> treeLoaderCtor =
                        PlatformDocTreeLoader.class.getDeclaredConstructor(loaderClass);
                if (!treeLoaderCtor.trySetAccessible()) {
                    throw new IllegalStateException(
                            "Немає доступу до конструктора PlatformDocTreeLoader (module access)"); //$NON-NLS-1$
                }
                PlatformDocTreeLoader treeLoader = treeLoaderCtor.newInstance(loader);
                Method htmlStream = loaderClass.getMethod("getHtmlInputStream", //$NON-NLS-1$
                        Version.class, String.class, String.class);
                provider = PlatformDocProvider.class
                        .getConstructor(loaderClass, PlatformDocTreeLoader.class)
                        .newInstance(loader, treeLoader);
                docLoader = loader;
                getHtmlInputStream = htmlStream;
                return provider;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                        "Не вдалося ініціалізувати провайдер довідки платформи: " + e, e); //$NON-NLS-1$
            }
        }
    }

    private static String optional(JsonObject arguments, String key) {
        return arguments.has(key) && !arguments.get(key).isJsonNull()
                ? arguments.get(key).getAsString().strip()
                : null;
    }
}
