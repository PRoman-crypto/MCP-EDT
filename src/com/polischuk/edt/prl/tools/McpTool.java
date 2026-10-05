/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Один MCP-інструмент: ім'я, опис, JSON Schema параметрів і обробник. */
public interface McpTool {

    String name();

    String description();

    /** JSON Schema об'єкта arguments (type: object). */
    JsonObject inputSchema();

    /** Виконує інструмент; результат серіалізується у відповідь tools/call. */
    JsonElement execute(JsonObject arguments) throws Exception;

    /** Схема без параметрів — для інструментів, що не приймають аргументів. */
    static JsonObject emptyObjectSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object"); //$NON-NLS-1$ //$NON-NLS-2$
        schema.add("properties", new JsonObject()); //$NON-NLS-1$
        return schema;
    }
}
