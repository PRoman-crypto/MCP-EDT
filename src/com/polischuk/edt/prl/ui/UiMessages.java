/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.ui;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.runtime.Platform;

/**
 * Написи UI трьома мовами. Мова обирається за локаллю EDT (Platform.getNL()):
 * uk* — українська, ru* — російська, інакше — англійська.
 */
public final class UiMessages {

    private static final Map<String, String[]> M = new HashMap<>();
    // індекси мов у масивах значень
    private static final int UK = 0;
    private static final int RU = 1;
    private static final int EN = 2;

    /** Ключ налаштування мови: "auto" | "en" | "ru" | "uk". */
    public static final String LANG_PREF_KEY = "uiLanguage"; //$NON-NLS-1$
    private static final String DEFAULT_LANG = "auto"; //$NON-NLS-1$

    private UiMessages() {
    }

    private static int lang() {
        String choice = DEFAULT_LANG;
        try {
            choice = org.eclipse.core.runtime.preferences.InstanceScope.INSTANCE
                    .getNode(com.polischuk.edt.prl.Activator.PLUGIN_ID)
                    .get(LANG_PREF_KEY, DEFAULT_LANG);
        } catch (Throwable ignored) {
            // до старту преференс-сервісу — дефолт
        }
        switch (choice) {
        case "uk": //$NON-NLS-1$
            return UK;
        case "ru": //$NON-NLS-1$
            return RU;
        case "en": //$NON-NLS-1$
            return EN;
        default:
            return detectLanguage();
        }
    }

    static {
        put("status.up", //$NON-NLS-1$
                "MCP:PRL Server працює — {0} (клік — меню)", //$NON-NLS-1$
                "MCP:PRL Server работает — {0} (клик — меню)", //$NON-NLS-1$
                "MCP:PRL Server is running — {0} (click for menu)"); //$NON-NLS-1$
        put("status.down", //$NON-NLS-1$
                "MCP:PRL Server не запущено (клік — меню)", //$NON-NLS-1$
                "MCP:PRL Server не запущен (клик — меню)", //$NON-NLS-1$
                "MCP:PRL Server is not running (click for menu)"); //$NON-NLS-1$
        put("menu.start", "Запустити сервер", "Запустить сервер", "Start server"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        put("menu.restart", "Перезапустити сервер", "Перезапустить сервер", "Restart server"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        put("menu.stop", "Зупинити сервер", "Остановить сервер", "Stop server"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        put("menu.write", "Дозволити інструменти запису", "Разрешить инструменты записи", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "Allow write tools"); //$NON-NLS-1$
        put("menu.copy", "Копіювати адресу сервера", "Копировать адрес сервера", "Copy server address"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        put("prefs.running", "Сервер запущено: {0}", "Сервер запущен: {0}", "Server is running: {0}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        put("prefs.stopped", "Сервер не запущено (див. Error Log)", "Сервер не запущен (см. Error Log)", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "Server is not running (see Error Log)"); //$NON-NLS-1$
        put("prefs.lang", "Мова інтерфейсу плагіна:", "Язык интерфейса плагина:", "Plugin UI language:"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        put("prefs.lang.auto", "Авто (за мовою EDT)", "Авто (по языку EDT)", "Auto (follow EDT)"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        put("prefs.lang.hint", //$NON-NLS-1$
                "Застосовується одразу до індикатора; сторінка налаштувань — після повторного відкриття.", //$NON-NLS-1$
                "Применяется сразу к индикатору; страница настроек — после повторного открытия.", //$NON-NLS-1$
                "Applies immediately to the indicator; this page — after reopening."); //$NON-NLS-1$
        put("prefs.port", "Порт сервера:", "Порт сервера:", "Server port:"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        put("prefs.port.tooltip", //$NON-NLS-1$
                "1024–65535. Застосовується одразу: сервер перезапуститься на новому порту. Не забудьте оновити адресу в конфігурації MCP-клієнтів (.mcp.json).", //$NON-NLS-1$
                "1024–65535. Применяется сразу: сервер перезапустится на новом порту. Не забудьте обновить адрес в конфигурации MCP-клиентов (.mcp.json).", //$NON-NLS-1$
                "1024–65535. Applied immediately: the server restarts on the new port. Remember to update the address in MCP client configs (.mcp.json)."); //$NON-NLS-1$
        put("prefs.port.hint", //$NON-NLS-1$
                "Якщо порт зайнятий — сканується до +10 угору; фактична адреса пишеться у %USERPROFILE%\\.edt-mcp\\instance-*.json. Пріоритет: це поле → -Dmcp.prl.port → 8765.", //$NON-NLS-1$
                "Если порт занят — сканируется до +10 вверх; фактический адрес пишется в %USERPROFILE%\\.edt-mcp\\instance-*.json. Приоритет: это поле → -Dmcp.prl.port → 8765.", //$NON-NLS-1$
                "If the port is busy, up to +10 ports are scanned; the actual address is written to %USERPROFILE%\\.edt-mcp\\instance-*.json. Priority: this field → -Dmcp.prl.port → 8765."); //$NON-NLS-1$
        put("prefs.write", //$NON-NLS-1$
                "Дозволити інструменти запису (write_module_source, edit_metadata, run_vrunner…)", //$NON-NLS-1$
                "Разрешить инструменты записи (write_module_source, edit_metadata, run_vrunner…)", //$NON-NLS-1$
                "Allow write tools (write_module_source, edit_metadata, run_vrunner…)"); //$NON-NLS-1$
        put("prefs.write.hint", //$NON-NLS-1$
                "Прапорець керує файлом {0} і діє одразу, без перезапуску.", //$NON-NLS-1$
                "Флажок управляет файлом {0} и действует сразу, без перезапуска.", //$NON-NLS-1$
                "The checkbox controls file {0} and takes effect immediately, no restart needed."); //$NON-NLS-1$
        put("prefs.err.port", "Порт має бути числом 1024–65535", "Порт должен быть числом 1024–65535", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "Port must be a number between 1024 and 65535"); //$NON-NLS-1$
        put("prefs.err.save", "Не вдалося зберегти порт: {0}", "Не удалось сохранить порт: {0}", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "Failed to save port: {0}"); //$NON-NLS-1$
        put("prefs.err.write", "Не вдалося змінити прапорець запису: {0}", //$NON-NLS-1$ //$NON-NLS-2$
                "Не удалось изменить флажок записи: {0}", "Failed to change write flag: {0}"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void put(String key, String uk, String ru, String en) {
        M.put(key, new String[] {uk, ru, en});
    }

    /**
     * Auto-режим: за локаллю EDT, але лише для мов, якими EDT реально перекладений
     * (ru або en). Для української інтерфейс EDT перекладу не має, тому Auto дає
     * англійську — щоб плагін не «випадав» українською серед англійського EDT.
     */
    private static int detectLanguage() {
        String nl = Platform.getNL();
        if (nl != null && nl.toLowerCase(java.util.Locale.ROOT).startsWith("ru")) { //$NON-NLS-1$
            return RU;
        }
        return EN;
    }

    public static String get(String key) {
        String[] values = M.get(key);
        return values == null ? key : values[lang()];
    }

    public static String get(String key, Object argument) {
        return get(key).replace("{0}", String.valueOf(argument)); //$NON-NLS-1$
    }
}
