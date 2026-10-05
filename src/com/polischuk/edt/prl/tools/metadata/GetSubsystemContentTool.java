/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.metadata;

import java.util.List;

import org.eclipse.emf.ecore.EObject;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.polischuk.edt.prl.edt.Emf;
import com.polischuk.edt.prl.edt.MetadataIndex;
import com.polischuk.edt.prl.edt.V8Access;
import com.polischuk.edt.prl.tools.McpTool;

/** Склад підсистеми: об'єкти, дочірні підсистеми. Шлях через крапку: Продажи.Отчеты. */
public final class GetSubsystemContentTool implements McpTool {

    @Override
    public String name() {
        return "get_subsystem_content"; //$NON-NLS-1$
    }

    @Override
    public String description() {
        return "Склад підсистеми конфігурації: об'єкти метаданих і дочірні підсистеми. " //$NON-NLS-1$
                + "name — ім'я підсистеми; вкладена — через крапку (Продажи.Отчеты). " //$NON-NLS-1$
                + "Список верхніх підсистем — list_metadata_objects kind=Subsystem."; //$NON-NLS-1$
    }

    @Override
    public JsonObject inputSchema() {
        return JsonParser.parseString("""
                {"type":"object","properties":{
                  "project":{"type":"string","description":"Ім'я проєкту EDT (необов'язково, якщо проєкт один)"},
                  "name":{"type":"string","description":"Ім'я підсистеми, вкладена — через крапку"}
                },"required":["name"]}""").getAsJsonObject(); //$NON-NLS-1$
    }

    @Override
    public JsonElement execute(JsonObject arguments) {
        String projectName = arguments.has("project") ? arguments.get("project").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$
        String path = arguments.get("name").getAsString(); //$NON-NLS-1$

        EObject configuration = V8Access.requireConfiguration(projectName);
        String[] segments = path.split("\\."); //$NON-NLS-1$
        EObject subsystem = MetadataIndex.findObject(configuration, "Subsystem", segments[0]); //$NON-NLS-1$
        for (int i = 1; i < segments.length; i++) {
            subsystem = findChild(subsystem, segments[i], path);
        }

        JsonObject result = new JsonObject();
        result.addProperty("subsystem", path); //$NON-NLS-1$
        JsonObject synonym = Emf.synonym(subsystem);
        if (synonym != null) {
            result.add("synonym", synonym); //$NON-NLS-1$
        }
        result.addProperty("includeInCommandInterface", //$NON-NLS-1$
                String.valueOf(Emf.get(subsystem, "includeInCommandInterface"))); //$NON-NLS-1$

        JsonArray content = new JsonArray();
        if (Emf.get(subsystem, "content") instanceof List<?> objects) { //$NON-NLS-1$
            for (Object item : objects) {
                if (item instanceof EObject object) {
                    content.add(object.eClass().getName() + "." + Emf.name(object)); //$NON-NLS-1$
                }
            }
        }
        result.addProperty("objectCount", content.size()); //$NON-NLS-1$
        result.add("content", content); //$NON-NLS-1$

        JsonArray children = new JsonArray();
        if (Emf.get(subsystem, "subsystems") instanceof List<?> subsystems) { //$NON-NLS-1$
            for (Object item : subsystems) {
                if (item instanceof EObject child) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("name", Emf.name(child)); //$NON-NLS-1$
                    JsonObject childSynonym = Emf.synonym(child);
                    if (childSynonym != null) {
                        entry.add("synonym", childSynonym); //$NON-NLS-1$
                    }
                    children.add(entry);
                }
            }
        }
        if (children.size() > 0) {
            result.add("childSubsystems", children); //$NON-NLS-1$
        }
        return result;
    }

    private static EObject findChild(EObject subsystem, String childName, String fullPath) {
        if (Emf.get(subsystem, "subsystems") instanceof List<?> children) { //$NON-NLS-1$
            for (Object item : children) {
                if (item instanceof EObject child && childName.equalsIgnoreCase(Emf.name(child))) {
                    return child;
                }
            }
        }
        throw new IllegalArgumentException("Підсистему не знайдено: " + childName + " (шлях " + fullPath + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
