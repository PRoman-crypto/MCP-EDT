/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.jobs;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.polischuk.edt.prl.tools.McpTool;

/** Статус асинхронної джоби (run_vrunner тощо): стан, код виходу, хвіст виводу. */
public final class GetJobStatusTool implements McpTool {

    @Override
    public String name() {
        return "get_job_status"; //$NON-NLS-1$
    }

    @Override
    public String description() {
        return "Статус асинхронної джоби за jobId (+хвіст виводу; stop:true — зупинити процес). " //$NON-NLS-1$
                + "Без jobId — список усіх джоб сесії."; //$NON-NLS-1$
    }

    @Override
    public JsonObject inputSchema() {
        return JsonParser.parseString("""
                {"type":"object","properties":{
                  "jobId":{"type":"string"},
                  "tailLines":{"type":"integer","default":100},
                  "stop":{"type":"boolean","default":false,"description":"Зупинити процес джоби"}
                }}""").getAsJsonObject(); //$NON-NLS-1$
    }

    @Override
    public JsonElement execute(JsonObject arguments) {
        if (!arguments.has("jobId") || arguments.get("jobId").getAsString().isBlank()) { //$NON-NLS-1$ //$NON-NLS-2$
            JsonObject result = new JsonObject();
            result.add("jobs", JobManager.list()); //$NON-NLS-1$
            return result;
        }
        String jobId = arguments.get("jobId").getAsString(); //$NON-NLS-1$
        if (arguments.has("stop") && arguments.get("stop").getAsBoolean()) { //$NON-NLS-1$ //$NON-NLS-2$
            return JobManager.stop(jobId);
        }
        int tailLines = arguments.has("tailLines") ? arguments.get("tailLines").getAsInt() : 100; //$NON-NLS-1$ //$NON-NLS-2$
        return JobManager.status(jobId, tailLines);
    }
}
