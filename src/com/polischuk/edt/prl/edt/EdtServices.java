/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.edt;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

/** Доступ до сервісів EDT через реєстр OSGi. */
public final class EdtServices {

    private EdtServices() {
    }

    public static <T> T get(Class<T> type) {
        Bundle bundle = FrameworkUtil.getBundle(EdtServices.class);
        if (bundle == null) {
            return null;
        }
        BundleContext context = bundle.getBundleContext();
        if (context == null) {
            return null;
        }
        ServiceReference<T> reference = context.getServiceReference(type);
        return reference == null ? null : context.getService(reference);
    }

    public static <T> T require(Class<T> type) {
        T service = get(type);
        if (service == null) {
            throw new IllegalStateException("EDT service is not available in OSGi registry: " + type.getName()); //$NON-NLS-1$
        }
        return service;
    }

    /** Діагностика: чи резолвиться клас і чи є сервіс у реєстрі. */
    public static String describeAvailability(String className) {
        try {
            Class<?> type = Class.forName(className);
            return get(type) != null ? "available" : "class resolved, service NOT registered"; //$NON-NLS-1$ //$NON-NLS-2$
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            return "class not resolved: " + e.getMessage(); //$NON-NLS-1$
        }
    }
}
