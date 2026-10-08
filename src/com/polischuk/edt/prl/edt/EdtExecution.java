/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.edt;

import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.e1c.g5.dt.applications.ExecutionContext;

/**
 * Контекст виконання для {@code IApplicationManager}. Поведінка застосунків-ІБ EDT
 * ({@code InfobaseApplicationBehaviourDelegate.update/prepare}) бере з контексту вікно
 * {@link ExecutionContext#ACTIVE_SHELL_NAME} — для діалогів змін в ІБ і облікових даних —
 * і без нього падає з «Shell is not provided in execution context». У кнопки «Оновити»
 * вікно кладе обробник UI; HTTP-потік MCP його не має, тож беремо вікно workbench самі.
 */
public final class EdtExecution {

    private EdtExecution() {
    }

    /** Контекст із вікном EDT; якщо workbench недоступний, помилка пояснює причину. */
    public static ExecutionContext context() {
        return context(true);
    }

    /**
     * Контекст із вікном EDT. {@code required=false} — для запуску клієнта, якому вікно
     * потрібне лише для необов'язкових діалогів: без вікна контекст усе одно повертається.
     */
    public static ExecutionContext context(boolean required) {
        Shell shell = workbenchShell();
        if (shell == null && required) {
            throw new IllegalStateException("Немає вікна 1С:EDT (workbench не запущений або вікно закрите): " //$NON-NLS-1$
                    + "оновлення ІБ потребує вікна для діалогів EDT"); //$NON-NLS-1$
        }
        ExecutionContext context = new ExecutionContext();
        if (shell != null) {
            context.setProperty(ExecutionContext.ACTIVE_SHELL_NAME, shell);
        }
        return context;
    }

    /**
     * Вікно для діалогів: активне, інакше вікно активного/першого workbench-вікна, інакше будь-яке
     * придатне вікно дисплея. Активного вікна може не бути, коли фокус в іншій програмі.
     */
    static Shell workbenchShell() {
        try {
            if (!PlatformUI.isWorkbenchRunning()) {
                return null;
            }
            Display display = PlatformUI.getWorkbench().getDisplay();
            if (display == null || display.isDisposed()) {
                return null;
            }
            if (Display.getCurrent() == display) {
                return pickShell(display);
            }
            AtomicReference<Shell> found = new AtomicReference<>();
            display.syncExec(() -> found.set(pickShell(display)));
            return found.get();
        } catch (LinkageError | IllegalStateException e) {
            return null;
        }
    }

    private static Shell pickShell(Display display) {
        Shell active = display.getActiveShell();
        if (usable(active)) {
            return active;
        }
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null) {
            IWorkbenchWindow[] windows = PlatformUI.getWorkbench().getWorkbenchWindows();
            window = windows.length > 0 ? windows[0] : null;
        }
        if (window != null && usable(window.getShell())) {
            return window.getShell();
        }
        for (Shell candidate : display.getShells()) {
            if (usable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean usable(Shell shell) {
        return shell != null && !shell.isDisposed();
    }
}
