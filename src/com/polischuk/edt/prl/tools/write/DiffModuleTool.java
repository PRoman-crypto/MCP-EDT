/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.write;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.resources.IProject;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.polischuk.edt.prl.edt.V8Access;
import com.polischuk.edt.prl.edt.WorkspaceFiles;
import com.polischuk.edt.prl.tools.McpTool;
import com.polischuk.edt.prl.tools.code.BslOutline;

/**
 * Diff модуля BSL проти базлайна.
 *
 * <p>Два джерела базлайна:
 * <ul>
 * <li>{@code against=git} (типово) — версія файлу в git-ref (типово HEAD). Відповідає на
 * питання «що змінилось у модулі відносно коміту», незалежно від того, хто правив — MCP,
 * редактор EDT чи інший інструмент;</li>
 * <li>{@code against=session} — стан на початок правок {@code write_module_source} у цій
 * сесії EDT. Вужче, зате показує рівно те, що зробив агент.</li>
 * </ul>
 *
 * <p>Режими: {@code summary} — які методи додано/змінено/видалено; {@code unified} —
 * повний diff; {@code methods} — diff кожного зміненого методу окремо.
 */
public final class DiffModuleTool implements McpTool {

    private static final int MAX_DIFF_LINES = 400;
    private static final int CONTEXT = 3;

    @Override
    public String name() {
        return "diff_module"; //$NON-NLS-1$
    }

    @Override
    public String description() {
        return "Diff модуля BSL. against=git (типово) — проти версії у git-ref (ref, типово HEAD); " //$NON-NLS-1$
                + "against=session — проти стану до правок write_module_source у цій сесії EDT. " //$NON-NLS-1$
                + "mode: summary (які методи додано/змінено/видалено) | unified (повний diff) | " //$NON-NLS-1$
                + "methods (diff кожного зміненого методу окремо)."; //$NON-NLS-1$
    }

    @Override
    public JsonObject inputSchema() {
        return JsonParser.parseString("""
                {"type":"object","properties":{
                  "project":{"type":"string","description":"Ім'я проєкту EDT (необов'язково, якщо проєкт один)"},
                  "path":{"type":"string","description":"Шлях модуля відносно проєкту"},
                  "against":{"type":"string","enum":["git","session"],"default":"git","description":"Джерело базлайна"},
                  "ref":{"type":"string","default":"HEAD","description":"git-ref для against=git: HEAD, гілка, тег, хеш коміту"},
                  "mode":{"type":"string","enum":["summary","unified","methods"],"default":"summary"}
                },"required":["path"]}""").getAsJsonObject(); //$NON-NLS-1$
    }

