/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.code;

import org.eclipse.core.resources.IProject;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.polischuk.edt.prl.edt.V8Access;
import com.polischuk.edt.prl.edt.WorkspaceFiles;
import com.polischuk.edt.prl.tools.McpTool;

/** Текст одного методу модуля BSL (з директивами компіляції над ним). */
public final class ReadMethodSourceTool implements McpTool {

    @Override
    public String name() {
        return "read_method_source"; //$NON-NLS-1$
    }

    @Override
    public String description() {
        return "Текст одного методу модуля BSL за ім'ям (з директивами &НаСервере тощо). " //$NON-NLS-1$
                + "Повертає також межі рядків методу — потрібні для подальшої заміни."; //$NON-NLS-1$
    }

    @Override
    public JsonObject inputSchema() {
        return JsonParser.parseString("""
                {"type":"object","properties":{
                  "project":{"type":"string","description":"Ім'я проєкту EDT (необов'язково, якщо проєкт один)"},
                  "path":{"type":"string","description":"Шлях модуля відносно проєкту"},
                  "method":{"type":"string","description":"Ім'я процедури або функції"}
                },"required":["path","method"]}""").getAsJsonObject(); //$NON-NLS-1$
    }

    @Override
    public JsonElement execute(JsonObject arguments) {
        String projectName = arguments.has("project") ? arguments.get("project").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$
        String path = arguments.get("path").getAsString(); //$NON-NLS-1$
        String methodName = arguments.get("method").getAsString(); //$NON-NLS-1$

        IProject project = V8Access.resolveEclipseProject(projectName);
        String source = WorkspaceFiles.read(project, path);
        BslOutline outline = BslOutline.parse(source);
        BslOutline.Method method = outline.findMethod(methodName);
        if (method == null) {
            throw new IllegalArgumentException("Метод не знайдено: " + methodName //$NON-NLS-1$
                    + ". Перегляньте методи через get_module_structure."); //$NON-NLS-1$
        }

        String[] lines = source.split("\r?\n", -1); //$NON-NLS-1$
        int start = BslOutline.startLineWithDirectives(method);
        StringBuilder text = new StringBuilder();
        for (int i = start - 1; i < method.endLine; i++) {
            text.append(lines[i]);
            if (i < method.endLine - 1) {
                text.append('\n');
            }
        }

        JsonObject result = new JsonObject();
        result.addProperty("path", path); //$NON-NLS-1$
        result.addProperty("method", method.name); //$NON-NLS-1$
        result.addProperty("kind", method.kind); //$NON-NLS-1$
        result.addProperty("export", method.export); //$NON-NLS-1$
        result.addProperty("startLine", start); //$NON-NLS-1$
        result.addProperty("endLine", method.endLine); //$NON-NLS-1$
        result.addProperty("signature", method.signature); //$NON-NLS-1$
        BslMethodJson.describe(result, method);
        result.addProperty("source", text.toString()); //$NON-NLS-1$
        return result;
    }
}
