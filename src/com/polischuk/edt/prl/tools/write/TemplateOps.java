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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com.google.gson.JsonObject;
import com.polischuk.edt.prl.edt.EdtServices;
import com.polischuk.edt.prl.edt.Emf;
import com.polischuk.edt.prl.edt.KindRegistry;
import com.polischuk.edt.prl.edt.MetadataIndex;
import com.polischuk.edt.prl.edt.V8Access;
import com.polischuk.edt.prl.edt.WriteGate;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IV8Project;

/**
 * Створення і наповнення макетів: реєстрація Template у метаданих об'єкта через
 * BM-транзакцію + запис файлу макета, згенерованого з JSON-специфікації.
 * Формати: табличний документ (Template.mxlx, MxlBuilder) і схема компоновки
 * даних (Template.dcs, SkdBuilder) — за параметром templateFormat.
 */
public final class TemplateOps {

    private TemplateOps() {
    }

    /** createTemplate: реєструє макет в об'єкті і пише Template.mxlx зі spec (або порожній). */
    public static JsonObject createTemplate(JsonObject arguments) throws Exception {
        WriteGate.check();

        String projectName = optional(arguments, "project"); //$NON-NLS-1$
        String kind = required(arguments, "kind"); //$NON-NLS-1$
        String name = required(arguments, "name"); //$NON-NLS-1$
        String template = required(arguments, "template"); //$NON-NLS-1$
        boolean dryRun = arguments.has("dryRun") && arguments.get("dryRun").getAsBoolean(); //$NON-NLS-1$ //$NON-NLS-2$

        IV8Project v8Project = V8Access.resolveProject(projectName);
        IProject project = v8Project.getProject();
        EObject configuration = V8Access.configuration(v8Project);
        String objectFolder = MetadataIndex.objectFolder(configuration, kind, name);

        // формат макета: spreadsheet (табличний документ, типово) або dcs (схема компоновки)
        String format = arguments.has("templateFormat") //$NON-NLS-1$
                ? arguments.get("templateFormat").getAsString() : "spreadsheet"; //$NON-NLS-1$ //$NON-NLS-2$
        boolean dcs = "dcs".equalsIgnoreCase(format) || "DataCompositionSchema".equalsIgnoreCase(format); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject spec = arguments.has("spec") && arguments.get("spec").isJsonObject() //$NON-NLS-1$ //$NON-NLS-2$
                ? arguments.getAsJsonObject("spec") //$NON-NLS-1$
                : new JsonObject();
        String xml = dcs ? SkdBuilder.buildDcs(spec) : MxlBuilder.buildMxlx(spec);
        String fileName = dcs ? "Template.dcs" : "Template.mxlx"; //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject result = new JsonObject();
        result.addProperty("operation", "createTemplate"); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("owner", objectFolder); //$NON-NLS-1$
        result.addProperty("template", template); //$NON-NLS-1$
        result.addProperty("dryRun", dryRun); //$NON-NLS-1$
        result.addProperty("applied", !dryRun); //$NON-NLS-1$

        if (dryRun) {
            result.addProperty("xmlPreview", xml.length() > 4000 ? xml.substring(0, 4000) + "…" : xml); //$NON-NLS-1$ //$NON-NLS-2$
            result.addProperty("xmlLength", xml.length()); //$NON-NLS-1$
            return result;
        }

        // 1) реєстрація макета в моделі метаданих
        IBmModel model = EdtServices.require(IBmModelManager.class).getModel(project);
        String fqn = KindRegistry.canonical(kind) + "." + name; //$NON-NLS-1$
        String lang = arguments.has("lang") ? arguments.get("lang").getAsString() : "ru"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String synonym = optional(arguments, "synonym"); //$NON-NLS-1$

        JsonObject registration = model.getGlobalContext().execute(
                new AbstractBmTask<JsonObject>("MCP:PRL createTemplate") { //$NON-NLS-1$
                    @Override
                    @SuppressWarnings("unchecked")
                    public JsonObject execute(IBmTransaction transaction, IProgressMonitor monitor) {
                        IBmObject bmObject = transaction.getTopObjectByFqn(fqn);
                        if (bmObject == null) {
                            throw new IllegalArgumentException("Об'єкт не знайдено за FQN: " + fqn); //$NON-NLS-1$
                        }
                        EObject owner = bmObject;
                        EStructuralFeature feature = owner.eClass().getEStructuralFeature("templates"); //$NON-NLS-1$
                        if (!(feature instanceof EReference reference) || !reference.isMany()) {
                            throw new IllegalArgumentException("Об'єкт " + owner.eClass().getName() //$NON-NLS-1$
                                    + " не підтримує макети"); //$NON-NLS-1$
                        }
                        List<EObject> templates = (List<EObject>) owner.eGet(reference);
                        for (EObject existing : templates) {
                            if (template.equalsIgnoreCase(Emf.name(existing))) {
                                throw new IllegalArgumentException("Макет уже існує: " + template); //$NON-NLS-1$
                            }
                        }
                        EObject child = EcoreUtil.create(reference.getEReferenceType());
                        setIfPresent(child, "name", template); //$NON-NLS-1$
                        EStructuralFeature uuid = child.eClass().getEStructuralFeature("uuid"); //$NON-NLS-1$
                        if (uuid != null) {
                            child.eSet(uuid, UUID.randomUUID());
                        }
                        if (synonym != null && !synonym.isBlank()
                                && Emf.get(child, "synonym") instanceof EMap) { //$NON-NLS-1$
                            ((EMap<String, String>) Emf.get(child, "synonym")).put(lang, synonym); //$NON-NLS-1$
                        }
                        // тип макета — табличний документ (енумератор TemplateType, літерал SPREADSHEET_DOCUMENT)
                        EStructuralFeature typeFeature = child.eClass().getEStructuralFeature("templateType"); //$NON-NLS-1$
                        if (typeFeature instanceof org.eclipse.emf.ecore.EAttribute attribute
                                && attribute.getEAttributeType() instanceof org.eclipse.emf.ecore.EEnum enumType) {
                            String[] candidates = dcs
                                    ? new String[] {"DataCompositionSchema", "DATA_COMPOSITION_SCHEMA"} //$NON-NLS-1$ //$NON-NLS-2$
                                    : new String[] {"SpreadsheetDocument", "SPREADSHEET_DOCUMENT"}; //$NON-NLS-1$ //$NON-NLS-2$
                            org.eclipse.emf.ecore.EEnumLiteral literal = null;
                            for (String candidate : candidates) {
                                literal = enumType.getEEnumLiteral(candidate);
                                if (literal != null) {
                                    break;
                                }
                            }
                            if (literal != null) {
                                child.eSet(attribute, literal.getInstance());
                            }
                        }
                        templates.add(child);
                        JsonObject change = new JsonObject();
                        change.addProperty("registered", template); //$NON-NLS-1$
                        change.addProperty("class", child.eClass().getName()); //$NON-NLS-1$
                        return change;
                    }
                });
        result.add("metadata", registration); //$NON-NLS-1$

        // 2) файл макета
        String templateFolderPath = objectFolder.contains("/CommonTemplates/") //$NON-NLS-1$
                ? objectFolder
                : objectFolder + "/Templates/" + template; //$NON-NLS-1$
        IFolder folder = project.getFolder(templateFolderPath);
        mkdirs(folder);
        IFile file = folder.getFile(fileName);
        byte[] body = xml.getBytes(StandardCharsets.UTF_8);
        if (file.exists()) {
            file.setContents(new ByteArrayInputStream(body), IResource.KEEP_HISTORY, null);
        } else {
            file.create(new ByteArrayInputStream(body), true, null);
        }
        result.addProperty("file", file.getProjectRelativePath().toString()); //$NON-NLS-1$
        result.addProperty("xmlLength", xml.length()); //$NON-NLS-1$
        result.addProperty("hint", //$NON-NLS-1$
                "Перевірте макет через get_mxl; помилки моделі — get_validation_errors."); //$NON-NLS-1$
        return result;
    }

