/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.jobs;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.polischuk.edt.prl.edt.EdtExecution;
import com.polischuk.edt.prl.edt.EdtServices;
import com.polischuk.edt.prl.edt.V8Access;
import com.polischuk.edt.prl.edt.WriteGate;
import com.polischuk.edt.prl.tools.McpTool;

import com.e1c.g5.dt.applications.ApplicationUpdateType;
import com.e1c.g5.dt.applications.ExecutionContext;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

/** Оновлення конфігурації інформаційної бази з проєкту EDT (асинхронна джоба). */
public final class UpdateInfobaseTool implements McpTool {

    @Override
    public String name() {
        return "update_infobase"; //$NON-NLS-1$
    }

    @Override
    public String description() {
        return "Оновлює конфігурацію ІБ застосунку з поточного стану проєкту EDT " //$NON-NLS-1$
                + "(type: incremental — типово, full — повне). Довга операція: повертає jobId, " //$NON-NLS-1$
                + "результат — get_job_status. Потрібне відкрите вікно EDT: якщо в ІБ є зміни чи потрібні " //$NON-NLS-1$
                + "облікові дані, EDT покаже діалог у своєму вікні, і джоба чекатиме відповіді."; //$NON-NLS-1$
    }

    @Override
    public JsonObject inputSchema() {
        return JsonParser.parseString("""
                {"type":"object","properties":{
                  "project":{"type":"string","description":"Ім'я проєкту EDT (необов'язково, якщо проєкт один)"},
                  "application":{"type":"string","description":"Ім'я застосунку; без нього — за замовчуванням"},
                  "type":{"type":"string","enum":["incremental","full"],"default":"incremental"}
                }}""").getAsJsonObject(); //$NON-NLS-1$
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
        WriteGate.check();

        String projectName = arguments.has("project") ? arguments.get("project").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$
        IProject project = V8Access.resolveEclipseProject(projectName);
        IApplicationManager manager = EdtServices.require(IApplicationManager.class);
        IApplication application = RunApplicationTool.resolveApplication(manager, project,
                arguments.has("application") ? arguments.get("application").getAsString() : null); //$NON-NLS-1$ //$NON-NLS-2$
        ApplicationUpdateType type = arguments.has("type") //$NON-NLS-1$
                && "full".equalsIgnoreCase(arguments.get("type").getAsString()) //$NON-NLS-1$ //$NON-NLS-2$
                        ? ApplicationUpdateType.FULL : ApplicationUpdateType.INCREMENTAL;

        // вікно EDT для діалогів (зміни в ІБ, облікові дані): без нього update падає з
        // «Shell is not provided in execution context»; беремо його тут, до фонового потоку
        ExecutionContext context = EdtExecution.context();
        return JobManager.startTask("update_infobase " + application.getName() + " (" + type + ")", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                () -> "Результат оновлення: " //$NON-NLS-1$
                        + manager.update(application, type, context, new NullProgressMonitor()));
    }
}
