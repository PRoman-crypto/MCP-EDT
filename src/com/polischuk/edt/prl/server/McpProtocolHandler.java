/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.server;

import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.polischuk.edt.prl.Activator;
import com.polischuk.edt.prl.tools.McpTool;
import com.polischuk.edt.prl.tools.ToolRegistry;

/**
 * Ядро протоколу MCP поверх JSON-RPC 2.0: initialize, ping, tools/list, tools/call.
 * Нотифікації (без id) відповіді не отримують — {@link #handle(String)} повертає null.
 */
public final class McpProtocolHandler {

    public static final String SERVER_NAME = "mcp-prl-server"; //$NON-NLS-1$
    public static final String SERVER_VERSION = "0.21.4"; //$NON-NLS-1$

    private static final String LATEST_PROTOCOL = "2025-06-18"; //$NON-NLS-1$
    private static final Set<String> SUPPORTED_PROTOCOLS = Set.of(
            "2024-11-05", "2025-03-26", "2025-06-18"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private final ToolRegistry registry;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final Gson prettyGson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    public McpProtocolHandler(ToolRegistry registry) {
        this.registry = registry;
    }

    /** Обробляє тіло POST-запиту; повертає JSON відповіді або null для нотифікацій. */
    public String handle(String body) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(body);
        } catch (JsonSyntaxException e) {
            return gson.toJson(errorResponse(null, -32700, "Parse error: " + e.getMessage())); //$NON-NLS-1$
        }
        if (!parsed.isJsonObject()) {
            return gson.toJson(errorResponse(null, JsonRpcException.INVALID_REQUEST,
                    "Expected a single JSON-RPC request object (batching is not supported)")); //$NON-NLS-1$
        }
        JsonObject request = parsed.getAsJsonObject();
        JsonElement id = request.get("id"); //$NON-NLS-1$
        boolean notification = id == null || id.isJsonNull();
        String method = request.has("method") && request.get("method").isJsonPrimitive() //$NON-NLS-1$ //$NON-NLS-2$
                ? request.get("method").getAsString() : null; //$NON-NLS-1$
        if (method == null) {
            return notification ? null
                    : gson.toJson(errorResponse(id, JsonRpcException.INVALID_REQUEST, "Missing method")); //$NON-NLS-1$
        }
        if (method.startsWith("notifications/")) { //$NON-NLS-1$
            return null;
        }
        JsonObject params = new JsonObject();
        JsonElement rawParams = request.get("params"); //$NON-NLS-1$
        if (rawParams != null && rawParams.isJsonObject()) {
            params = rawParams.getAsJsonObject();
        }
        try {
            JsonObject result = dispatch(method, params);
            if (result == null) {
                return notification ? null
                        : gson.toJson(errorResponse(id, JsonRpcException.METHOD_NOT_FOUND,
                                "Method not found: " + method)); //$NON-NLS-1$
            }
            if (notification) {
                return null;
            }
            JsonObject response = new JsonObject();
            response.addProperty("jsonrpc", "2.0"); //$NON-NLS-1$ //$NON-NLS-2$
            response.add("id", id); //$NON-NLS-1$
            response.add("result", result); //$NON-NLS-1$
            return gson.toJson(response);
        } catch (JsonRpcException e) {
            return notification ? null : gson.toJson(errorResponse(id, e.getCode(), e.getMessage()));
        } catch (Throwable e) { // включно з LinkageError від недоступних класів EDT
            Activator.logError("MCP method failed: " + method, e); //$NON-NLS-1$
            return notification ? null : gson.toJson(errorResponse(id, JsonRpcException.INTERNAL_ERROR,
                    e.getClass().getSimpleName() + ": " + e.getMessage())); //$NON-NLS-1$
        }
    }

    private JsonObject dispatch(String method, JsonObject params) {
        switch (method) {
        case "initialize": //$NON-NLS-1$
            return initialize(params);
        case "ping": //$NON-NLS-1$
            return new JsonObject();
        case "tools/list": //$NON-NLS-1$
            return toolsList();
        case "tools/call": //$NON-NLS-1$
            return toolsCall(params);
        default:
            return null;
        }
    }

    private JsonObject initialize(JsonObject params) {
        String requested = params.has("protocolVersion") //$NON-NLS-1$
                ? params.get("protocolVersion").getAsString() : LATEST_PROTOCOL; //$NON-NLS-1$
        String protocolVersion = SUPPORTED_PROTOCOLS.contains(requested) ? requested : LATEST_PROTOCOL;

        JsonObject toolsCapability = new JsonObject();
        toolsCapability.addProperty("listChanged", false); //$NON-NLS-1$
        JsonObject capabilities = new JsonObject();
        capabilities.add("tools", toolsCapability); //$NON-NLS-1$

        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", SERVER_NAME); //$NON-NLS-1$
        serverInfo.addProperty("title", "MCP:PRL Server"); //$NON-NLS-1$ //$NON-NLS-2$
        serverInfo.addProperty("version", SERVER_VERSION); //$NON-NLS-1$

        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", protocolVersion); //$NON-NLS-1$
        result.add("capabilities", capabilities); //$NON-NLS-1$
        result.add("serverInfo", serverInfo); //$NON-NLS-1$
        result.addProperty("instructions", //$NON-NLS-1$
                "MCP-сервер всередині 1C:EDT. Інструменти працюють із проєктами, відкритими в поточному workspace EDT."); //$NON-NLS-1$
        return result;
    }

    private JsonObject toolsList() {
        JsonArray tools = new JsonArray();
        for (McpTool tool : registry.all()) {
            JsonObject t = new JsonObject();
            t.addProperty("name", tool.name()); //$NON-NLS-1$
            t.addProperty("description", tool.description()); //$NON-NLS-1$
            t.add("inputSchema", tool.inputSchema()); //$NON-NLS-1$
            tools.add(t);
        }
        JsonObject result = new JsonObject();
        result.add("tools", tools); //$NON-NLS-1$
        return result;
    }

    private JsonObject toolsCall(JsonObject params) {
        String name = params.has("name") ? params.get("name").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$
        if (name == null || name.isBlank()) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, "Missing tool name"); //$NON-NLS-1$
        }
        McpTool tool = registry.get(name);
        if (tool == null) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, "Unknown tool: " + name); //$NON-NLS-1$
        }
        JsonObject arguments = new JsonObject();
        JsonElement rawArguments = params.get("arguments"); //$NON-NLS-1$
        if (rawArguments != null && rawArguments.isJsonObject()) {
            arguments = rawArguments.getAsJsonObject();
        }
        try {
            JsonElement data = tool.execute(arguments);
            JsonObject result = new JsonObject();
            result.add("content", textContent(prettyGson.toJson(data))); //$NON-NLS-1$
            if (data != null && data.isJsonObject()) {
                result.add("structuredContent", data); //$NON-NLS-1$
            }
            result.addProperty("isError", false); //$NON-NLS-1$
            return result;
        } catch (Throwable e) {
            Activator.logError("Tool failed: " + name, e); //$NON-NLS-1$
            JsonObject result = new JsonObject();
            result.add("content", textContent(e.getClass().getSimpleName() + ": " + e.getMessage())); //$NON-NLS-1$
            result.addProperty("isError", true); //$NON-NLS-1$
            return result;
        }
    }

    private static JsonArray textContent(String text) {
        JsonObject item = new JsonObject();
        item.addProperty("type", "text"); //$NON-NLS-1$ //$NON-NLS-2$
        item.addProperty("text", text); //$NON-NLS-1$
        JsonArray content = new JsonArray();
        content.add(item);
        return content;
    }

    private static JsonObject errorResponse(JsonElement id, int code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code); //$NON-NLS-1$
        error.addProperty("message", message); //$NON-NLS-1$
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0"); //$NON-NLS-1$ //$NON-NLS-2$
        response.add("id", id); //$NON-NLS-1$
        response.add("error", error); //$NON-NLS-1$
        return response;
    }
}