    /** setTemplateContent: перезаписує вміст існуючого макета зі spec. */
    public static JsonObject setTemplateContent(JsonObject arguments) throws Exception {
        WriteGate.check();

        String projectName = optional(arguments, "project"); //$NON-NLS-1$
        String kind = required(arguments, "kind"); //$NON-NLS-1$
        String name = required(arguments, "name"); //$NON-NLS-1$
        String template = required(arguments, "template"); //$NON-NLS-1$
        boolean dryRun = arguments.has("dryRun") && arguments.get("dryRun").getAsBoolean(); //$NON-NLS-1$ //$NON-NLS-2$
        if (!arguments.has("spec") || !arguments.get("spec").isJsonObject()) { //$NON-NLS-1$ //$NON-NLS-2$
            throw new IllegalArgumentException("Потрібен параметр spec (опис макета)"); //$NON-NLS-1$
        }

        IV8Project v8Project = V8Access.resolveProject(projectName);
        IProject project = v8Project.getProject();
        EObject configuration = V8Access.configuration(v8Project);
        String objectFolder = MetadataIndex.objectFolder(configuration, kind, name);
        String templateFolderPath = objectFolder.contains("/CommonTemplates/") //$NON-NLS-1$
                ? objectFolder
                : objectFolder + "/Templates/" + template; //$NON-NLS-1$
        // формат визначаємо за наявним файлом макета
        IFolder templateFolder = project.getFolder(templateFolderPath);
        IFile mxlx = templateFolder.getFile("Template.mxlx"); //$NON-NLS-1$
        IFile dcsFile = templateFolder.getFile("Template.dcs"); //$NON-NLS-1$
        boolean dcs = !mxlx.exists() && dcsFile.exists();
        IFile file = dcs ? dcsFile : mxlx;
        if (!file.exists()) {
            throw new IllegalArgumentException("Макет не знайдено: " + templateFolderPath //$NON-NLS-1$
                    + " (Template.mxlx / Template.dcs). Створіть його через createTemplate."); //$NON-NLS-1$
        }

        JsonObject spec = arguments.getAsJsonObject("spec"); //$NON-NLS-1$
        String xml = dcs ? SkdBuilder.buildDcs(spec) : MxlBuilder.buildMxlx(spec);
        JsonObject result = new JsonObject();
        result.addProperty("operation", "setTemplateContent"); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("file", file.getProjectRelativePath().toString()); //$NON-NLS-1$
        result.addProperty("format", dcs ? "dcs" : "spreadsheet"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        result.addProperty("dryRun", dryRun); //$NON-NLS-1$
        result.addProperty("applied", !dryRun); //$NON-NLS-1$
        result.addProperty("xmlLength", xml.length()); //$NON-NLS-1$
        if (dryRun) {
            result.addProperty("xmlPreview", xml.length() > 4000 ? xml.substring(0, 4000) + "…" : xml); //$NON-NLS-1$ //$NON-NLS-2$
            return result;
        }
        file.setContents(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
                IResource.KEEP_HISTORY, null);
        return result;
    }

    /** setDataSetQuery: точкова заміна тексту запиту набору даних у схемі СКД. */
    public static JsonObject setDataSetQuery(JsonObject arguments) throws Exception {
        WriteGate.check();

        String projectName = optional(arguments, "project"); //$NON-NLS-1$
        String kind = required(arguments, "kind"); //$NON-NLS-1$
        String name = required(arguments, "name"); //$NON-NLS-1$
        String template = required(arguments, "template"); //$NON-NLS-1$
        String dataSet = required(arguments, "dataSet"); //$NON-NLS-1$
        String query = required(arguments, "query"); //$NON-NLS-1$
        boolean dryRun = arguments.has("dryRun") && arguments.get("dryRun").getAsBoolean(); //$NON-NLS-1$ //$NON-NLS-2$

        IV8Project v8Project = V8Access.resolveProject(projectName);
        IProject project = v8Project.getProject();
        EObject configuration = V8Access.configuration(v8Project);
        String objectFolder = MetadataIndex.objectFolder(configuration, kind, name);
        String templateFolderPath = objectFolder.contains("/CommonTemplates/") //$NON-NLS-1$
                ? objectFolder
                : objectFolder + "/Templates/" + template; //$NON-NLS-1$
        IFile file = project.getFolder(templateFolderPath).getFile("Template.dcs"); //$NON-NLS-1$
        if (!file.exists()) {
            throw new IllegalArgumentException("Схему СКД не знайдено: " + templateFolderPath //$NON-NLS-1$
                    + "/Template.dcs"); //$NON-NLS-1$
        }

        String updated = SkdBuilder.setDataSetQuery(
                com.polischuk.edt.prl.edt.WorkspaceFiles.read(file), dataSet, query);

        JsonObject result = new JsonObject();
        result.addProperty("operation", "setDataSetQuery"); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("file", file.getProjectRelativePath().toString()); //$NON-NLS-1$
        result.addProperty("dataSet", dataSet); //$NON-NLS-1$
        result.addProperty("dryRun", dryRun); //$NON-NLS-1$
        result.addProperty("applied", !dryRun); //$NON-NLS-1$
        if (dryRun) {
            result.addProperty("note", //$NON-NLS-1$
                    "Запит замінено у пам'яті; файл не змінено. Повторіть із dryRun:false."); //$NON-NLS-1$
            return result;
        }
        file.setContents(new ByteArrayInputStream(updated.getBytes(StandardCharsets.UTF_8)),
                IResource.KEEP_HISTORY, null);
        result.addProperty("hint", //$NON-NLS-1$
                "Поля набору могли змінитись — перевірте get_skd і за потреби перегенеруйте схему."); //$NON-NLS-1$
        return result;
    }

    private static void setIfPresent(EObject object, String featureName, Object value) {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (feature != null) {
            object.eSet(feature, value);
        }
    }

    private static void mkdirs(IFolder folder) throws org.eclipse.core.runtime.CoreException {
        if (folder.exists()) {
            return;
        }
        if (folder.getParent() instanceof IFolder parent) {
            mkdirs(parent);
        }
        folder.create(true, true, null);
    }

    private static String required(JsonObject arguments, String key) {
        if (!arguments.has(key) || arguments.get(key).getAsString().isBlank()) {
            throw new IllegalArgumentException("Обов'язковий параметр відсутній: " + key); //$NON-NLS-1$
        }
        return arguments.get(key).getAsString();
    }

    private static String optional(JsonObject arguments, String key) {
        return arguments.has(key) && !arguments.get(key).isJsonNull()
                ? arguments.get(key).getAsString() : null;
    }
}
