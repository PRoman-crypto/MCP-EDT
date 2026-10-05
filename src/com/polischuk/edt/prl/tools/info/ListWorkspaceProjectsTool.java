/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.info;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.emf.ecore.EObject;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.polischuk.edt.prl.edt.Emf;
import com.polischuk.edt.prl.edt.V8Access;
import com.polischuk.edt.prl.tools.McpTool;

import com._1c.g5.v8.dt.core.platform.IV8Project;

/**
 * Проєкти поточного workspace EDT. Поки що — через Eclipse resources API
 * (ознака 1С-проєкту визначається за nature); деталізація типів (конфігурація,
 * розширення, зовнішні обробки) буде додана через IV8ProjectManager у milestone 2.
 */
public final class ListWorkspaceProjectsTool implements McpTool {

    private static final String V8_NATURE_PREFIX = "com._1c.g5"; //$NON-NLS-1$

    @Override
    public String name() {
        return "list_workspace_projects"; //$NON-NLS-1$
    }

    @Override
    public String description() {
        return "Список проєктів у поточному workspace 1C:EDT: ім'я, розташування, стан, ознака 1С-проєкту."; //$NON-NLS-1$
    }

    @Override
    public JsonObject inputSchema() {
        return McpTool.emptyObjectSchema();
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws CoreException {
        JsonArray projects = new JsonArray();
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            JsonObject item = new JsonObject();
            item.addProperty("name", project.getName()); //$NON-NLS-1$
            IPath location = project.getLocation();
            item.addProperty("location", location == null ? null : location.toOSString()); //$NON-NLS-1$
            item.addProperty("open", project.isOpen()); //$NON-NLS-1$
            if (project.isOpen()) {
                boolean is1c = false;
                JsonArray natures = new JsonArray();
                for (String natureId : project.getDescription().getNatureIds()) {
                    natures.add(natureId);
                    if (natureId.startsWith(V8_NATURE_PREFIX)) {
                        is1c = true;
                    }
                }
                item.add("natures", natures); //$NON-NLS-1$
                item.addProperty("is1cProject", is1c); //$NON-NLS-1$
                if (is1c) {
                    addV8Details(item, project);
                }
            }
            projects.add(item);
        }
        JsonObject result = new JsonObject();
        result.addProperty("count", projects.size()); //$NON-NLS-1$
        result.add("projects", projects); //$NON-NLS-1$
        return result;
    }

    /** Тип 1С-проєкту і властивості конфігурації через IV8ProjectManager (стійко до відмов EDT API). */
    private static void addV8Details(JsonObject item, IProject project) {
        try {
            IV8Project v8Project = V8Access.projectManager().getProject(project);
            if (v8Project == null) {
                return;
            }
            item.addProperty("v8ProjectType", v8Project.getClass().getSimpleName()); //$NON-NLS-1$
            EObject configuration = V8Access.configuration(v8Project);
            if (configuration != null) {
                item.addProperty("configurationName", Emf.name(configuration)); //$NON-NLS-1$
                String version = Emf.str(configuration, "version"); //$NON-NLS-1$
                if (version != null && !version.isBlank()) {
                    item.addProperty("configurationVersion", version); //$NON-NLS-1$
                }
            }
        } catch (Throwable e) { // включно з NoClassDefFoundError, якщо EDT API недоступне
            item.addProperty("v8Error", e.toString()); //$NON-NLS-1$
        }
    }
}
