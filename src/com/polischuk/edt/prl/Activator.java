/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl;

import org.eclipse.core.runtime.Plugin;
import org.eclipse.core.runtime.Status;
import org.osgi.framework.BundleContext;

import com.polischuk.edt.prl.server.McpHttpServer;

public class Activator extends Plugin {

    public static final String PLUGIN_ID = "com.polischuk.edt.prl.server"; //$NON-NLS-1$

    private static volatile Activator instance;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        instance = this;
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        McpHttpServer.stopInstance();
        instance = null;
        super.stop(context);
    }

    public static Activator getDefault() {
        return instance;
    }

    public static void logInfo(String message) {
        Activator a = instance;
        if (a != null) {
            a.getLog().log(new Status(Status.INFO, PLUGIN_ID, message));
        }
    }

    public static void logError(String message, Throwable t) {
        Activator a = instance;
        if (a != null) {
            a.getLog().log(new Status(Status.ERROR, PLUGIN_ID, message, t));
        }
    }
}
