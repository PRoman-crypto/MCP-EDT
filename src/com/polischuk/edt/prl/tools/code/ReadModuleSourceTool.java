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

/** Читання тексту модуля BSL (цілком або діапазон рядків). */
public final class ReadModuleSourceTool implements McpTool {

    @Override
    public String name() {
        return "read_module_source"; //$NON-NLS-1$
    }

    @Override
    public String description() {
        return "Текст модуля BSL за шляхом із list_modules. startLine/endLine (1-based, включно) — діапазон; " //$NON-NLS-1$
                + "без них — модуль цілком. Для великих модулів читайте метод через read_method_source."; //$NON-NLS-1$
    }

    @Override
    public JsonObject inputSchema() {
        return JsonParser.parseString("""
                {"type":"object","properties":{
                  "project":{"type":"string","description":"Ім'я проєкту EDT (необов'язково, якщо проєкт один)"},
                  "path":{"type":"string","description":"Шлях модуля відносно проєкту, напр. src/CommonModules/X/Module.bsl"},
                  "startLine":{"type":"integer","description":"Перший рядок (1-based)"},
                  "endLine":{"type":"integer","description":"Останній рядок (включно)"}
                },"required":["path"]}""").getAsJsonObject(); //$NON-NLS-1$
    }

    @Override
    public JsonElement execute(JsonObject arguments) {
        String projectName = arguments.has("project") ? arguments.get("project").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$
        String path = arguments.get("path").getAsString(); //$NON-NLS-1$
        IProject project = V8Access.resolveEclipseProject(projectName);
        String source = WorkspaceFiles.read(project, path);
        String[] lines = source.split("\r?\n", -1); //$NON-NLS-1$

        int start = arguments.has("startLine") ? Math.max(1, arguments.get("startLine").getAsInt()) : 1; //$NON-NLS-1$ //$NON-NLS-2$
        int end = arguments.has("endLine") //$NON-NLS-1$
                ? Math.min(lines.length, arguments.get("endLine").getAsInt()) : lines.length; //$NON-NLS-1$

        JsonObject result = new JsonObject();
        result.addProperty("path", path); //$NON-NLS-1$
        result.addProperty("totalLines", lines.length); //$NON-NLS-1$
        result.addProperty("startLine", start); //$NON-NLS-1$
        result.addProperty("endLine", end); //$NON-NLS-1$
        StringBuilder slice = new StringBuilder();
        for (int i = start - 1; i < end; i++) {
            slice.append(lines[i]);
            if (i < end - 1) {
                slice.append('\n');
            }
        }
        result.addProperty("source", slice.toString()); //$NON-NLS-1$
        return result;
    }
}
