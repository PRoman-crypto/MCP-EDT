/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.write;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.emf.ecore.EObject;

import com.google.gson.JsonObject;
import com.polischuk.edt.prl.edt.MetadataIndex;
import com.polischuk.edt.prl.edt.V8Access;
import com.polischuk.edt.prl.edt.WorkspaceFiles;
import com.polischuk.edt.prl.tools.code.BslOutline;

import com._1c.g5.v8.dt.core.platform.IV8Project;

/**
 * Композитна операція addFormHandler: команда форми + кнопка (FormOps.addFormCommand)
 * + каркас методу-обробника в модулі форми — все одним викликом. Найчастіший
 * сценарій доробки форми зводиться до однієї дії агента.
 */
public final class FormHandlerOps {

    private FormHandlerOps() {
    }

    public static JsonObject addFormHandler(JsonObject arguments) throws Exception {
        // 1) команда + кнопка (там же dryRun через executeAndRollback)
        JsonObject result = FormOps.addFormCommand(arguments);
        boolean dryRun = arguments.has("dryRun") && arguments.get("dryRun").getAsBoolean(); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject change = result.getAsJsonObject("change"); //$NON-NLS-1$
        String handler = change != null && change.has("handler") //$NON-NLS-1$
                ? change.get("handler").getAsString() //$NON-NLS-1$
                : arguments.get("command").getAsString(); //$NON-NLS-1$

        // 2) каркас обробника в модулі форми
        String projectName = arguments.has("project") ? arguments.get("project").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$
        IV8Project v8Project = V8Access.resolveProject(projectName);
        IProject project = v8Project.getProject();
        EObject configuration = V8Access.configuration(v8Project);
        String objectFolder = MetadataIndex.objectFolder(configuration,
                arguments.get("kind").getAsString(), arguments.get("name").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        boolean commonForm = objectFolder.contains("/CommonForms/"); //$NON-NLS-1$
        String modulePath = commonForm
                ? objectFolder + "/Module.bsl" //$NON-NLS-1$
                : objectFolder + "/Forms/" + arguments.get("form").getAsString() + "/Module.bsl"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        String methodText = "&НаКлиенте" + "\r\n" //$NON-NLS-1$ //$NON-NLS-2$
                + "Процедура " + handler + "(Команда)" + "\r\n" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "\t// TODO: реалізуйте обробник команди" + "\r\n" //$NON-NLS-1$ //$NON-NLS-2$
                + "КонецПроцедуры"; //$NON-NLS-1$

        JsonObject module = new JsonObject();
        module.addProperty("path", modulePath); //$NON-NLS-1$
        if (dryRun) {
            module.addProperty("wouldAppend", methodText); //$NON-NLS-1$
        } else {
            IFile file = project.getFile(new org.eclipse.core.runtime.Path(modulePath));
            if (!file.exists()) {
                byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
                file.create(new ByteArrayInputStream(bom), true, null);
            }
            String source = WorkspaceFiles.read(file);
            if (BslOutline.parse(source).findMethod(handler) != null) {
                module.addProperty("handlerExists", true); //$NON-NLS-1$
            } else {
                String updated = source.isBlank()
                        ? methodText + "\r\n" //$NON-NLS-1$
                        : source.stripTrailing() + "\r\n\r\n" + methodText + "\r\n"; //$NON-NLS-1$ //$NON-NLS-2$
                Charset charset = Charset.forName(file.getCharset(true));
                byte[] body = updated.getBytes(charset);
                if (WorkspaceFiles.hasUtf8Bom(file)) {
                    byte[] withBom = new byte[body.length + 3];
                    withBom[0] = (byte) 0xEF;
                    withBom[1] = (byte) 0xBB;
                    withBom[2] = (byte) 0xBF;
                    System.arraycopy(body, 0, withBom, 3, body.length);
                    body = withBom;
                }
                file.setContents(new ByteArrayInputStream(body), IResource.KEEP_HISTORY, null);
                module.addProperty("handlerAppended", handler); //$NON-NLS-1$
            }
        }
        result.add("module", module); //$NON-NLS-1$
        result.addProperty("operation", "addFormHandler"); //$NON-NLS-1$ //$NON-NLS-2$
        return result;
    }
}
