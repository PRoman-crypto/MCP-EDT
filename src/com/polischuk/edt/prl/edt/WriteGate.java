/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.edt;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Дозвіл на запис. Вмикається файлом-прапорцем поза MCP-протоколом — щоб AI-клієнт
 * не міг увімкнути запис самостійно: %USERPROFILE%\.edt-mcp\write-enabled.
 */
public final class WriteGate {

    public static final Path FLAG = Path.of(System.getProperty("user.home"), ".edt-mcp", "write-enabled"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private WriteGate() {
    }

    public static boolean isEnabled() {
        return Files.exists(FLAG);
    }

    public static void check() {
        if (!isEnabled()) {
            throw new IllegalStateException("Інструменти запису вимкнені. Щоб дозволити запис, створіть файл " //$NON-NLS-1$
                    + FLAG + " (наприклад: New-Item -ItemType File -Force '" + FLAG + "') і повторіть виклик. " //$NON-NLS-1$ //$NON-NLS-2$
                    + "Видалення файлу знову вимикає запис."); //$NON-NLS-1$
        }
    }
}