    @Override
    public JsonElement execute(JsonObject arguments) {
        String projectName = arguments.has("project") ? arguments.get("project").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$
        String path = arguments.get("path").getAsString(); //$NON-NLS-1$
        String against = arguments.has("against") //$NON-NLS-1$
                ? arguments.get("against").getAsString().toLowerCase(Locale.ROOT) : "git"; //$NON-NLS-1$ //$NON-NLS-2$
        String ref = arguments.has("ref") && !arguments.get("ref").getAsString().isBlank() //$NON-NLS-1$ //$NON-NLS-2$
                ? arguments.get("ref").getAsString() : "HEAD"; //$NON-NLS-1$ //$NON-NLS-2$
        String mode = arguments.has("mode") //$NON-NLS-1$
                ? arguments.get("mode").getAsString().toLowerCase(Locale.ROOT) : "summary"; //$NON-NLS-1$ //$NON-NLS-2$

        IProject project = V8Access.resolveEclipseProject(projectName);
        JsonObject result = new JsonObject();
        result.addProperty("project", project.getName()); //$NON-NLS-1$
        result.addProperty("path", path); //$NON-NLS-1$
        result.addProperty("against", against); //$NON-NLS-1$
        result.addProperty("mode", mode); //$NON-NLS-1$

        String baseline;
        if ("session".equals(against)) { //$NON-NLS-1$
            baseline = ModuleSnapshots.baseline(ModuleSnapshots.key(project.getName(), path));
            if (baseline == null) {
                result.addProperty("hasBaseline", false); //$NON-NLS-1$
                result.addProperty("message", "Модуль не змінювався через write_module_source " //$NON-NLS-1$ //$NON-NLS-2$
                        + "у поточній сесії EDT. Для порівняння з комітом викличте against=git."); //$NON-NLS-1$
                return result;
            }
        } else {
            GitBaseline.Result git = GitBaseline.read(project, path, ref);
            result.addProperty("ref", ref); //$NON-NLS-1$
            result.addProperty("repositoryPath", git.repositoryPath()); //$NON-NLS-1$
            String describe = GitBaseline.describeRef(project, ref);
            if (describe != null && !describe.isEmpty()) {
                result.addProperty("refCommit", describe); //$NON-NLS-1$
            }
            if (git.absentInRef()) {
                result.addProperty("hasBaseline", false); //$NON-NLS-1$
                result.addProperty("isNewFile", true); //$NON-NLS-1$
                result.addProperty("message", "Файлу немає в " + ref //$NON-NLS-1$
                        + " — модуль новий відносно цього ref, увесь вміст доданий."); //$NON-NLS-1$
                return result;
            }
            baseline = git.content();
        }
        result.addProperty("hasBaseline", true); //$NON-NLS-1$

        String current = WorkspaceFiles.read(project, path);
        String[] oldLines = baseline.split("\r?\n", -1); //$NON-NLS-1$
        String[] newLines = current.split("\r?\n", -1); //$NON-NLS-1$
        LineDiff diff = LineDiff.between(oldLines, newLines);

        result.addProperty("addedLines", diff.added()); //$NON-NLS-1$
        result.addProperty("removedLines", diff.removed()); //$NON-NLS-1$
        result.addProperty("algorithm", diff.algorithm()); //$NON-NLS-1$
        if (diff.isEmpty()) {
            result.addProperty("message", "Змін немає — поточний стан збігається з базлайном."); //$NON-NLS-1$ //$NON-NLS-2$
            return result;
        }

        switch (mode) {
        case "unified" -> result.addProperty("diff", diff.unified(CONTEXT, MAX_DIFF_LINES)); //$NON-NLS-1$ //$NON-NLS-2$
        case "methods" -> result.add("methods", methodDiffs(baseline, current)); //$NON-NLS-1$ //$NON-NLS-2$
        default -> addSummary(result, baseline, current);
        }
        return result;
    }

    /** Зведення по методах: що додано, змінено, видалено — без тіл. */
    private static void addSummary(JsonObject result, String baseline, String current) {
        Map<String, String> oldBodies = methodBodies(baseline);
        Map<String, String> newBodies = methodBodies(current);
        Map<String, BslOutline.Method> newMethods = methodIndex(current);
        Map<String, BslOutline.Method> oldMethods = methodIndex(baseline);

        JsonArray added = new JsonArray();
        JsonArray changed = new JsonArray();
        JsonArray removed = new JsonArray();
        for (Map.Entry<String, String> entry : newBodies.entrySet()) {
            String key = entry.getKey();
            if (!oldBodies.containsKey(key)) {
                added.add(methodJson(newMethods.get(key)));
            } else if (!oldBodies.get(key).equals(entry.getValue())) {
                changed.add(methodJson(newMethods.get(key)));
            }
        }
        for (String key : oldBodies.keySet()) {
            if (!newBodies.containsKey(key)) {
                removed.add(methodJson(oldMethods.get(key)));
            }
        }
        result.add("addedMethods", added); //$NON-NLS-1$
        result.add("changedMethods", changed); //$NON-NLS-1$
        result.add("removedMethods", removed); //$NON-NLS-1$
        if (added.size() == 0 && changed.size() == 0 && removed.size() == 0) {
            // рядки змінились поза межами методів: шапка, директиви, коментарі
            result.addProperty("note", "Змін у межах методів немає — правки поза тілами " //$NON-NLS-1$ //$NON-NLS-2$
                    + "(шапка модуля, коментарі, порожні рядки). Деталі — mode=unified."); //$NON-NLS-1$
        }
    }

