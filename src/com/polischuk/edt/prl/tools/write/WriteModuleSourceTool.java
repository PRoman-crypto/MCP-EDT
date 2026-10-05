/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.write;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.polischuk.edt.prl.edt.V8Access;
import com.polischuk.edt.prl.edt.WorkspaceFiles;
import com.polischuk.edt.prl.edt.WriteGate;
import com.polischuk.edt.prl.tools.McpTool;
import com.polischuk.edt.prl.tools.code.BslOutline;

/**
 * Запис у модуль BSL. Режими: replaceMethod (замінити один метод), replaceLines
 * (діапазон рядків), append, insertBeforeMethod/insertAfterMethod, replace (модуль
 * цілком — тільки з confirmFullReplace). dryRun показує, що саме зміниться, без запису.
 */
public final class WriteModuleSourceTool implements McpTool {

    private static final double WARN_SHRINK = 0.30;
    private static final double REFUSE_SHRINK = 0.50;
    private static final int PREVIEW_LINES = 40;

    @Override
    public String name() {
        return "write_module_source"; //$NON-NLS-1$
    }

    @Override
    public String description() {
        return "Запис у модуль BSL. Режими: replaceMethod (один метод; НЕ replace!), replaceLines, append, " //$NON-NLS-1$
                + "insertBeforeMethod, insertAfterMethod, replace (модуль цілком, потрібен confirmFullReplace:true). " //$NON-NLS-1$
                + "Спочатку виклик із dryRun:true — перевірте replacedRange і removedLines, потім dryRun:false. " //$NON-NLS-1$
                + "Код методу передавайте повністю, включно з директивами (&НаСервере тощо)."; //$NON-NLS-1$
    }

    @Override
    public JsonObject inputSchema() {
        return JsonParser.parseString("""
                {"type":"object","properties":{
                  "project":{"type":"string","description":"Ім'я проєкту EDT (необов'язково, якщо проєкт один)"},
                  "path":{"type":"string","description":"Шлях модуля відносно проєкту"},
                  "mode":{"type":"string","enum":["replaceMethod","replaceLines","append","insertBeforeMethod","insertAfterMethod","replace"]},
                  "method":{"type":"string","description":"Ім'я методу (для *Method-режимів)"},
                  "startLine":{"type":"integer","description":"Перший рядок діапазону (replaceLines, 1-based)"},
                  "endLine":{"type":"integer","description":"Останній рядок діапазону (включно)"},
                  "code":{"type":"string","description":"Новий код (для replaceMethod — метод цілком із директивами)"},
                  "dryRun":{"type":"boolean","default":false,"description":"true — показати зміни без запису"},
                  "confirmFullReplace":{"type":"boolean","default":false,"description":"Обов'язково true для mode=replace"},
                  "allowLargeRemoval":{"type":"boolean","default":false,"description":"Дозволити скорочення модуля більш ніж наполовину"}
                },"required":["path","mode","code"]}""").getAsJsonObject(); //$NON-NLS-1$
    }

