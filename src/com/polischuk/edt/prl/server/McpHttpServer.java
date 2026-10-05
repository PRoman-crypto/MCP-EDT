/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.server;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;

import com.polischuk.edt.prl.Activator;
import com.polischuk.edt.prl.tools.ToolRegistry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Локальний MCP-сервер (Streamable HTTP): один endpoint /mcp, JSON-RPC 2.0 через POST.
 * Біндинг тільки на loopback; порт задається -Dmcp.prl.port (дефолт 8765),
 * при зайнятості сканується діапазон із 10 портів угору.
 */
public final class McpHttpServer {

    public static final String ENDPOINT = "/mcp"; //$NON-NLS-1$
    private static final int DEFAULT_PORT = 8765;
    private static final int PORT_SCAN_RANGE = 10;

    private static volatile McpHttpServer instance;

    private final HttpServer http;
    private final McpProtocolHandler protocol;
    private final String sessionId = UUID.randomUUID().toString();
    private final int port;

    public static synchronized void startInstance() {
        if (instance != null) {
            return;
        }
        try {
            instance = new McpHttpServer();
            Activator.logInfo("MCP server listening on http://127.0.0.1:" + instance.port + ENDPOINT); //$NON-NLS-1$
            instance.writeDiscoveryFile();
        } catch (IOException e) {
            Activator.logError("Failed to start MCP server", e); //$NON-NLS-1$
        }
    }

    public static synchronized void stopInstance() {
        McpHttpServer current = instance;
        if (current != null) {
            current.http.stop(0);
            current.deleteDiscoveryFile();
            instance = null;
        }
    }

    /** Перезапуск сервера (напр., після зміни порту в Preferences) — без рестарту EDT. */
    public static synchronized void restartInstance() {
        stopInstance();
        startInstance();
    }

    /** Налаштований базовий порт: Preferences → -Dmcp.prl.port → дефолт. */
    public static int configuredBasePort() {
        int fromPreferences = org.eclipse.core.runtime.preferences.InstanceScope.INSTANCE
                .getNode(com.polischuk.edt.prl.Activator.PLUGIN_ID).getInt("port", -1); //$NON-NLS-1$
        if (fromPreferences > 0) {
            return fromPreferences;
        }
        return Integer.getInteger("mcp.prl.port", DEFAULT_PORT).intValue(); //$NON-NLS-1$
    }

    /** Порт запущеного сервера або -1, якщо сервер не стартував. */
    public static int runningPort() {
        McpHttpServer current = instance;
        return current == null ? -1 : current.port;
    }

    private McpHttpServer() throws IOException {
        protocol = new McpProtocolHandler(ToolRegistry.createDefault());

        int base = configuredBasePort();
        HttpServer created = null;
        int boundPort = -1;
        IOException lastError = null;
        for (int p = base; p < base + PORT_SCAN_RANGE; p++) {
            try {
                created = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), p), 0);
                boundPort = p;
                break;
            } catch (IOException e) {
                lastError = e;
            }
        }
        if (created == null) {
            throw lastError != null ? lastError
                    : new IOException("No free port in range " + base + ".." + (base + PORT_SCAN_RANGE - 1)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        http = created;
        port = boundPort;
        http.createContext(ENDPOINT, this::handleExchange);
        http.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "mcp-prl-http"); //$NON-NLS-1$
            t.setDaemon(true);
            return t;
        }));
        http.start();
    }

    private void handleExchange(HttpExchange exchange) throws IOException {
        try {
            String origin = exchange.getRequestHeaders().getFirst("Origin"); //$NON-NLS-1$
            if (!isOriginAllowed(origin)) {
                respond(exchange, 403, "{\"error\":\"forbidden origin\"}"); //$NON-NLS-1$
                return;
            }
            switch (exchange.getRequestMethod()) {
            case "POST": //$NON-NLS-1$
                handlePost(exchange);
                break;
            case "GET": //$NON-NLS-1$
                respond(exchange, 405, "{\"error\":\"SSE stream is not supported; use POST\"}"); //$NON-NLS-1$
                break;
            case "DELETE": //$NON-NLS-1$
                respond(exchange, 200, ""); //$NON-NLS-1$
                break;
            default:
                respond(exchange, 405, ""); //$NON-NLS-1$
            }
        } catch (Throwable e) {
            Activator.logError("MCP request handling failed", e); //$NON-NLS-1$
            try {
                respond(exchange, 500, "{\"error\":\"internal server error\"}"); //$NON-NLS-1$
            } catch (IOException ignore) {
                // канал уже закритий клієнтом
            }
        } finally {
            exchange.close();
        }
    }

    private void handlePost(HttpExchange exchange) throws IOException {
        byte[] raw = exchange.getRequestBody().readAllBytes();
        String body = new String(raw, StandardCharsets.UTF_8);
        String response = protocol.handle(body);
        if (response == null) {
            respond(exchange, 202, ""); //$NON-NLS-1$
            return;
        }
        exchange.getResponseHeaders().set("Mcp-Session-Id", sessionId); //$NON-NLS-1$
        respond(exchange, 200, response);
    }

    /**
     * Discovery-файл %USERPROFILE%\.edt-mcp\instance-<hash-workspace>.json — щоб клієнти
     * знаходили фактичний порт, коли відкрито кілька копій EDT (порт міг зміститися).
     */
    private java.nio.file.Path discoveryFile() {
        String workspace = org.eclipse.core.resources.ResourcesPlugin.getWorkspace()
                .getRoot().getLocation().toOSString();
        String hash = Integer.toHexString(workspace.hashCode());
        return java.nio.file.Path.of(System.getProperty("user.home"), ".edt-mcp", //$NON-NLS-1$ //$NON-NLS-2$
                "instance-" + hash + ".json"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void writeDiscoveryFile() {
        try {
            String workspace = org.eclipse.core.resources.ResourcesPlugin.getWorkspace()
                    .getRoot().getLocation().toOSString();
            String json = "{\"endpoint\":\"http://127.0.0.1:" + port + ENDPOINT + "\",\"port\":" + port //$NON-NLS-1$ //$NON-NLS-2$
                    + ",\"workspace\":" + new com.google.gson.Gson().toJson(workspace) //$NON-NLS-1$
                    + ",\"pid\":" + ProcessHandle.current().pid() + "}"; //$NON-NLS-1$ //$NON-NLS-2$
            java.nio.file.Path file = discoveryFile();
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.writeString(file, json);
        } catch (Exception e) {
            Activator.logError("Failed to write discovery file", e); //$NON-NLS-1$
        }
    }

    private void deleteDiscoveryFile() {
        try {
            java.nio.file.Files.deleteIfExists(discoveryFile());
        } catch (Exception ignored) {
            // не критично
        }
    }

    private static boolean isOriginAllowed(String origin) {
        if (origin == null || origin.isBlank()) {
            return true; // не-браузерні клієнти (CLI) заголовок не шлють
        }
        try {
            String host = URI.create(origin.trim()).getHost();
            return host != null && ("localhost".equalsIgnoreCase(host) //$NON-NLS-1$
                    || "127.0.0.1".equals(host) || "::1".equals(host) || "[::1]".equals(host)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8"); //$NON-NLS-1$ //$NON-NLS-2$
        if (bytes.length == 0) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}
