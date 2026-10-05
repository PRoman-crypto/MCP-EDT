/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.jobs;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.polischuk.edt.prl.edt.WriteGate;
import com.polischuk.edt.prl.tools.McpTool;

/**
 * Підключення наявного на диску проєкту до workspace EDT (аналог File → Import →
 * Existing Projects).
 *
 * <p>Потрібен, коли проєкт створено поза EDT — інакше IDE його просто не бачить:
 * Eclipse не сканує диск, проєкт з'являється лише після явного імпорту.
 * Каталог має містити {@code .project}; для проєктів 1С — ще {@code DT-INF/PROJECT.PMF}.
 */
public final class ImportProjectTool implements McpTool {

    @Override
    public String name() {
        return "import_project"; //$NON-NLS-1$
    }

    @Override
    public String description() {
        return "Підключає наявний каталог проєкту до workspace EDT (як File → Import → Existing Projects). " //$NON-NLS-1$
                + "path — абсолютний шлях до каталогу з .project. Якщо проєкт уже у workspace, " //$NON-NLS-1$
                + "повертає його стан без змін."; //$NON-NLS-1$
    }

    @Override
    public JsonObject inputSchema() {
        return JsonParser.parseString("""
                {"type":"object","properties":{
                  "path":{"type":"string","description":"Абсолютний шлях до каталогу проєкту (усередині має бути .project)"}
                },"required":["path"]}""").getAsJsonObject(); //$NON-NLS-1$
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
        WriteGate.check(); // змінює склад workspace, а не лише читає

        Path folder = Path.of(arguments.get("path").getAsString()); //$NON-NLS-1$
        if (!folder.isAbsolute()) {
            throw new IllegalArgumentException("path має бути абсолютним: " + folder); //$NON-NLS-1$
        }
        Path descriptionFile = folder.resolve(".project"); //$NON-NLS-1$
        if (!Files.isRegularFile(descriptionFile)) {
            throw new IllegalArgumentException("У каталозі немає .project: " + folder //$NON-NLS-1$
                    + ". Імпортувати можна лише готовий каталог проєкту Eclipse/EDT."); //$NON-NLS-1$
        }

        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IProjectDescription description = workspace.loadProjectDescription(
                new org.eclipse.core.runtime.Path(descriptionFile.toString()));
        description.setLocation(new org.eclipse.core.runtime.Path(folder.toString()));
        IProject project = workspace.getRoot().getProject(description.getName());

        JsonObject result = new JsonObject();
        result.addProperty("name", description.getName()); //$NON-NLS-1$
        result.addProperty("location", folder.toString()); //$NON-NLS-1$
        if (project.exists()) {
            if (!project.isOpen()) {
                project.open(new NullProgressMonitor());
            }
            result.addProperty("imported", false); //$NON-NLS-1$
            result.addProperty("message", "Проєкт уже у workspace" //$NON-NLS-1$ //$NON-NLS-2$
                    + (project.isOpen() ? " і відкритий." : ".")); //$NON-NLS-1$ //$NON-NLS-2$
        } else {
            project.create(description, new NullProgressMonitor());
            project.open(new NullProgressMonitor());
            result.addProperty("imported", true); //$NON-NLS-1$
        }
        result.addProperty("open", project.isOpen()); //$NON-NLS-1$

        JsonArray natures = new JsonArray();
        for (String nature : project.getDescription().getNatureIds()) {
            natures.add(nature);
        }
        result.add("natures", natures); //$NON-NLS-1$
        result.addProperty("hint", "Індексація і збірка EDT ідуть у фоні — " //$NON-NLS-1$ //$NON-NLS-2$
                + "перший виклик по цьому проєкту може повернути «проєкт не знайдено», повторіть."); //$NON-NLS-1$
        return result;
    }
}
