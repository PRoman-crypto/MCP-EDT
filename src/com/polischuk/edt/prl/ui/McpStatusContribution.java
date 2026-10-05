/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.ui;

import java.nio.file.Files;

import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.ui.menus.WorkbenchWindowControlContribution;

import com.polischuk.edt.prl.edt.WriteGate;
import com.polischuk.edt.prl.server.McpHttpServer;

/**
 * Індикатор стану сервера в нижньому тримі. Структура 1:1 з робочим
 * компонентом 1C Workmate (com.e1c.edt.ai.ui.BaseStatusBarControl) у цьому EDT:
 * Composite(GridLayout, marginWidth=2, marginHeight=-5, marginBottom=-5) +
 * Label-іконка (Image з прозорим фоном) + ТЕКСТОВИЙ Label зі шрифтом -2.
 * Від'ємні margin компенсують обрізання трим-рядка; текстовий Label фарбує тема.
 */
public class McpStatusContribution extends WorkbenchWindowControlContribution {

    private static final int REFRESH_MS = 5000;
    private static final String LABEL_TEXT = "MCP:PRL"; //$NON-NLS-1$
    private static final int DOT_IMG = 10;

    private Image greenImage;
    private Image redImage;
    private Font font;

    public McpStatusContribution() {
        super("com.polischuk.edt.prl.status"); //$NON-NLS-1$
    }

    @Override
    public boolean isDynamic() {
        return true;
    }

    @Override
    protected Control createControl(Composite parent) {
        Composite container = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(2, false);
        layout.marginWidth = 2;
        layout.marginHeight = -5;
        layout.marginBottom = -5;
        container.setLayout(layout);

        greenImage = createCircleImage(parent.getDisplay(), new RGB(50, 205, 50));
        redImage = createCircleImage(parent.getDisplay(), new RGB(205, 50, 50));

        Label icon = new Label(container, SWT.NONE);
        icon.setImage(redImage);
        icon.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, true));

        Label text = new Label(container, SWT.NONE);
        text.setText(LABEL_TEXT);
        FontData[] fd = text.getFont().getFontData();
        for (FontData f : fd) {
            f.setHeight(Math.max(7, f.getHeight() - 2));
        }
        font = new Font(text.getFont().getDevice(), fd);
        text.setFont(font);
        text.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, true));

        Menu menu = buildMenu(container);
        MouseAdapter opener = new MouseAdapter() {
            @Override
            public void mouseDown(MouseEvent e) {
                updateMenuState(menu);
                menu.setVisible(true);
            }
        };
        icon.addMouseListener(opener);
        text.addMouseListener(opener);

        container.addDisposeListener(e -> {
            if (greenImage != null) {
                greenImage.dispose();
                greenImage = null;
            }
            if (redImage != null) {
                redImage.dispose();
                redImage = null;
            }
            if (font != null) {
                font.dispose();
                font = null;
            }
        });

        Runnable refresh = new Runnable() {
            @Override
            public void run() {
                if (icon.isDisposed() || text.isDisposed()) {
                    return;
                }
                int port = McpHttpServer.runningPort();
                boolean up = port > 0;
                icon.setImage(up ? greenImage : redImage);
                String tooltip = up
                        ? UiMessages.get("status.up", endpoint(port)) //$NON-NLS-1$
                        : UiMessages.get("status.down"); //$NON-NLS-1$
                icon.setToolTipText(tooltip);
                text.setToolTipText(tooltip);
                icon.getDisplay().timerExec(REFRESH_MS, this);
            }
        };
        refresh.run();
        return container;
    }

    /** Кружечок з прозорим фоном — щоб не конфліктувати з фоном теми. */
    private static Image createCircleImage(Display display, RGB color) {
        PaletteData palette = new PaletteData(0xFF0000, 0xFF00, 0xFF);
        ImageData data = new ImageData(DOT_IMG, DOT_IMG, 24, palette);
        RGB transparent = new RGB(255, 0, 255);
        data.transparentPixel = palette.getPixel(transparent);
        int c = DOT_IMG / 2;
        int r = DOT_IMG / 2 - 1;
        for (int y = 0; y < DOT_IMG; y++) {
            for (int x = 0; x < DOT_IMG; x++) {
                int dx = x - c;
                int dy = y - c;
                data.setPixel(x, y, palette.getPixel(dx * dx + dy * dy <= r * r ? color : transparent));
            }
        }
        return new Image(display, data);
    }

    private Menu buildMenu(Control anchor) {
        Menu menu = new Menu(anchor);
        MenuItem start = item(menu, UiMessages.get("menu.start"), McpHttpServer::startInstance); //$NON-NLS-1$
        start.setData("start"); //$NON-NLS-1$
        MenuItem restart = item(menu, UiMessages.get("menu.restart"), McpHttpServer::restartInstance); //$NON-NLS-1$
        restart.setData("restart"); //$NON-NLS-1$
        MenuItem stop = item(menu, UiMessages.get("menu.stop"), McpHttpServer::stopInstance); //$NON-NLS-1$
        stop.setData("stop"); //$NON-NLS-1$

        new MenuItem(menu, SWT.SEPARATOR);

        MenuItem write = new MenuItem(menu, SWT.CHECK);
        write.setText(UiMessages.get("menu.write")); //$NON-NLS-1$
        write.setData("write"); //$NON-NLS-1$
        write.addListener(SWT.Selection, e -> {
            try {
                if (write.getSelection()) {
                    Files.createDirectories(WriteGate.FLAG.getParent());
                    if (!Files.exists(WriteGate.FLAG)) {
                        Files.createFile(WriteGate.FLAG);
                    }
                } else {
                    Files.deleteIfExists(WriteGate.FLAG);
                }
            } catch (Exception ex) {
                // без діалогів у тримі
            }
        });

        new MenuItem(menu, SWT.SEPARATOR);

        MenuItem copy = item(menu, UiMessages.get("menu.copy"), () -> { //$NON-NLS-1$
            int port = McpHttpServer.runningPort();
            if (port > 0) {
                Clipboard clipboard = new Clipboard(menu.getDisplay());
                clipboard.setContents(new Object[] {endpoint(port)},
                        new Transfer[] {TextTransfer.getInstance()});
                clipboard.dispose();
            }
        });
        copy.setData("copy"); //$NON-NLS-1$

        return menu;
    }

    private static MenuItem item(Menu menu, String label, Runnable action) {
        MenuItem item = new MenuItem(menu, SWT.PUSH);
        item.setText(label);
        item.addListener(SWT.Selection, e -> action.run());
        return item;
    }

    private static void updateMenuState(Menu menu) {
        boolean up = McpHttpServer.runningPort() > 0;
        for (MenuItem item : menu.getItems()) {
            Object tag = item.getData();
            if ("start".equals(tag)) { //$NON-NLS-1$
                item.setEnabled(!up);
            } else if ("restart".equals(tag) || "stop".equals(tag) || "copy".equals(tag)) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                item.setEnabled(up);
            } else if ("write".equals(tag)) { //$NON-NLS-1$
                item.setSelection(WriteGate.isEnabled());
            }
        }
    }

    private static String endpoint(int port) {
        return "http://127.0.0.1:" + port + "/mcp"; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
