/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.metadata;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.polischuk.edt.prl.edt.Emf;
import com.polischuk.edt.prl.edt.MetadataIndex;
import com.polischuk.edt.prl.edt.V8Access;
import com.polischuk.edt.prl.edt.WorkspaceFiles;
import com.polischuk.edt.prl.tools.McpTool;

import com._1c.g5.v8.dt.core.platform.IV8Project;

/** Вбудована довідка об'єкта метаданих (аналог кнопки «?») — HTML-файли Help у вихідниках. */
public final class GetObjectHelpTool implements McpTool {

    private static final int MAX_TEXT_LENGTH = 20000;

    @Override
    public String name() {
        return "get_object_help"; //$NON-NLS-1$
    }

    @Override
    public String description() {
        return "Вбудована довідка об'єкта метаданих і його форм (текст із HTML-сторінок Help "
                + "у вихідниках EDT). У кожній сторінці: scope=object|form, form — ім'я форми."; //$NON-NLS-1$
    }

    @Override
    public JsonObject inputSchema() {
        return JsonParser.parseString("""
                {"type":"object","properties":{
                  "project":{"type":"string","description":"Ім'я проєкту EDT (необов'язково, якщо проєкт один)"},
                  "kind":{"type":"string","description":"Вид метаданих (Catalog, Document, Справочник…)"},
                  "name":{"type":"string","description":"Ім'я об'єкта"}
                },"required":["kind","name"]}""").getAsJsonObject(); //$NON-NLS-1$
    }

    @Override
    public JsonElement execute(JsonObject arguments) {
        String projectName = arguments.has("project") ? arguments.get("project").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$
        String kind = arguments.get("kind").getAsString(); //$NON-NLS-1$
        String name = arguments.get("name").getAsString(); //$NON-NLS-1$

        IV8Project v8Project = V8Access.resolveProject(projectName);
        EObject configuration = V8Access.configuration(v8Project);
        if (configuration == null) {
            throw new IllegalArgumentException("Проєкт не містить конфігурації"); //$NON-NLS-1$
        }
        EObject object = MetadataIndex.findObject(configuration, kind, name);
        String folder = MetadataIndex.objectFolder(configuration, kind, name);

        IProject project = v8Project.getProject();
        JsonObject result = new JsonObject();
        result.addProperty("object", folder); //$NON-NLS-1$
        result.addProperty("kind", object.eClass().getName()); //$NON-NLS-1$
        result.addProperty("name", Emf.name(object)); //$NON-NLS-1$
        JsonObject synonym = Emf.synonym(object);
        if (synonym != null) {
            result.add("synonym", synonym); //$NON-NLS-1$
        }
        String comment = Emf.str(object, "comment"); //$NON-NLS-1$
        if (comment != null && !comment.isBlank()) {
            result.addProperty("comment", comment); //$NON-NLS-1$
        }

        // Довідка є і в самого об'єкта (<folder>/Help), і в кожної його форми
        // (<folder>/Forms/<Форма>/Help) — збираємо обидва рівні.
        IFolder objectFolder = project.getFolder(folder);
        List<IFile> pages = new ArrayList<>();
        if (objectFolder.exists()) {
            WorkspaceFiles.walk(objectFolder, "html", file -> collectHelpPage(file, pages)); //$NON-NLS-1$
            WorkspaceFiles.walk(objectFolder, "htm", file -> collectHelpPage(file, pages)); //$NON-NLS-1$
        }
        if (pages.isEmpty()) {
            result.addProperty("hasHelp", false); //$NON-NLS-1$
            result.addProperty("message", "Вбудована довідка для об'єкта та його форм відсутня."); //$NON-NLS-1$ //$NON-NLS-2$
            return result;
        }
        result.addProperty("hasHelp", true); //$NON-NLS-1$

        pages.sort((a, b) -> a.getProjectRelativePath().toString()
                .compareTo(b.getProjectRelativePath().toString()));
        JsonArray pagesJson = new JsonArray();
        for (IFile page : pages) {
            String path = page.getProjectRelativePath().toString();
            JsonObject entry = new JsonObject();
            entry.addProperty("path", path); //$NON-NLS-1$
            String form = formName(path);
            entry.addProperty("scope", form == null ? "object" : "form"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (form != null) {
                entry.addProperty("form", form); //$NON-NLS-1$
            }
            entry.addProperty("text", htmlToText(WorkspaceFiles.read(page))); //$NON-NLS-1$
            pagesJson.add(entry);
        }
        result.addProperty("pageCount", pagesJson.size()); //$NON-NLS-1$
        result.add("pages", pagesJson); //$NON-NLS-1$
        return result;
    }

    /** Сторінка довідки — html/htm усередині будь-якої теки Help об'єкта. */
    private static void collectHelpPage(IFile file, List<IFile> pages) {
        String path = file.getProjectRelativePath().toString().replace('\\', '/');
        if (path.contains("/Help/")) { //$NON-NLS-1$
            pages.add(file);
        }
    }

    /** Ім'я форми зі шляху .../Forms/<Форма>/Help/...; null — довідка самого об'єкта. */
    private static String formName(String path) {
        String normalized = path.replace('\\', '/');
        int formsAt = normalized.indexOf("/Forms/"); //$NON-NLS-1$
        if (formsAt < 0) {
            return null;
        }
        String tail = normalized.substring(formsAt + "/Forms/".length()); //$NON-NLS-1$
        int slash = tail.indexOf('/');
        return slash < 0 ? tail : tail.substring(0, slash);
    }

    private static String htmlToText(String html) {
        String text = html
                .replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ") //$NON-NLS-1$ //$NON-NLS-2$
                .replaceAll("(?i)<br\\s*/?>", "\n") //$NON-NLS-1$ //$NON-NLS-2$
                .replaceAll("(?i)</(p|div|h[1-6]|li|tr)>", "\n") //$NON-NLS-1$ //$NON-NLS-2$
                .replaceAll("<[^>]+>", " ") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                .replace("&quot;", "\"").replace("&amp;", "&") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                .replaceAll("[ \\t]+", " ") //$NON-NLS-1$ //$NON-NLS-2$
                .replaceAll("\\n\\s*\\n+", "\n\n") //$NON-NLS-1$ //$NON-NLS-2$
                .strip();
        return text.length() > MAX_TEXT_LENGTH ? text.substring(0, MAX_TEXT_LENGTH) + "…" : text; //$NON-NLS-1$
    }
}