    /** Diff кожного зміненого методу окремо — зручно, коли правок кілька в різних місцях. */
    private static JsonArray methodDiffs(String baseline, String current) {
        Map<String, String> oldBodies = methodBodies(baseline);
        Map<String, String> newBodies = methodBodies(current);
        Map<String, BslOutline.Method> newMethods = methodIndex(current);
        Map<String, BslOutline.Method> oldMethods = methodIndex(baseline);

        JsonArray result = new JsonArray();
        for (Map.Entry<String, String> entry : newBodies.entrySet()) {
            String key = entry.getKey();
            String oldBody = oldBodies.get(key);
            if (oldBody != null && oldBody.equals(entry.getValue())) {
                continue;
            }
            JsonObject item = methodJson(newMethods.get(key));
            item.addProperty("change", oldBody == null ? "added" : "changed"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            LineDiff methodDiff = LineDiff.between(
                    oldBody == null ? new String[0] : oldBody.split("\r?\n", -1), //$NON-NLS-1$
                    entry.getValue().split("\r?\n", -1)); //$NON-NLS-1$
            item.addProperty("addedLines", methodDiff.added()); //$NON-NLS-1$
            item.addProperty("removedLines", methodDiff.removed()); //$NON-NLS-1$
            item.addProperty("diff", methodDiff.unified(CONTEXT, MAX_DIFF_LINES)); //$NON-NLS-1$
            result.add(item);
        }
        for (Map.Entry<String, String> entry : oldBodies.entrySet()) {
            if (newBodies.containsKey(entry.getKey())) {
                continue;
            }
            JsonObject item = methodJson(oldMethods.get(entry.getKey()));
            item.addProperty("change", "removed"); //$NON-NLS-1$ //$NON-NLS-2$
            item.addProperty("removedLines", entry.getValue().split("\r?\n", -1).length); //$NON-NLS-1$ //$NON-NLS-2$
            result.add(item);
        }
        return result;
    }

    private static JsonObject methodJson(BslOutline.Method method) {
        JsonObject item = new JsonObject();
        if (method == null) {
            return item;
        }
        item.addProperty("name", method.name); //$NON-NLS-1$
        item.addProperty("kind", method.kind); //$NON-NLS-1$
        item.addProperty("export", method.export); //$NON-NLS-1$
        item.addProperty("startLine", BslOutline.startLineWithDirectives(method)); //$NON-NLS-1$
        item.addProperty("endLine", method.endLine); //$NON-NLS-1$
        if (method.region != null) {
            item.addProperty("region", method.region); //$NON-NLS-1$
        }
        return item;
    }

    /** Тіла методів за іменем (без регістру) — для порівняння «змінився / ні». */
    private static Map<String, String> methodBodies(String source) {
        String[] lines = source.split("\r?\n", -1); //$NON-NLS-1$
        Map<String, String> bodies = new LinkedHashMap<>();
        for (BslOutline.Method method : BslOutline.parse(source).methods) {
            List<String> body = new ArrayList<>();
            int start = BslOutline.startLineWithDirectives(method);
            for (int i = start - 1; i < method.endLine && i < lines.length; i++) {
                body.add(lines[i]);
            }
            bodies.put(method.name.toLowerCase(Locale.ROOT), String.join("\n", body)); //$NON-NLS-1$
        }
        return bodies;
    }

    private static Map<String, BslOutline.Method> methodIndex(String source) {
        Map<String, BslOutline.Method> index = new LinkedHashMap<>();
        for (BslOutline.Method method : BslOutline.parse(source).methods) {
            index.put(method.name.toLowerCase(Locale.ROOT), method);
        }
        return index;
    }
}
