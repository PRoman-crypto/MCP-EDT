/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.jobs;

import java.io.File;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.polischuk.edt.prl.edt.WriteGate;
import com.polischuk.edt.prl.tools.McpTool;

/**
 * Запуск Vanessa Runner (vrunner) асинхронною джобою. Потребує встановлених
 * OneScript і vrunner (opm install vanessa-runner). Команди — з allowlist.
 */
public final class RunVrunnerTool implements McpTool {

    private static final Set<String> ALLOWED_COMMANDS = Set.of(
            "vanessa", "xunit", "run", "init-dev", "compile", "decompile", "compileepf", "decompileepf", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
            "syntax-check", "session", "updatedb", "loadcfg", "unloadcfg", "version"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

    @Override
    public String name() {
        return "run_vrunner"; //$NON-NLS-1$
    }

    @Override
    public String description() {
        return "Запускає команду Vanessa Runner (vrunner) асинхронно. Повертає jobId одразу; " //$NON-NLS-1$
                + "прогрес і вивід — get_job_status. Дозволені команди: vanessa, xunit, run, init-dev, " //$NON-NLS-1$
                + "compile(epf), decompile(epf), syntax-check, session, updatedb, loadcfg, unloadcfg, version."; //$NON-NLS-1$
    }

    @Override
    public JsonObject inputSchema() {
        return JsonParser.parseString("""
                {"type":"object","properties":{
                  "command":{"type":"string","description":"Команда vrunner (vanessa, xunit, run…)"},
                  "args":{"type":"array","items":{"type":"string"},"description":"Додаткові аргументи команди"},
                  "workDir":{"type":"string","description":"Робочий каталог (де vanessa-settings/env.json); за замовчуванням — каталог 1С-проєкту в git"},
                  "vrunnerPath":{"type":"string","description":"Повний шлях до vrunner.bat/vrunner.cmd, якщо не в PATH"}
                },"required":["command"]}""").getAsJsonObject(); //$NON-NLS-1$
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
        WriteGate.check(); // vrunner виконує дії над базами 1С — вимагаємо дозволу запису

        String command = arguments.get("command").getAsString().toLowerCase(); //$NON-NLS-1$
        if (!ALLOWED_COMMANDS.contains(command)) {
            throw new IllegalArgumentException("Команда не в allowlist: " + command //$NON-NLS-1$
                    + ". Дозволені: " + String.join(", ", ALLOWED_COMMANDS)); //$NON-NLS-1$ //$NON-NLS-2$
        }

        String vrunner = arguments.has("vrunnerPath") //$NON-NLS-1$
                ? arguments.get("vrunnerPath").getAsString() : findVrunner(); //$NON-NLS-1$
        if (vrunner == null) {
            throw new IllegalStateException("vrunner не знайдено. Встановіть OneScript (oscript.ru) і виконайте " //$NON-NLS-1$
                    + "'opm install vanessa-runner', або передайте параметр vrunnerPath."); //$NON-NLS-1$
        }

        List<String> commandLine = new ArrayList<>(List.of("cmd.exe", "/c", vrunner, command)); //$NON-NLS-1$ //$NON-NLS-2$
        if (arguments.has("args")) { //$NON-NLS-1$
            arguments.get("args").getAsJsonArray().forEach(a -> commandLine.add(a.getAsString())); //$NON-NLS-1$
        }
        File workDir = arguments.has("workDir") ? new File(arguments.get("workDir").getAsString()) : null; //$NON-NLS-1$ //$NON-NLS-2$
        if (workDir != null && !workDir.isDirectory()) {
            throw new IllegalArgumentException("workDir не існує: " + workDir); //$NON-NLS-1$
        }
        // консольний вивід 1С/oscript на Windows зазвичай у CP866
        return JobManager.start(commandLine, workDir, Charset.forName("CP866")); //$NON-NLS-1$
    }

    private static String findVrunner() {
        String path = System.getenv("PATH"); //$NON-NLS-1$
        if (path == null) {
            return null;
        }
        for (String dir : path.split(";")) { //$NON-NLS-1$
            for (String candidate : new String[] {"vrunner.bat", "vrunner.cmd", "vrunner.exe"}) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                File file = new File(dir.strip(), candidate);
                if (file.isFile()) {
                    return file.getAbsolutePath();
                }
            }
        }
        return null;
    }
}
