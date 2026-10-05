/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.code;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.eclipse.core.resources.IProject;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.polischuk.edt.prl.edt.V8Access;
import com.polischuk.edt.prl.edt.WorkspaceFiles;
import com.polischuk.edt.prl.tools.McpTool;

/** Текстовий пошук по модулях BSL проєкту (перший етап; пошук посилань — пізніше). */
public final class CodeSearchTool implements McpTool {

    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final int DEFAULT_SYMBOL_MATCHES = 20;

    @Override
    public String name() {
        return "code_search"; //$NON-NLS-1$
    }

    @Override
    public String description() {
        return "Пошук по коду BSL. query — текст або регулярний вираз (regex:true), " //$NON-NLS-1$
                + "повертає збіги з шляхом, номером рядка і текстом рядка. " //$NON-NLS-1$
                + "symbol — навпаки, «де це визначено»: 'ЗагальнийМодуль.Метод', " //$NON-NLS-1$
                + "'Catalog.Номенклатура.Метод' або просто ім'я методу; повертає модуль, " //$NON-NLS-1$
                + "сигнатуру, межі рядків, тіло і doc-коментар."; //$NON-NLS-1$
    }

    @Override
    public JsonObject inputSchema() {
        return JsonParser.parseString("""
                {"type":"object","properties":{
                  "query":{"type":"string","description":"Текст або регулярний вираз"},
                  "symbol":{"type":"string","description":"Замість query: символ для переходу до визначення — 'Модуль.Метод', 'Kind.Ім'я.Метод' або ім'я методу"},
                  "project":{"type":"string","description":"Ім'я проєкту EDT; без нього — усі 1С-проєкти workspace"},
                  "regex":{"type":"boolean","default":false},
                  "caseSensitive":{"type":"boolean","default":false},
                  "pathFilter":{"type":"string","description":"Підрядок шляху модуля (без регістру)"},
                  "maxResults":{"type":"integer","default":100},
                  "contextLines":{"type":"integer","default":0,"description":"Рядків контексту до і після збігу"}
                }}""").getAsJsonObject(); //$NON-NLS-1$
    }

