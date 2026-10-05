/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl;

import org.eclipse.ui.IStartup;

import com.polischuk.edt.prl.server.McpHttpServer;

/** Точка раннього старту: піднімає MCP-сервер одразу після старту workbench. */
public class McpStartup implements IStartup {

    @Override
    public void earlyStartup() {
        McpHttpServer.startInstance();
    }
}
