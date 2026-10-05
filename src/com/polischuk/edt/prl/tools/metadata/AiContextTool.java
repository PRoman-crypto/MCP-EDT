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
import org.eclipse.emf.ecore.EObject;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.polischuk.edt.prl.edt.MetadataIndex;
import com.polischuk.edt.prl.edt.V8Access;
import com.polischuk.edt.prl.edt.WorkspaceFiles;
import com.polischuk.edt.prl.tools.McpTool;
import com.polischuk.edt.prl.tools.code.BslMethodJson;
import com.polischuk.edt.prl.tools.code.BslOutline;

import com._1c.g5.v8.dt.core.platform.IV8Project;

/**
 * Агрегований контекст об'єкта метаданих для AI: структура + модулі з методами
 * одним викликом (замість 5-7 окремих запитів).
 */
public final class AiContextTool implements McpTool {

    private static final int MAX_MODULES = 12;

    @Override
    public String name() {
        return "ai_context"; //$NON-NLS-1$
    }

    @Override
    public String description() {
        return "Зведений контекст одним викликом. Без form — об'єкт метаданих: структура " //$NON-NLS-1$
                + "(реквізити, ТЧ, форми) + модулі об'єкта з методами. З form — конкретна форма: " //$NON-NLS-1$
                + "дерево елементів, реквізити, команди і модуль форми. " //$NON-NLS-1$
                + "detail: minimal (тільки структура) | standard (+ експортні методи) | full (усі методи)."; //$NON-NLS-1$
    }

