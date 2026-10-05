/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.jobs;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.polischuk.edt.prl.edt.V8Access;
import com.polischuk.edt.prl.tools.McpTool;

/**
 * Очищення (clean) проєкту EDT і, за потреби, повна пересборка.
 *
 * <p>Коли потрібно: після оновлення EDT, при дивних помилках компіляції чи валідації,
 * коли EDT перестала бачити свіжі правки. Після clean платформа перебудує проєкт сама.
 *
 * <p>Операція довга (на типовій конфігурації — хвилини) і руйнує кеш, тож:
 * без {@code confirmed} інструмент лише показує, що саме буде очищено, а сама збірка
 * йде асинхронною джобою — інакше виклик MCP обірвався б по таймауту раніше, ніж
 * EDT закінчила б роботу.
 */
public final class RebuildProjectTool implements McpTool {

    @Override
    public String name() {
        return "rebuild_project"; //$NON-NLS-1$
    }

    @Override
    public String description() {
        return "Очищення проєкту EDT (clean build): скидає кеш збірки, після чого EDT перебудує проєкт. " //$NON-NLS-1$
                + "Без project — усі відкриті проєкти. rebuildAfterClean=true — одразу повна пересборка. " //$NON-NLS-1$
                + "Без confirmed=true нічого не робить, лише показує, що буде очищено. " //$NON-NLS-1$
                + "Повертає jobId — стан дивитись через get_job_status."; //$NON-NLS-1$
    }

    @Override
    public JsonObject inputSchema() {
        return JsonParser.parseString("""
                {"type":"object","properties":{
                  "project":{"type":"string","description":"Ім'я проєкту; без нього — усі відкриті проєкти workspace"},
                  "rebuildAfterClean":{"type":"boolean","default":false,"description":"Виконати повну пересборку одразу після очищення"},
                  "confirmed":{"type":"boolean","default":false,"description":"Підтвердження: без нього повертається лише перелік того, що буде очищено"}
                }}""").getAsJsonObject(); //$NON-NLS-1$
    }

    @Override
    public JsonElement execute(JsonObject arguments) {
        String projectName = arguments.has("project") && !arguments.get("project").getAsString().isBlank() //$NON-NLS-1$ //$NON-NLS-2$
                ? arguments.get("project").getAsString() : null; //$NON-NLS-1$
        boolean rebuildAfterClean = arguments.has("rebuildAfterClean") //$NON-NLS-1$
                && arguments.get("rebuildAfterClean").getAsBoolean(); //$NON-NLS-1$
        boolean confirmed = arguments.has("confirmed") && arguments.get("confirmed").getAsBoolean(); //$NON-NLS-1$ //$NON-NLS-2$

        List<IProject> projects = new ArrayList<>();
        if (projectName != null) {
            projects.add(V8Access.resolveEclipseProject(projectName));
        } else {
            projects.addAll(V8Access.openEclipseProjects());
        }

        JsonArray names = new JsonArray();
        projects.forEach(project -> names.add(project.getName()));

        JsonObject result = new JsonObject();
        result.add("projects", names); //$NON-NLS-1$
        result.addProperty("rebuildAfterClean", rebuildAfterClean); //$NON-NLS-1$
        if (!confirmed) {
            result.addProperty("confirmed", false); //$NON-NLS-1$
            result.addProperty("message", "Очищення не виконано. Буде очищено проєктів: " //$NON-NLS-1$ //$NON-NLS-2$
                    + projects.size() + ". Кеш збірки й маркери валідації скидаються, наступна " //$NON-NLS-1$
                    + "збірка займе час. Повторіть виклик із confirmed=true."); //$NON-NLS-1$
            return result;
        }

        String label = "rebuild_project " + (projectName == null ? "усі проєкти" : projectName); //$NON-NLS-1$ //$NON-NLS-2$
        return JobManager.startTask(label, () -> {
            StringBuilder log = new StringBuilder();
            for (IProject project : projects) {
                log.append("clean: ").append(project.getName()).append('\n'); //$NON-NLS-1$
                project.build(IncrementalProjectBuilder.CLEAN_BUILD, new NullProgressMonitor());
                if (rebuildAfterClean) {
                    log.append("build: ").append(project.getName()).append('\n'); //$NON-NLS-1$
                    project.build(IncrementalProjectBuilder.FULL_BUILD, new NullProgressMonitor());
                }
            }
            log.append("Готово: проєктів оброблено ").append(projects.size()); //$NON-NLS-1$
            return log.toString();
        });
    }
}