    @Override
    public JsonElement execute(JsonObject arguments) {
        String symbol = arguments.has("symbol") && !arguments.get("symbol").getAsString().isBlank() //$NON-NLS-1$ //$NON-NLS-2$
                ? arguments.get("symbol").getAsString().trim() : null; //$NON-NLS-1$
        if (symbol != null) {
            return resolveSymbol(symbol,
                    arguments.has("project") ? arguments.get("project").getAsString() : null, //$NON-NLS-1$ //$NON-NLS-2$
                    arguments.has("maxResults") //$NON-NLS-1$
                            ? arguments.get("maxResults").getAsInt() : DEFAULT_SYMBOL_MATCHES); //$NON-NLS-1$
        }
        if (!arguments.has("query") || arguments.get("query").getAsString().isBlank()) { //$NON-NLS-1$ //$NON-NLS-2$
            throw new IllegalArgumentException(
                    "Вкажіть 'query' (пошук по тексту) або 'symbol' (перехід до визначення)."); //$NON-NLS-1$
        }
        String query = arguments.get("query").getAsString(); //$NON-NLS-1$
        String projectName = arguments.has("project") ? arguments.get("project").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$
        boolean regex = arguments.has("regex") && arguments.get("regex").getAsBoolean(); //$NON-NLS-1$ //$NON-NLS-2$
        boolean caseSensitive = arguments.has("caseSensitive") && arguments.get("caseSensitive").getAsBoolean(); //$NON-NLS-1$ //$NON-NLS-2$
        String pathFilter = arguments.has("pathFilter") //$NON-NLS-1$
                ? arguments.get("pathFilter").getAsString().toLowerCase() : null; //$NON-NLS-1$
        int maxResults = arguments.has("maxResults") //$NON-NLS-1$
                ? arguments.get("maxResults").getAsInt() : DEFAULT_MAX_RESULTS; //$NON-NLS-1$
        int contextLines = arguments.has("contextLines") ? arguments.get("contextLines").getAsInt() : 0; //$NON-NLS-1$ //$NON-NLS-2$

        Pattern pattern = compile(query, regex, caseSensitive);

        List<IProject> projects = new ArrayList<>();
        if (projectName != null && !projectName.isBlank()) {
            projects.add(V8Access.resolveEclipseProject(projectName));
        } else {
            V8Access.v8Projects().forEach(p -> projects.add(p.getProject()));
        }

        JsonArray matches = new JsonArray();
        int[] total = {0};
        for (IProject project : projects) {
            WorkspaceFiles.walk(project, "bsl", file -> { //$NON-NLS-1$
                String path = file.getProjectRelativePath().toString();
                if (pathFilter != null && !path.toLowerCase().contains(pathFilter)) {
                    return;
                }
                String[] lines = WorkspaceFiles.read(file).split("\n", -1); //$NON-NLS-1$
                for (int i = 0; i < lines.length; i++) {
                    Matcher matcher = pattern.matcher(lines[i]);
                    if (!matcher.find()) {
                        continue;
                    }
                    total[0]++;
                    if (matches.size() >= maxResults) {
                        continue; // рахуємо total далі, але не додаємо
                    }
                    JsonObject entry = new JsonObject();
                    entry.addProperty("project", project.getName()); //$NON-NLS-1$
                    entry.addProperty("path", path); //$NON-NLS-1$
                    entry.addProperty("line", i + 1); //$NON-NLS-1$
                    entry.addProperty("text", lines[i].strip()); //$NON-NLS-1$
                    if (contextLines > 0) {
                        StringBuilder context = new StringBuilder();
                        int from = Math.max(0, i - contextLines);
                        int to = Math.min(lines.length - 1, i + contextLines);
                        for (int j = from; j <= to; j++) {
                            context.append(j + 1).append(": ").append(lines[j]); //$NON-NLS-1$
                            if (j < to) {
                                context.append('\n');
                            }
                        }
                        entry.addProperty("context", context.toString()); //$NON-NLS-1$
                    }
                    matches.add(entry);
                }
            });
        }

        JsonObject result = new JsonObject();
        result.addProperty("query", query); //$NON-NLS-1$
        result.addProperty("totalMatches", total[0]); //$NON-NLS-1$
        result.addProperty("returned", matches.size()); //$NON-NLS-1$
        result.add("matches", matches); //$NON-NLS-1$
        return result;
    }

    // ------------------------------------------------------------------
    // symbol: перехід до визначення
    // ------------------------------------------------------------------

    /**
     * «Де це визначено» для символа BSL.
     *
     * <p>Спершу пробуємо адресний шлях за власником символа (загальний модуль,
     * або модуль об'єкта метаданих), бо це один файл замість обходу проєкту.
     * Якщо власник не вказаний або файл не знайшовся — обходимо модулі й збираємо
     * всі методи з таким іменем: неоднозначність краще показати, ніж вгадати.
     */
    private static JsonElement resolveSymbol(String symbol, String projectName, int maxMatches) {
        int lastDot = symbol.lastIndexOf('.');
        String owner = lastDot < 0 ? null : symbol.substring(0, lastDot);
        String methodName = lastDot < 0 ? symbol : symbol.substring(lastDot + 1);
        if (methodName.isBlank()) {
            throw new IllegalArgumentException("У symbol немає імені методу: " + symbol); //$NON-NLS-1$
        }

        List<IProject> projects = new ArrayList<>();
        if (projectName != null && !projectName.isBlank()) {
            projects.add(V8Access.resolveEclipseProject(projectName));
        } else {
            V8Access.v8Projects().forEach(p -> projects.add(p.getProject()));
        }

        JsonArray matches = new JsonArray();
        int[] total = {0};
        for (IProject project : projects) {
            for (String path : candidatePaths(owner)) {
                JsonObject match = definitionAt(project, path, methodName);
                if (match != null) {
                    total[0]++;
                    matches.add(match);
                }
            }
        }
        if (matches.isEmpty()) {
            for (IProject project : projects) {
                WorkspaceFiles.walk(project, "bsl", file -> { //$NON-NLS-1$
                    if (total[0] >= maxMatches) {
                        return;
                    }
                    String path = file.getProjectRelativePath().toString();
                    if (owner != null && !path.toLowerCase().contains(owner.toLowerCase())) {
                        return;
                    }
                    JsonObject match = definitionAt(project, path, methodName);
                    if (match != null) {
                        total[0]++;
                        matches.add(match);
                    }
                });
            }
        }

        JsonObject result = new JsonObject();
        result.addProperty("symbol", symbol); //$NON-NLS-1$
        result.addProperty("method", methodName); //$NON-NLS-1$
        if (owner != null) {
            result.addProperty("owner", owner); //$NON-NLS-1$
        }
        result.addProperty("resolved", matches.size() > 0); //$NON-NLS-1$
        result.addProperty("returned", matches.size()); //$NON-NLS-1$
        result.add("definitions", matches); //$NON-NLS-1$
        if (matches.isEmpty()) {
            result.addProperty("hint", "Метод не знайдено. Перевірте ім'я через " //$NON-NLS-1$ //$NON-NLS-2$
                    + "get_module_structure або пошук по тексту (query)."); //$NON-NLS-1$
        }
        return result;
    }