    @Override
    public JsonObject execute(JsonObject arguments) throws Exception {
        WriteGate.check();

        String projectName = arguments.has("project") ? arguments.get("project").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$
        String path = arguments.get("path").getAsString(); //$NON-NLS-1$
        String mode = arguments.get("mode").getAsString(); //$NON-NLS-1$
        String code = arguments.get("code").getAsString(); //$NON-NLS-1$
        boolean dryRun = arguments.has("dryRun") && arguments.get("dryRun").getAsBoolean(); //$NON-NLS-1$ //$NON-NLS-2$

        IProject project = V8Access.resolveEclipseProject(projectName);
        IFile file = WorkspaceFiles.file(project, path);
        String original = WorkspaceFiles.read(file);
        String eol = original.contains("\r\n") ? "\r\n" : "\n"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        List<String> lines = new ArrayList<>(Arrays.asList(original.split("\r?\n", -1))); //$NON-NLS-1$
        // нормалізація EOL коду, включно з самотнім \r у кінці (захист від подвоєння CR при записі)
        List<String> codeLines = Arrays.asList(
                code.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        int oldTotal = lines.size();

        // 1-based inclusive межі заміни; для вставок replaceStart > replaceEnd (порожній діапазон)
        int replaceStart;
        int replaceEnd;
        switch (mode) {
        case "replace": { //$NON-NLS-1$
            if (!arguments.has("confirmFullReplace") || !arguments.get("confirmFullReplace").getAsBoolean()) { //$NON-NLS-1$ //$NON-NLS-2$
                throw new IllegalArgumentException("mode=replace ЗАМІНЮЄ ВЕСЬ МОДУЛЬ (" + oldTotal //$NON-NLS-1$
                        + " рядків буде втрачено). Для заміни одного методу використайте replaceMethod. " //$NON-NLS-1$
                        + "Якщо повна заміна усвідомлена — повторіть із confirmFullReplace:true."); //$NON-NLS-1$
            }
            replaceStart = 1;
            replaceEnd = oldTotal;
            break;
        }
        case "replaceLines": { //$NON-NLS-1$
            replaceStart = requiredInt(arguments, "startLine"); //$NON-NLS-1$
            replaceEnd = requiredInt(arguments, "endLine"); //$NON-NLS-1$
            if (replaceStart < 1 || replaceEnd < replaceStart || replaceEnd > oldTotal) {
                throw new IllegalArgumentException("Некоректний діапазон " + replaceStart + ".." + replaceEnd //$NON-NLS-1$ //$NON-NLS-2$
                        + " (у модулі " + oldTotal + " рядків)"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            break;
        }
        case "replaceMethod": //$NON-NLS-1$
        case "insertBeforeMethod": //$NON-NLS-1$
        case "insertAfterMethod": { //$NON-NLS-1$
            BslOutline.Method method = findMethod(arguments, lines);
            int start = BslOutline.startLineWithDirectives(method);
            if ("replaceMethod".equals(mode)) { //$NON-NLS-1$
                replaceStart = start;
                replaceEnd = method.endLine;
            } else if ("insertBeforeMethod".equals(mode)) { //$NON-NLS-1$
                replaceStart = start;
                replaceEnd = start - 1;
            } else {
                replaceStart = method.endLine + 1;
                replaceEnd = method.endLine;
            }
            break;
        }
        case "append": { //$NON-NLS-1$
            replaceStart = oldTotal + 1;
            replaceEnd = oldTotal;
            break;
        }
        default:
            throw new IllegalArgumentException("Невідомий mode: " + mode); //$NON-NLS-1$
        }

        List<String> updated = new ArrayList<>(lines.subList(0, replaceStart - 1));
        updated.addAll(codeLines);
        updated.addAll(lines.subList(replaceEnd, oldTotal));
        int newTotal = updated.size();
        int removedLines = Math.max(0, oldTotal - newTotal);
        double shrink = oldTotal > 0 ? (double) removedLines / oldTotal : 0;

        JsonObject result = new JsonObject();
        result.addProperty("path", path); //$NON-NLS-1$
        result.addProperty("mode", mode); //$NON-NLS-1$
        result.addProperty("oldTotalLines", oldTotal); //$NON-NLS-1$
        result.addProperty("newTotalLines", newTotal); //$NON-NLS-1$
        result.addProperty("removedLines", removedLines); //$NON-NLS-1$
        if (replaceEnd >= replaceStart) {
            result.addProperty("replacedRange", replaceStart + "-" + replaceEnd); //$NON-NLS-1$ //$NON-NLS-2$
            result.addProperty("replacedLines", replaceEnd - replaceStart + 1); //$NON-NLS-1$
        } else {
            result.addProperty("insertAtLine", replaceStart); //$NON-NLS-1$
        }

        boolean replacing = "replace".equals(mode) || "replaceLines".equals(mode) || "replaceMethod".equals(mode); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (replacing && shrink > REFUSE_SHRINK
                && !(arguments.has("allowLargeRemoval") && arguments.get("allowLargeRemoval").getAsBoolean())) { //$NON-NLS-1$ //$NON-NLS-2$
            throw new IllegalStateException("СТОП: модуль скоротився б на " + Math.round(shrink * 100) //$NON-NLS-1$
                    + "% (" + removedLines + " рядків). Ймовірно, обраний не той режим (replace замість replaceMethod?). " //$NON-NLS-1$ //$NON-NLS-2$
                    + "Якщо скорочення усвідомлене — повторіть із allowLargeRemoval:true."); //$NON-NLS-1$
        }
        if (replacing && shrink > WARN_SHRINK) {
            result.addProperty("warning", "Модуль скорочується на " + Math.round(shrink * 100) //$NON-NLS-1$ //$NON-NLS-2$
                    + "% — перевірте replacedRange у dryRun."); //$NON-NLS-1$
        }

        if (dryRun) {
            result.addProperty("dryRun", true); //$NON-NLS-1$
            result.addProperty("written", false); //$NON-NLS-1$
            if (replaceEnd >= replaceStart) {
                result.addProperty("replacedPreview", preview(lines, replaceStart, replaceEnd)); //$NON-NLS-1$
            }
            return result;
        }

        String key = ModuleSnapshots.key(project.getName(), path);
        ModuleSnapshots.baselineIfAbsent(key, original);

        String newText = String.join(eol, updated);
        Charset charset = Charset.forName(file.getCharset(true));
        byte[] body = newText.getBytes(charset);
        if (WorkspaceFiles.hasUtf8Bom(file)) {
            byte[] withBom = new byte[body.length + 3];
            withBom[0] = (byte) 0xEF;
            withBom[1] = (byte) 0xBB;
            withBom[2] = (byte) 0xBF;
            System.arraycopy(body, 0, withBom, 3, body.length);
            body = withBom;
        }
        file.setContents(new ByteArrayInputStream(body), IResource.KEEP_HISTORY, null);
        result.addProperty("written", true); //$NON-NLS-1$
        result.add("validation", awaitValidation(file)); //$NON-NLS-1$
        return result;
    }

    private static BslOutline.Method findMethod(JsonObject arguments, List<String> lines) {
        if (!arguments.has("method")) { //$NON-NLS-1$
            throw new IllegalArgumentException("Для цього режиму обов'язковий параметр method"); //$NON-NLS-1$
        }
        String methodName = arguments.get("method").getAsString(); //$NON-NLS-1$
        BslOutline outline = BslOutline.parse(String.join("\n", lines)); //$NON-NLS-1$
        BslOutline.Method method = outline.findMethod(methodName);
        if (method == null) {
            throw new IllegalArgumentException("Метод не знайдено: " + methodName //$NON-NLS-1$
                    + ". Перегляньте методи через get_module_structure."); //$NON-NLS-1$
        }
        return method;
    }

    private static String preview(List<String> lines, int start, int end) {
        StringBuilder preview = new StringBuilder();
        int shown = 0;
        for (int i = start; i <= end && shown < PREVIEW_LINES; i++, shown++) {
            preview.append(i).append(": ").append(lines.get(i - 1)).append('\n'); //$NON-NLS-1$
        }
        if (end - start + 1 > PREVIEW_LINES) {
            preview.append("… ще ").append(end - start + 1 - PREVIEW_LINES).append(" рядків"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return preview.toString();
    }

    /** Коротке очікування асинхронної валідації EDT і збір маркерів по файлу. */
    private static JsonObject awaitValidation(IFile file) {
        JsonObject validation = new JsonObject();
        try {
            Thread.sleep(3000);
            IMarker[] markers = file.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_ZERO);
            int errors = 0;
            JsonArray problems = new JsonArray();
            for (IMarker marker : markers) {
                boolean isError = marker.getAttribute(IMarker.SEVERITY,
                        IMarker.SEVERITY_INFO) == IMarker.SEVERITY_ERROR;
                if (!isError) {
                    continue;
                }
                errors++;
                if (problems.size() < 10) {
                    JsonObject problem = new JsonObject();
                    int line = marker.getAttribute(IMarker.LINE_NUMBER, -1);
                    if (line > 0) {
                        problem.addProperty("line", line); //$NON-NLS-1$
                    }
                    problem.addProperty("message", marker.getAttribute(IMarker.MESSAGE, "")); //$NON-NLS-1$ //$NON-NLS-2$
                    problems.add(problem);
                }
            }
            validation.addProperty("errorCount", errors); //$NON-NLS-1$
            validation.add("errors", problems); //$NON-NLS-1$
            validation.addProperty("note", //$NON-NLS-1$
                    "Валідація EDT асинхронна — фінальний стан перевіряйте get_validation_errors із pathFilter."); //$NON-NLS-1$
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (CoreException e) {
            validation.addProperty("error", e.getMessage()); //$NON-NLS-1$
        }
        return validation;
    }

    private static int requiredInt(JsonObject arguments, String name) {
        if (!arguments.has(name)) {
            throw new IllegalArgumentException("Обов'язковий параметр відсутній: " + name); //$NON-NLS-1$
        }
        return arguments.get(name).getAsInt();
    }
}