    @Override
    public JsonObject inputSchema() {
        return JsonParser.parseString("""
                {"type":"object","properties":{
                  "project":{"type":"string","description":"Ім'я проєкту EDT (необов'язково, якщо проєкт один)"},
                  "kind":{"type":"string","description":"Вид метаданих (Catalog, Document, Справочник…)"},
                  "name":{"type":"string","description":"Ім'я об'єкта"},
                  "form":{"type":"string","description":"Ім'я форми об'єкта: контекст збирається по формі, а не по об'єкту"},
                  "detail":{"type":"string","enum":["minimal","standard","full"],"default":"standard"}
                },"required":["kind","name"]}""").getAsJsonObject(); //$NON-NLS-1$
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
        String projectName = arguments.has("project") ? arguments.get("project").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$
        String detail = arguments.has("detail") ? arguments.get("detail").getAsString() : "standard"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String form = arguments.has("form") && !arguments.get("form").getAsString().isBlank() //$NON-NLS-1$ //$NON-NLS-2$
                ? arguments.get("form").getAsString() : null; //$NON-NLS-1$
        boolean full = "full".equals(detail); //$NON-NLS-1$

        IV8Project v8Project = V8Access.resolveProject(projectName);
        EObject configuration = V8Access.configuration(v8Project);
        if (configuration == null) {
            throw new IllegalArgumentException("Проєкт не містить конфігурації"); //$NON-NLS-1$
        }
        String folder = MetadataIndex.objectFolder(configuration,
                arguments.get("kind").getAsString(), arguments.get("name").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        IProject project = v8Project.getProject();

        JsonObject result = new JsonObject();
        if (form != null) {
            return formContext(result, arguments, project, folder, form, detail, full);
        }

        result.add("structure", new GetObjectDetailsTool().execute(arguments)); //$NON-NLS-1$
        if ("minimal".equals(detail)) { //$NON-NLS-1$
            return result;
        }

        IFolder objectFolder = project.getFolder(folder);
        JsonArray modules = new JsonArray();
        if (objectFolder.exists()) {
            WorkspaceFiles.walk(objectFolder, "bsl", file -> { //$NON-NLS-1$
                if (modules.size() < MAX_MODULES) {
                    modules.add(moduleContext(file.getProjectRelativePath().toString(),
                            WorkspaceFiles.read(file), full));
                }
            });
        }
        result.add("modules", modules); //$NON-NLS-1$
        result.addProperty("hint", //$NON-NLS-1$
                "Тіло методу — read_method_source; де визначено — code_search із symbol; " //$NON-NLS-1$
                + "контекст конкретної форми — цей самий виклик із параметром form."); //$NON-NLS-1$
        return result;
    }

    /**
     * Контекст форми: дерево елементів і реквізитів (те саме, що get_form_image
     * format=structure) плюс модуль форми з методами. Без цього агент по формі мусив
     * робити три виклики й однаково не бачив обробників.
     */
    private JsonElement formContext(JsonObject result, JsonObject arguments, IProject project,
            String folder, String form, String detail, boolean full) throws Exception {
        JsonObject formArguments = arguments.deepCopy();
        formArguments.remove("detail"); //$NON-NLS-1$
        formArguments.addProperty("format", "structure"); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("target", "form"); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("form", form); //$NON-NLS-1$
        result.add("formStructure", new GetFormImageTool().execute(formArguments)); //$NON-NLS-1$
        if ("minimal".equals(detail)) { //$NON-NLS-1$
            return result;
        }

        String modulePath = folder + "/Forms/" + form + "/Module.bsl"; //$NON-NLS-1$ //$NON-NLS-2$
        IFile moduleFile = project.getFile(modulePath);
        if (moduleFile.exists()) {
            result.add("module", moduleContext(modulePath, WorkspaceFiles.read(moduleFile), full)); //$NON-NLS-1$
        } else {
            result.addProperty("module", (String) null); //$NON-NLS-1$
            result.addProperty("moduleHint", "У форми немає модуля: " + modulePath); //$NON-NLS-1$ //$NON-NLS-2$
        }
        result.addProperty("hint", //$NON-NLS-1$
                "Тіло обробника — read_method_source за path модуля форми; " //$NON-NLS-1$
                + "контекст об'єкта-власника — цей самий виклик без параметра form."); //$NON-NLS-1$
        return result;
    }

    /** Модуль зі структурою методів: сигнатури, параметри, doc-коментарі, області. */
    private static JsonObject moduleContext(String path, String source, boolean full) {
        JsonObject module = new JsonObject();
        module.addProperty("path", path); //$NON-NLS-1$
        BslOutline outline = BslOutline.parse(source);
        module.addProperty("totalLines", outline.totalLines); //$NON-NLS-1$
        module.addProperty("methodCount", outline.methods.size()); //$NON-NLS-1$

        JsonArray methods = new JsonArray();
        for (BslOutline.Method method : outline.methods) {
            if (!full && !method.export) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("name", method.name); //$NON-NLS-1$
            entry.addProperty("kind", method.kind); //$NON-NLS-1$
            entry.addProperty("export", method.export); //$NON-NLS-1$
            entry.addProperty("signature", method.signature); //$NON-NLS-1$
            if (!method.directives.isEmpty()) {
                entry.addProperty("directive", String.join(" ", method.directives)); //$NON-NLS-1$ //$NON-NLS-2$
            }
            entry.addProperty("startLine", BslOutline.startLineWithDirectives(method)); //$NON-NLS-1$
            entry.addProperty("endLine", method.endLine); //$NON-NLS-1$
            BslMethodJson.describe(entry, method);
            methods.add(entry);
        }
        module.add(full ? "methods" : "exportMethods", methods); //$NON-NLS-1$ //$NON-NLS-2$
        // У модулі форми експортних методів зазвичай немає — обробники не експортні,
        // тож без full агент побачив би порожній список і нічого не зрозумів.
        if (!full && methods.isEmpty() && outline.methods.size() > 0) {
            module.addProperty("hint", "Експортних методів немає (" + outline.methods.size() //$NON-NLS-1$ //$NON-NLS-2$
                    + " неекспортних) — передайте detail=full, щоб побачити всі."); //$NON-NLS-1$
        }
        return module;
    }
}
