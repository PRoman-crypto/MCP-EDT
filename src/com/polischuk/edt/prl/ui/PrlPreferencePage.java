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

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.osgi.service.prefs.Preferences;

import com.polischuk.edt.prl.Activator;
import com.polischuk.edt.prl.edt.WriteGate;
import com.polischuk.edt.prl.server.McpHttpServer;

/**
 * Window → Preferences → MCP:PRL: статус сервера, порт (застосовується одразу —
 * сервер перезапускається без рестарту EDT) і дозвіл запису (файл-прапорець WriteGate).
 */
public class PrlPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    private static final String PORT_KEY = "port"; //$NON-NLS-1$
    private static final String[] LANG_CODES = {"auto", "en", "ru", "uk"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    private Label status;
    private Combo langCombo;
    private Text portField;
    private Button writeEnabled;

    public PrlPreferencePage() {
        super("MCP:PRL Server"); //$NON-NLS-1$
        noDefaultAndApplyButton();
    }

    @Override
    public void init(IWorkbench workbench) {
        // стан читається у createContents
    }

    @Override
    protected Control createContents(Composite parent) {
        Composite panel = new Composite(parent, SWT.NONE);
        panel.setLayout(new GridLayout(2, false));

        status = new Label(panel, SWT.NONE);
        status.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));
        refreshStatus();

        Label langLabel = new Label(panel, SWT.NONE);
        langLabel.setText(UiMessages.get("prefs.lang")); //$NON-NLS-1$
        langCombo = new Combo(panel, SWT.READ_ONLY | SWT.DROP_DOWN);
        langCombo.setItems(new String[] {
                UiMessages.get("prefs.lang.auto"), "English", "Русский", "Українська"}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        langCombo.select(currentLangIndex());
        Label langHint = new Label(panel, SWT.WRAP);
        langHint.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));
        langHint.setText(UiMessages.get("prefs.lang.hint")); //$NON-NLS-1$

        Label portLabel = new Label(panel, SWT.NONE);
        portLabel.setText(UiMessages.get("prefs.port")); //$NON-NLS-1$
        portField = new Text(panel, SWT.BORDER);
        GridData portData = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        portData.widthHint = 80;
        portField.setLayoutData(portData);
        portField.setText(String.valueOf(McpHttpServer.configuredBasePort()));
        portField.setToolTipText(UiMessages.get("prefs.port.tooltip")); //$NON-NLS-1$

        Label portHint = new Label(panel, SWT.WRAP);
        portHint.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));
        portHint.setText(UiMessages.get("prefs.port.hint")); //$NON-NLS-1$

        new Label(panel, SWT.NONE).setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));
        writeEnabled = new Button(panel, SWT.CHECK);
        writeEnabled.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));
        writeEnabled.setText(UiMessages.get("prefs.write")); //$NON-NLS-1$
        writeEnabled.setSelection(WriteGate.isEnabled());

        Label writeHint = new Label(panel, SWT.WRAP);
        writeHint.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));
        writeHint.setText(UiMessages.get("prefs.write.hint", WriteGate.FLAG)); //$NON-NLS-1$

        return panel;
    }

    private void refreshStatus() {
        int port = McpHttpServer.runningPort();
        status.setText(port > 0
                ? UiMessages.get("prefs.running", "http://127.0.0.1:" + port + "/mcp") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                : UiMessages.get("prefs.stopped")); //$NON-NLS-1$
    }

    private int currentLangIndex() {
        String choice = InstanceScope.INSTANCE.getNode(Activator.PLUGIN_ID)
                .get(UiMessages.LANG_PREF_KEY, LANG_CODES[0]);
        for (int i = 0; i < LANG_CODES.length; i++) {
            if (LANG_CODES[i].equals(choice)) {
                return i;
            }
        }
        return 0;
    }

    @Override
    public boolean performOk() {
        // мова інтерфейсу плагіна
        try {
            Preferences node = InstanceScope.INSTANCE.getNode(Activator.PLUGIN_ID);
            node.put(UiMessages.LANG_PREF_KEY, LANG_CODES[langCombo.getSelectionIndex()]);
            node.flush();
        } catch (Exception e) {
            setErrorMessage(e.getMessage());
            return false;
        }
        // порт
        int newPort;
        try {
            newPort = Integer.parseInt(portField.getText().strip());
        } catch (NumberFormatException e) {
            setErrorMessage(UiMessages.get("prefs.err.port")); //$NON-NLS-1$
            return false;
        }
        if (newPort < 1024 || newPort > 65535) {
            setErrorMessage(UiMessages.get("prefs.err.port")); //$NON-NLS-1$
            return false;
        }
        try {
            Preferences node = InstanceScope.INSTANCE.getNode(Activator.PLUGIN_ID);
            int oldPort = McpHttpServer.configuredBasePort();
            node.putInt(PORT_KEY, newPort);
            node.flush();
            if (newPort != oldPort) {
                McpHttpServer.restartInstance();
                refreshStatus();
            }
        } catch (Exception e) {
            setErrorMessage(UiMessages.get("prefs.err.save", e.getMessage())); //$NON-NLS-1$
            return false;
        }
        // дозвіл запису
        try {
            if (writeEnabled != null && !writeEnabled.isDisposed()) {
                if (writeEnabled.getSelection()) {
                    Files.createDirectories(WriteGate.FLAG.getParent());
                    if (!Files.exists(WriteGate.FLAG)) {
                        Files.createFile(WriteGate.FLAG);
                    }
                } else {
                    Files.deleteIfExists(WriteGate.FLAG);
                }
            }
        } catch (Exception e) {
            setErrorMessage(UiMessages.get("prefs.err.write", e.getMessage())); //$NON-NLS-1$
            return false;
        }
        return true;
    }
}
