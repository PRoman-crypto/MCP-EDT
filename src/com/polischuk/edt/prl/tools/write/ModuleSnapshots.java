/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.write;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Базлайни модулів у межах сесії EDT: перший запис у модуль зберігає його
 * вихідний текст, diff_module порівнює поточний стан із базлайном.
 */
public final class ModuleSnapshots {

    private static final Map<String, String> SNAPSHOTS = new ConcurrentHashMap<>();

    private ModuleSnapshots() {
    }

    public static String key(String project, String path) {
        return project + "|" + path.replace('\\', '/'); //$NON-NLS-1$
    }

    public static void baselineIfAbsent(String key, String original) {
        SNAPSHOTS.putIfAbsent(key, original);
    }

    public static String baseline(String key) {
        return SNAPSHOTS.get(key);
    }
}
