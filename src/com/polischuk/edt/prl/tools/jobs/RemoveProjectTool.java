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
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.polischuk.edt.prl.edt.WriteGate;
import com.polischuk.edt.prl.tools.McpTool;

/**
 * Прибирання проєкту з workspace EDT — зворотна операція до {@code import_project}.
 *
 * <p>{@code deleteContent=false} (типово) прибирає лише запис у workspace, файли на диску
 * лишаються — так само, як Delete без галочки в EDT. З {@code deleteContent=true} каталог
 * видаляється з диска НАЗАВЖДИ, тому обидва режими вимагають {@code confirmed=true}:
 * без нього інструмент лише показує, що саме буде прибрано.
 */
public final class RemoveProjectTool implements McpTool {

    @Override
    public String name() {
        return "remove_project"; //$NON-NLS-1$
    }

    @Override
    public String description() {
        return "Прибирає проєкт із workspace EDT (зворотне до import_project). " //$NON-NLS-1$
                + "deleteContent=false (типово) — лише запис у workspace, файли лишаються; " //$NON-NLS-1$
                + "deleteContent=true — каталог видаляється з диска назавжди. " //$NON-NLS-1$
                + "Без confirmed=true нічого не робить, лише показує, що буде прибрано."; //$NON-NLS-1$
    }

    @Override
    public JsonObject inputSchema() {
        return JsonParser.parseString("""
                {"type":"object","properties":{
                  "project":{"type":"string","description":"Ім'я проєкту у workspace"},
                  "deleteContent":{"type":"boolean","default":false,"description":"Видалити ще й каталог із диска (незворотно)"},
                  "confirmed":{"type":"boolean","default":false,"description":"Підтвердження; без нього — лише опис того, що буде зроблено"}
                },"required":["project"]}""").getAsJsonObject(); //$NON-NLS-1$
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
        WriteGate.check();

        String projectName = arguments.get("project").getAsString(); //$NON-NLS-1$
        boolean deleteContent = arguments.has("deleteContent") //$NON-NLS-1$
                && arguments.get("deleteContent").getAsBoolean(); //$NON-NLS-1$
        boolean confirmed = arguments.has("confirmed") && arguments.get("confirmed").getAsBoolean(); //$NON-NLS-1$ //$NON-NLS-2$

        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!project.exists()) {
            throw new IllegalArgumentException("Проєкт не знайдено у workspace: " + projectName //$NON-NLS-1$
                    + ". Список — list_workspace_projects."); //$NON-NLS-1$
        }
        IPath location = project.getLocation();

        JsonObject result = new JsonObject();
        result.addProperty("project", projectName); //$NON-NLS-1$
        result.addProperty("location", location == null ? null : location.toOSString()); //$NON-NLS-1$
        result.addProperty("deleteContent", deleteContent); //$NON-NLS-1$
        if (!confirmed) {
            result.addProperty("removed", false); //$NON-NLS-1$
            result.addProperty("message", deleteContent //$NON-NLS-1$
                    ? "Не виконано. Буде прибрано з workspace І ВИДАЛЕНО З ДИСКА: " + location //$NON-NLS-1$
                            + ". Повторіть із confirmed=true." //$NON-NLS-1$
                    : "Не виконано. Буде прибрано лише запис у workspace, файли лишаться на диску. " //$NON-NLS-1$
                            + "Повторіть із confirmed=true."); //$NON-NLS-1$
            return result;
        }

        // force=true: прибираємо навіть якщо частина ресурсів не синхронізована з диском
        project.delete(deleteContent, true, new NullProgressMonitor());
        result.addProperty("removed", true); //$NON-NLS-1$
        result.addProperty("stillOnDisk", !deleteContent); //$NON-NLS-1$
        return result;
    }
}
