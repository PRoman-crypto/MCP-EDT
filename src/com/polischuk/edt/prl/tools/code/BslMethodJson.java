/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.code;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Спільна серіалізація «розібраної» частини методу: параметри, doc-коментар, область.
 *
 * <p>Один код для get_module_structure, read_method_source і code_search(symbol) — щоб
 * агент бачив однаковий набір полів, звідки б не прийшов до методу.
 */
public final class BslMethodJson {

    private BslMethodJson() {
    }

    /** Додає до entry: parameters[], docComment, region — лише те, що фактично є. */
    public static void describe(JsonObject entry, BslOutline.Method method) {
        if (!method.parameters.isEmpty()) {
            JsonArray parameters = new JsonArray();
            for (BslOutline.Parameter parameter : method.parameters) {
                JsonObject item = new JsonObject();
                item.addProperty("name", parameter.name); //$NON-NLS-1$
                if (parameter.byValue) {
                    item.addProperty("byValue", true); //$NON-NLS-1$
                }
                if (parameter.defaultValue != null) {
                    item.addProperty("defaultValue", parameter.defaultValue); //$NON-NLS-1$
                }
                if (!parameter.types.isEmpty()) {
                    JsonArray types = new JsonArray();
                    parameter.types.forEach(types::add);
                    item.add("types", types); //$NON-NLS-1$
                }
                if (parameter.description != null) {
                    item.addProperty("description", parameter.description); //$NON-NLS-1$
                }
                parameters.add(item);
            }
            entry.add("parameters", parameters); //$NON-NLS-1$
        }
        if (method.docComment != null) {
            entry.addProperty("docComment", method.docComment); //$NON-NLS-1$
        }
        if (method.region != null) {
            entry.addProperty("region", method.region); //$NON-NLS-1$
        }
    }
}
