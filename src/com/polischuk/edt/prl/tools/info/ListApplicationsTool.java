/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.info;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.polischuk.edt.prl.edt.EdtServices;
import com.polischuk.edt.prl.edt.V8Access;
import com.polischuk.edt.prl.tools.McpTool;

import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

/** Застосунки (інформаційні бази) проєктів EDT: ім'я, тип, стан, ознака дефолтного. */
public final class ListApplicationsTool implements McpTool {

    @Override
    public String name() {
        return "list_applications"; //$NON-NLS-1$
    }

    @Override
    public String description() {
        return "Список застосунків (інформаційних баз), підключених до проєктів workspace: " //$NON-NLS-1$
                + "ім'я, тип, стан життєвого циклу, ознака застосунку за замовчуванням."; //$NON-NLS-1$
    }

    @Override
    public JsonObject inputSchema() {
        return JsonParser.parseString("""
                {"type":"object","properties":{
                  "project":{"type":"string","description":"Ім'я проєкту EDT; без нього — усі 1С-проєкти workspace"}
                }}""").getAsJsonObject(); //$NON-NLS-1$
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
        String projectName = arguments.has("project") ? arguments.get("project").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$
        IApplicationManager manager = EdtServices.require(IApplicationManager.class);

        List<IProject> projects = new ArrayList<>();
        if (projectName != null && !projectName.isBlank()) {
            projects.add(V8Access.resolveEclipseProject(projectName));
        } else {
            V8Access.v8Projects().forEach(p -> projects.add(p.getProject()));
        }

        JsonArray applications = new JsonArray();
        for (IProject project : projects) {
            String defaultId = manager.getDefaultApplication(project)
                    .map(IApplication::getId).orElse(null);
            for (IApplication application : manager.getApplications(project)) {
                JsonObject entry = new JsonObject();
                entry.addProperty("project", project.getName()); //$NON-NLS-1$
                entry.addProperty("id", application.getId()); //$NON-NLS-1$
                entry.addProperty("name", application.getName()); //$NON-NLS-1$
                entry.addProperty("type", application.getType() == null ? null //$NON-NLS-1$
                        : application.getType().getName());
                application.getRequiredVersion()
                        .ifPresent(v -> entry.addProperty("platformVersion", v)); //$NON-NLS-1$
                entry.addProperty("isDefault", application.getId().equals(defaultId)); //$NON-NLS-1$
                try {
                    entry.addProperty("state", String.valueOf(manager.getLifecycleState(application))); //$NON-NLS-1$
                } catch (Exception e) {
                    entry.addProperty("state", "unknown"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                applications.add(entry);
            }
        }

        JsonObject result = new JsonObject();
        result.addProperty("count", applications.size()); //$NON-NLS-1$
        result.add("applications", applications); //$NON-NLS-1$
        if (applications.size() == 0) {
            result.addProperty("hint", //$NON-NLS-1$
                    "До проєктів не підключено жодної інформаційної бази (вкладка «Приложения» в EDT)."); //$NON-NLS-1$
        }
        return result;
    }
}