    /** Імовірні шляхи модуля за власником символа. */
    private static List<String> candidatePaths(String owner) {
        List<String> paths = new ArrayList<>();
        if (owner == null || owner.isBlank()) {
            return paths;
        }
        int dot = owner.indexOf('.');
        if (dot < 0) {
            paths.add("src/CommonModules/" + owner + "/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
            return paths;
        }
        // Kind.Ім'я → модуль менеджера, потім модуль об'єкта
        String kind = owner.substring(0, dot);
        String name = owner.substring(dot + 1);
        String folder = "src/" + kind + "s/" + name; //$NON-NLS-1$ //$NON-NLS-2$
        paths.add(folder + "/ManagerModule.bsl"); //$NON-NLS-1$
        paths.add(folder + "/ObjectModule.bsl"); //$NON-NLS-1$
        paths.add(folder + "/RecordSetModule.bsl"); //$NON-NLS-1$
        return paths;
    }

    /** Визначення методу в конкретному модулі; null — модуля немає або методу в ньому немає. */
    private static JsonObject definitionAt(IProject project, String path, String methodName) {
        String source;
        try {
            source = WorkspaceFiles.read(project, path);
        } catch (RuntimeException e) {
            return null; // модуля за таким шляхом немає
        }
        BslOutline outline = BslOutline.parse(source);
        BslOutline.Method method = outline.findMethod(methodName);
        if (method == null) {
            return null;
        }
        String[] lines = source.split("\r?\n", -1); //$NON-NLS-1$
        int start = BslOutline.startLineWithDirectives(method);

        JsonObject entry = new JsonObject();
        entry.addProperty("project", project.getName()); //$NON-NLS-1$
        entry.addProperty("path", path); //$NON-NLS-1$
        entry.addProperty("method", method.name); //$NON-NLS-1$
        entry.addProperty("kind", method.kind); //$NON-NLS-1$
        entry.addProperty("export", method.export); //$NON-NLS-1$
        entry.addProperty("signature", method.signature); //$NON-NLS-1$
        entry.addProperty("startLine", start); //$NON-NLS-1$
        entry.addProperty("endLine", method.endLine); //$NON-NLS-1$
        BslMethodJson.describe(entry, method);
        StringBuilder text = new StringBuilder();
        for (int i = start - 1; i < method.endLine && i < lines.length; i++) {
            text.append(lines[i]);
            if (i < method.endLine - 1) {
                text.append('\n');
            }
        }
        entry.addProperty("source", text.toString()); //$NON-NLS-1$
        return entry;
    }

    private static Pattern compile(String query, boolean regex, boolean caseSensitive) {
        int flags = Pattern.UNICODE_CASE | (caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);
        try {
            return Pattern.compile(regex ? query : Pattern.quote(query), flags);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Некоректний регулярний вираз: " + e.getMessage()); //$NON-NLS-1$
        }
    }
}
