/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.jobs;

import java.nio.charset.Charset;
import java.util.Optional;

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

import com.e1c.g5.dt.applications.ExecutionContext;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

/** Запуск 1С:Підприємства для застосунку (ІБ) проєкту через IApplicationManager. */
public final class RunApplicationTool implements McpTool {

    @Override
    public String name() {
        return "run_application"; //$NON-NLS-1$
    }

    @Override
    public String description() {
        return "Запускає 1С:Підприємство для застосунку проєкту (list_applications покаже доступні). " //$NON-NLS-1$
                + "Без application — застосунок за замовчуванням. Повертає jobId процесу."; //$NON-NLS-1$
    }

    @Override
    public JsonObject inputSchema() {
        return JsonParser.parseString("""
                {"type":"object","properties":{
                  "project":{"type":"string","description":"Ім'я проєкту EDT (необов'язково, якщо проєкт один)"},
                  "application":{"type":"string","description":"Ім'я застосунку; без нього — застосунок за замовчуванням"},
                  "clientType":{"type":"string","enum":["thin","thick"],"default":"thin","description":"Тип клієнта 1С"}
                }}""").getAsJsonObject(); //$NON-NLS-1$
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
        WriteGate.check(); // запуск підприємства може проводити документи/міняти дані

        String projectName = arguments.has("project") ? arguments.get("project").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$
        IProject project = V8Access.resolveEclipseProject(projectName);
        IApplicationManager manager = EdtServices.require(IApplicationManager.class);
        IApplication application = resolveApplication(manager, project,
                arguments.has("application") ? arguments.get("application").getAsString() : null); //$NON-NLS-1$ //$NON-NLS-2$

        ExecutionContext context = EdtExecution.context(false);
        // без типу клієнта IApplicationManager.start падає:
        // «Контекст выполнения не предоставляет тип клиента для запуска»
        context.setProperty(IApplication.CONTEXT_CLIENT_TYPE, YaxunitTestsTool.clientTypeId(arguments));
        Optional<Process> process = manager.start(application, context, new NullProgressMonitor());
        if (process.isEmpty()) {
            JsonObject result = new JsonObject();
            result.addProperty("started", true); //$NON-NLS-1$
            result.addProperty("note", //$NON-NLS-1$
                    "Застосунок запущено, але процес не повернуто (запуск через зовнішній механізм)."); //$NON-NLS-1$
            return result;
        }
        return JobManager.wrapProcess(process.get(),
                "1С:Підприємство — " + application.getName(), Charset.forName("CP866")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    static IApplication resolveApplication(IApplicationManager manager, IProject project, String name)
            throws Exception {
        if (name != null && !name.isBlank()) {
            for (IApplication application : manager.getApplications(project)) {
                if (name.equalsIgnoreCase(application.getName())) {
                    return application;
                }
            }
            throw new IllegalArgumentException("Застосунок не знайдено: " + name //$NON-NLS-1$
                    + ". Список — list_applications."); //$NON-NLS-1$
        }
        return manager.getDefaultApplication(project).orElseThrow(() -> new IllegalArgumentException(
                "У проєкту немає застосунку за замовчуванням — вкажіть application (див. list_applications)")); //$NON-NLS-1$
    }
}
