/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.metadata;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.emf.ecore.EObject;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.polischuk.edt.prl.edt.MetadataIndex;
import com.polischuk.edt.prl.edt.V8Access;
import com.polischuk.edt.prl.edt.WorkspaceFiles;
import com.polischuk.edt.prl.tools.McpTool;

import com._1c.g5.v8.dt.core.platform.IV8Project;

/**
 * Макети об'єкта: список (без template) або вміст одного макета. Текстові формати
 * (.dcs СКД, .txt, .xml) віддаються текстом; бінарні (.mxl табличний документ) —
 * тільки метаінформація.
 */
public final class GetTemplateTool implements McpTool {

    private static final int MAX_TEXT = 100_000;

    @Override
    public String name() {
        return "get_template"; //$NON-NLS-1$
    }

    @Override
    public String description() {
        return "Макети об'єкта метаданих: без template — список макетів із типами; з template — вміст " //$NON-NLS-1$
                + "(тексти .dcs/.txt/.xml; для бінарних .mxl — лише метаінформація; структура СКД — get_skd)."; //$NON-NLS-1$
    }

    @Override
    public JsonObject inputSchema() {
        return JsonParser.parseString("""
                {"type":"object","properties":{
                  "project":{"type":"string","description":"Ім'я проєкту EDT (необов'язково, якщо проєкт один)"},
                  "kind":{"type":"string","description":"Вид власника (Report, DataProcessor, Справочник, CommonTemplate…)"},
                  "name":{"type":"string","description":"Ім'я об'єкта-власника (для CommonTemplate — ім'я макета)"},
                  "template":{"type":"string","description":"Ім'я макета; без нього — список"}
                },"required":["kind","name"]}""").getAsJsonObject(); //$NON-NLS-1$
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
        String projectName = arguments.has("project") ? arguments.get("project").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$
        String kind = arguments.get("kind").getAsString(); //$NON-NLS-1$
        String name = arguments.get("name").getAsString(); //$NON-NLS-1$
        String template = arguments.has("template") && !arguments.get("template").getAsString().isBlank() //$NON-NLS-1$ //$NON-NLS-2$
                ? arguments.get("template").getAsString() : null; //$NON-NLS-1$

        IV8Project v8Project = V8Access.resolveProject(projectName);
        EObject configuration = V8Access.configuration(v8Project);
        IProject project = v8Project.getProject();
        String objectFolder = MetadataIndex.objectFolder(configuration, kind, name);

        // загальний макет — сам є макетом: файли лежать одразу в його теці
        boolean commonTemplate = objectFolder.contains("/CommonTemplates/"); //$NON-NLS-1$
        IFolder root = project.getFolder(commonTemplate ? objectFolder : objectFolder + "/Templates"); //$NON-NLS-1$

        JsonObject result = new JsonObject();
        result.addProperty("owner", objectFolder); //$NON-NLS-1$
        if (!root.exists()) {
            result.addProperty("templateCount", 0); //$NON-NLS-1$
            result.add("templates", new JsonArray()); //$NON-NLS-1$
            return result;
        }

        if (template == null && !commonTemplate) {
            JsonArray templates = new JsonArray();
            for (IResource member : root.members()) {
                if (member instanceof IFolder folder) {
                    templates.add(describeTemplate(folder.getName(), folder));
                }
            }
            result.addProperty("templateCount", templates.size()); //$NON-NLS-1$
            result.add("templates", templates); //$NON-NLS-1$
            return result;
        }

        IFolder templateFolder = commonTemplate ? root : root.getFolder(template);
        if (!templateFolder.exists()) {
            throw new IllegalArgumentException("Макет не знайдено: " + template //$NON-NLS-1$
                    + ". Викличте без template, щоб побачити список."); //$NON-NLS-1$
        }
        JsonObject info = describeTemplate(commonTemplate ? name : template, templateFolder);
        // вміст текстових файлів
        for (IResource member : templateFolder.members()) {
            if (member instanceof IFile file && isTextExtension(file.getFileExtension())) {
                String text = WorkspaceFiles.read(file);
                if (text.length() > MAX_TEXT) {
                    text = text.substring(0, MAX_TEXT) + "\n… (обрізано, повний розмір " //$NON-NLS-1$
                            + text.length() + " символів)"; //$NON-NLS-1$
                }
                info.addProperty("content", text); //$NON-NLS-1$
                info.addProperty("contentFile", file.getName()); //$NON-NLS-1$
                break;
            }
        }
        return info;
    }

    private static JsonObject describeTemplate(String templateName, IFolder folder) throws Exception {
        JsonObject entry = new JsonObject();
        entry.addProperty("name", templateName); //$NON-NLS-1$
        JsonArray files = new JsonArray();
        String type = "Unknown"; //$NON-NLS-1$
        for (IResource member : folder.members()) {
            if (member instanceof IFile file) {
                JsonObject f = new JsonObject();
                f.addProperty("file", file.getName()); //$NON-NLS-1$
                long size = file.getLocation() == null ? -1 : file.getLocation().toFile().length();
                f.addProperty("sizeBytes", size); //$NON-NLS-1$
                files.add(f);
                String ext = file.getFileExtension();
                if ("dcs".equalsIgnoreCase(ext)) { //$NON-NLS-1$
                    type = "DataCompositionSchema"; //$NON-NLS-1$
                } else if ("mxl".equalsIgnoreCase(ext)) { //$NON-NLS-1$
                    type = "SpreadsheetDocument (binary)"; //$NON-NLS-1$
                } else if ("txt".equalsIgnoreCase(ext)) { //$NON-NLS-1$
                    type = "TextDocument"; //$NON-NLS-1$
                }
            }
        }
        entry.addProperty("type", type); //$NON-NLS-1$
        entry.add("files", files); //$NON-NLS-1$
        return entry;
    }

    private static boolean isTextExtension(String extension) {
        return "dcs".equalsIgnoreCase(extension) || "txt".equalsIgnoreCase(extension) //$NON-NLS-1$ //$NON-NLS-2$
                || "xml".equalsIgnoreCase(extension); //$NON-NLS-1$
    }
}
