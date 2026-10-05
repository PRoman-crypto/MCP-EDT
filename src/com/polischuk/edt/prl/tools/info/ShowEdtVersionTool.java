/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.info;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProduct;
import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.polischuk.edt.prl.edt.EdtServices;
import com.polischuk.edt.prl.server.McpProtocolHandler;
import com.polischuk.edt.prl.tools.McpTool;

/** Версії 1C:EDT, Eclipse, Java і параметри поточного workspace. */
public final class ShowEdtVersionTool implements McpTool {

    @Override
    public String name() {
        return "show_edt_version"; //$NON-NLS-1$
    }

    @Override
    public String description() {
        return "Версія 1C:EDT, платформи Eclipse і Java, шлях до workspace та версія MCP-сервера."; //$NON-NLS-1$
    }

    @Override
    public JsonObject inputSchema() {
        return McpTool.emptyObjectSchema();
    }

    @Override
    public JsonElement execute(JsonObject arguments) {
        JsonObject result = new JsonObject();
        IProduct product = Platform.getProduct();
        if (product != null) {
            result.addProperty("product", product.getName()); //$NON-NLS-1$
        }
        result.addProperty("edtVersion", bundleVersion("com._1c.g5.v8.dt.core")); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("eclipsePlatform", bundleVersion("org.eclipse.platform")); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("java", System.getProperty("java.vendor") //$NON-NLS-1$ //$NON-NLS-2$
                + " " + System.getProperty("java.runtime.version")); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("os", System.getProperty("os.name") //$NON-NLS-1$ //$NON-NLS-2$
                + " " + System.getProperty("os.version")); //$NON-NLS-1$ //$NON-NLS-2$
        IPath workspace = ResourcesPlugin.getWorkspace().getRoot().getLocation();
        result.addProperty("workspace", workspace == null ? null : workspace.toOSString()); //$NON-NLS-1$
        result.addProperty("mcpServerVersion", McpProtocolHandler.SERVER_VERSION); //$NON-NLS-1$

        JsonObject services = new JsonObject();
        services.addProperty("IV8ProjectManager", //$NON-NLS-1$
                EdtServices.describeAvailability("com._1c.g5.v8.dt.core.platform.IV8ProjectManager")); //$NON-NLS-1$
        services.addProperty("IBmModelManager", //$NON-NLS-1$
                EdtServices.describeAvailability("com._1c.g5.v8.dt.core.platform.IBmModelManager")); //$NON-NLS-1$
        services.addProperty("IMarkerManager", //$NON-NLS-1$
                EdtServices.describeAvailability("com._1c.g5.v8.dt.validation.marker.IMarkerManager")); //$NON-NLS-1$
        services.addProperty("IResourceLookup", //$NON-NLS-1$
                EdtServices.describeAvailability("com._1c.g5.v8.dt.core.platform.IResourceLookup")); //$NON-NLS-1$
        result.add("edtServices", services); //$NON-NLS-1$
        return result;
    }

    private static String bundleVersion(String symbolicName) {
        Bundle bundle = Platform.getBundle(symbolicName);
        return bundle == null ? null : bundle.getVersion().toString();
    }
}
