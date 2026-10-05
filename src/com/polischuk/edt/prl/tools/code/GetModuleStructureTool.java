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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.polischuk.edt.prl.edt.V8Access;
import com.polischuk.edt.prl.edt.WorkspaceFiles;
import com.polischuk.edt.prl.tools.McpTool;

/** Структура модуля BSL: області, методи (з межами рядків), змінні модуля. */
public final class GetModuleStructureTool implements McpTool {

    @Override
    public String name() {
        return "get_module_structure"; //$NON-NLS-1$
    }

    @Override
    public String description() {
        return "Карта модуля BSL: методи (ім'я, вид, Експорт, директиви, рядки від-до), області, змінні. " //$NON-NLS-1$
                + "Використовуйте перед читанням: далі read_method_source або read_module_source із діапазоном."; //$NON-NLS-1$
    }

    @Override
    public JsonObject inputSchema() {
        return JsonParser.parseString("""
                {"type":"object","properties":{
                  "project":{"type":"string","description":"Ім'я проєкту EDT (необов'язково, якщо проєкт один)"},
                  "path":{"type":"string","description":"Шлях модуля відносно проєкту"},
                  "nameFilter":{"type":"string","description":"Підрядок імені методу (без регістру)"}
                },"required":["path"]}""").getAsJsonObject(); //$NON-NLS-1$
    }

    @Override
    public JsonElement execute(JsonObject arguments) {
        String projectName = arguments.has("project") ? arguments.get("project").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$
        String path = arguments.get("path").getAsString(); //$NON-NLS-1$
        String nameFilter = arguments.has("nameFilter") //$NON-NLS-1$
                ? arguments.get("nameFilter").getAsString().toLowerCase() : null; //$NON-NLS-1$

        IProject project = V8Access.resolveEclipseProject(projectName);
        BslOutline outline = BslOutline.parse(WorkspaceFiles.read(project, path));

        JsonObject result = new JsonObject();
        result.addProperty("path", path); //$NON-NLS-1$
        result.addProperty("totalLines", outline.totalLines); //$NON-NLS-1$

        if (!outline.moduleVariables.isEmpty()) {
            JsonArray variables = new JsonArray();
            outline.moduleVariables.forEach(variables::add);
            result.add("moduleVariables", variables); //$NON-NLS-1$
        }

        JsonArray regions = new JsonArray();
        for (BslOutline.Region region : outline.regions) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", region.name); //$NON-NLS-1$
            entry.addProperty("level", region.level); //$NON-NLS-1$
            entry.addProperty("startLine", region.startLine); //$NON-NLS-1$
            entry.addProperty("endLine", region.endLine); //$NON-NLS-1$
            regions.add(entry);
        }
        if (regions.size() > 0) {
            result.add("regions", regions); //$NON-NLS-1$
        }

        JsonArray methods = new JsonArray();
        for (BslOutline.Method method : outline.methods) {
            if (nameFilter != null && !method.name.toLowerCase().contains(nameFilter)) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("name", method.name); //$NON-NLS-1$
            entry.addProperty("kind", method.kind); //$NON-NLS-1$
            entry.addProperty("export", method.export); //$NON-NLS-1$
            if (!method.directives.isEmpty()) {
                JsonArray directives = new JsonArray();
                method.directives.forEach(directives::add);
                entry.add("directives", directives); //$NON-NLS-1$
            }
            entry.addProperty("signature", method.signature); //$NON-NLS-1$
            entry.addProperty("startLine", BslOutline.startLineWithDirectives(method)); //$NON-NLS-1$
            entry.addProperty("endLine", method.endLine); //$NON-NLS-1$
            BslMethodJson.describe(entry, method);
            methods.add(entry);
        }
        result.addProperty("methodCount", methods.size()); //$NON-NLS-1$
        result.add("methods", methods); //$NON-NLS-1$
        return result;
    }
}
