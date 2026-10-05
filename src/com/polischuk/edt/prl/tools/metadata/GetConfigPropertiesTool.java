/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.metadata;

import org.eclipse.emf.ecore.EObject;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.polischuk.edt.prl.edt.Emf;
import com.polischuk.edt.prl.edt.V8Access;
import com.polischuk.edt.prl.tools.McpTool;

import com._1c.g5.v8.dt.core.platform.IV8Project;

/** Властивості конфігурації: ім'я, версія, режими, мови тощо. */
public final class GetConfigPropertiesTool implements McpTool {

    @Override
    public String name() {
        return "get_config_properties"; //$NON-NLS-1$
    }

    @Override
    public String description() {
        return "Властивості конфігурації 1С: ім'я, синонім, версія, режим сумісності, мови та інші атрибути. " //$NON-NLS-1$
                + "Параметр project можна не вказувати, якщо 1С-проєкт у workspace один."; //$NON-NLS-1$
    }

    @Override
    public JsonObject inputSchema() {
        return JsonParser.parseString("""
                {"type":"object","properties":{
                  "project":{"type":"string","description":"Ім'я проєкту EDT (необов'язково, якщо проєкт один)"}
                }}""").getAsJsonObject(); //$NON-NLS-1$
    }

    @Override
    public JsonElement execute(JsonObject arguments) {
        String projectName = arguments.has("project") ? arguments.get("project").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$
        IV8Project project = V8Access.resolveProject(projectName);
        EObject configuration = V8Access.configuration(project);
        if (configuration == null) {
            throw new IllegalArgumentException("Проєкт '" + project.getProject().getName() //$NON-NLS-1$
                    + "' не містить конфігурації (" + project.getClass().getSimpleName() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        JsonObject result = new JsonObject();
        result.addProperty("project", project.getProject().getName()); //$NON-NLS-1$
        result.addProperty("projectType", project.getClass().getSimpleName()); //$NON-NLS-1$
        JsonObject synonym = Emf.synonym(configuration);
        if (synonym != null) {
            result.add("synonym", synonym); //$NON-NLS-1$
        }
        Object defaultLanguage = Emf.get(configuration, "defaultLanguage"); //$NON-NLS-1$
        if (defaultLanguage instanceof EObject language) {
            result.addProperty("defaultLanguage", Emf.name(language)); //$NON-NLS-1$
        }
        result.add("properties", Emf.attributes(configuration, false)); //$NON-NLS-1$
        return result;
    }
}
