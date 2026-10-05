/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.write;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.polischuk.edt.prl.edt.MetadataIndex;
import com.polischuk.edt.prl.edt.Types;
import com.polischuk.edt.prl.edt.V8Access;

import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;

/**
 * Автозаимствование ссылочных типів у розширенні: якщо в аргументах edit_metadata є типи
 * на кшталт СправочникСсылка.X, а об'єкта X ще немає в розширенні, але він є в базовій
 * конфігурації — X заимствується штатним сервісом EDT ДО основної транзакції (adopt відкриває
 * власну BM-транзакцію, тому всередині нашої він неможливий). Об'єкти, яких немає й у базі
 * (власні об'єкти розширення, у т.ч. створювані в тому ж batch), пропускаються.
 */
final class ExtensionTypeAdopt {

    private ExtensionTypeAdopt() {
    }

    /**
     * Повертає null, якщо проєкт не розширення чи нема що заимствовать; інакше
     * {adopted:[...]} або (dryRun) {wouldAdopt:[...]}.
     */
    static JsonObject prepare(IProject project, JsonObject arguments, boolean dryRun) {
        IExtensionProject extension = findExtension(project);
        if (extension == null) {
            return null;
        }
        Set<String> referenced = new LinkedHashSet<>();
        collect(arguments, referenced);
        if (referenced.isEmpty()) {
            return null;
        }
        EObject extensionConfiguration = extension.getConfiguration();
        IConfigurationProject parent = extension.getParent();
        EObject baseConfiguration = parent == null ? null : parent.getConfiguration();
        if (extensionConfiguration == null || baseConfiguration == null) {
            JsonObject reason = new JsonObject();
            reason.addProperty("skippedReason", "конфігурація розширення чи базова ще не завантажена в модель"); //$NON-NLS-1$ //$NON-NLS-2$
            return reason;
        }
        JsonArray adopted = new JsonArray();
        JsonArray skipped = new JsonArray();
        JsonArray details = new JsonArray();
        for (String reference : referenced) {
            String[] parts = reference.split("\\|", 2); //$NON-NLS-1$
            String kind = parts[0];
            String name = parts[1];
            boolean inExtension = exists(extensionConfiguration, kind, name);
            details.add(kind + "." + name + " inExtension=" + inExtension + " inBase=" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + exists(baseConfiguration, kind, name) + " extCfg=" + extensionConfiguration.eClass().getName() //$NON-NLS-1$
                    + " collSize=" + sizeOf(extensionConfiguration, kind)); //$NON-NLS-1$
            if (inExtension) {
                continue;
            }
            if (!exists(baseConfiguration, kind, name)) {
                skipped.add(kind + "." + name); //$NON-NLS-1$
                continue;
            }
            if (!dryRun) {
                JsonObject adoptArguments = new JsonObject();
                adoptArguments.addProperty("project", project.getName()); //$NON-NLS-1$
                adoptArguments.addProperty("kind", kind); //$NON-NLS-1$
                adoptArguments.addProperty("name", name); //$NON-NLS-1$
                ExtensionOps.adoptObject(adoptArguments);
                awaitProducedTypes(project, kind, name);
            }
            adopted.add(kind + "." + name); //$NON-NLS-1$
        }
        if (adopted.size() == 0 && skipped.size() == 0) {
            JsonObject checked = new JsonObject();
            checked.addProperty("checked", referenced.size()); //$NON-NLS-1$
            checked.addProperty("note", "усі ссылочные типи вже є в розширенні"); //$NON-NLS-1$ //$NON-NLS-2$
            checked.add("details", details); //$NON-NLS-1$
            return checked;
        }
        JsonObject result = new JsonObject();
        result.add(dryRun ? "wouldAdopt" : "adopted", adopted); //$NON-NLS-1$ //$NON-NLS-2$
        if (skipped.size() > 0) {
            result.add("notInBase", skipped); //$NON-NLS-1$
        }
        return result;
    }

    /** Після adopt згенеровані типи обчислюються асинхронно — чекаємо (до ~40 с), поки refType стане доступним. */
    private static void awaitProducedTypes(IProject project, String kind, String name) {
        com._1c.g5.v8.bm.integration.IBmModel model = com.polischuk.edt.prl.edt.EdtServices
                .require(com._1c.g5.v8.dt.core.platform.IBmModelManager.class).getModel(project);
        try {
            model.waitAllEnqueuedEventsSent();
        } catch (RuntimeException ignored) {
            // продовжуємо з опитуванням
        }
        for (int attempt = 0; attempt < 40; attempt++) {
            Boolean ready = model.executeReadonlyTask(new com._1c.g5.v8.bm.integration.AbstractBmTask<Boolean>(
                    "MCP:PRL await produced types") { //$NON-NLS-1$
                @Override
                public Boolean execute(com._1c.g5.v8.bm.core.IBmTransaction transaction,
                        org.eclipse.core.runtime.IProgressMonitor monitor) {
                    return Boolean.valueOf(Types.producedRefTypeReady(transaction, kind, name));
                }
            });
            if (Boolean.TRUE.equals(ready)) {
                return;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static int sizeOf(EObject configuration, String kind) {
        List<?> collection = MetadataIndex.findCollection(configuration, kind);
        return collection == null ? -1 : collection.size();
    }

    private static boolean exists(EObject configuration, String kind, String name) {
        List<?> collection = MetadataIndex.findCollection(configuration, kind);
        if (collection == null) {
            return false;
        }
        for (Object item : collection) {
            if (item instanceof EObject object && name.equalsIgnoreCase(com.polischuk.edt.prl.edt.Emf.name(object))) {
                return true;
            }
        }
        return false;
    }

    private static IExtensionProject findExtension(IProject project) {
        for (IExtensionProject extension : V8Access.projectManager().getProjects(IExtensionProject.class)) {
            if (extension.getProject().getName().equals(project.getName())) {
                return extension;
            }
        }
        return null;
    }

    /** Обходить JSON аргументів: масиви "types", рядки "type"; збирає "Kind|Name". */
    private static void collect(JsonElement element, Set<String> out) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(child -> collect(child, out));
        } else if (element.isJsonObject()) {
            for (var entry : element.getAsJsonObject().entrySet()) {
                String key = entry.getKey();
                JsonElement value = entry.getValue();
                if (("types".equals(key) || "type".equals(key) || "valueType".equals(key)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        && (value.isJsonArray() || value.isJsonPrimitive())) {
                    if (value.isJsonArray()) {
                        for (JsonElement item : value.getAsJsonArray()) {
                            if (item.isJsonPrimitive()) {
                                addType(item.getAsString(), out);
                            }
                        }
                    } else {
                        addType(value.getAsString(), out);
                    }
                } else {
                    collect(value, out);
                }
            }
        }
    }

    private static void addType(String raw, Set<String> out) {
        String[] kindAndName = Types.refKindAndName(raw);
        if (kindAndName != null) {
            out.add(kindAndName[0] + "|" + kindAndName[1]); //$NON-NLS-1$
        }
    }
}
