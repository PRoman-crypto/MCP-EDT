/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.metadata;

import java.util.List;
import java.util.Set;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com.polischuk.edt.prl.edt.Emf;
import com.polischuk.edt.prl.edt.MetadataIndex;
import com.polischuk.edt.prl.edt.V8Access;
import com.polischuk.edt.prl.tools.McpTool;

/** Деталі об'єкта метаданих: властивості, реквізити, табличні частини, форми, макети, команди. */
public final class GetObjectDetailsTool implements McpTool {

    private static final int MAX_COLLECTION_ITEMS = 500;

    /** Колекції базового об'єкта, які показуємо для запозиченого об'єкта розширення. */
    private static final Set<String> BASE_COLLECTIONS = Set.of(
            "attributes", "tabularSections", "dimensions", "resources", "forms", "commands", "templates"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$

    /** Колекції, для елементів яких виводимо типи (реквізити/виміри/ресурси). */
    private static final Set<String> TYPED_COLLECTIONS = Set.of(
            "attributes", "dimensions", "resources", "addressingAttributes", "accountingFlags"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

    @Override
    public String name() {
        return "get_object_details"; //$NON-NLS-1$
    }

    @Override
    public String description() {
        return "Структура об'єкта метаданих: властивості, реквізити з типами, табличні частини, " //$NON-NLS-1$
                + "виміри/ресурси, форми, команди, макети. Параметри: kind (Catalog/Справочник…), name. "
                + "Для запозиченого об'єкта розширення додається baseObject — склад об'єкта базової конфігурації."; //$NON-NLS-1$
    }

    @Override
    public JsonObject inputSchema() {
        return JsonParser.parseString("""
                {"type":"object","properties":{
                  "project":{"type":"string","description":"Ім'я проєкту EDT (необов'язково, якщо проєкт один)"},
                  "kind":{"type":"string","description":"Вид метаданих (Catalog, Document, Справочник…)"},
                  "name":{"type":"string","description":"Ім'я об'єкта, напр. Номенклатура"}
                },"required":["kind","name"]}""").getAsJsonObject(); //$NON-NLS-1$
    }

    @Override
    public JsonElement execute(JsonObject arguments) {
        String projectName = arguments.has("project") ? arguments.get("project").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$
        String kind = required(arguments, "kind"); //$NON-NLS-1$
        String name = required(arguments, "name"); //$NON-NLS-1$

        IV8Project v8Project = V8Access.resolveProject(projectName);
        EObject configuration = V8Access.configuration(v8Project);
        if (configuration == null) {
            throw new IllegalArgumentException("Проєкт '" + v8Project.getProject().getName() //$NON-NLS-1$
                    + "' не є конфігурацією або розширенням"); //$NON-NLS-1$
        }
        EObject object = findObject(configuration, kind, name);

        JsonObject result = new JsonObject();
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
        result.add("properties", Emf.attributes(object, true)); //$NON-NLS-1$

        for (EReference reference : object.eClass().getEAllReferences()) {
            if (!reference.isMany()) {
                continue;
            }
            Object value = object.eGet(reference);
            if (!(value instanceof List<?> list) || list.isEmpty()) {
                continue;
            }
            if (!(list.get(0) instanceof EObject first) || Emf.name(first) == null) {
                continue;
            }
            boolean typed = TYPED_COLLECTIONS.contains(reference.getName());
            boolean tabular = "tabularSections".equals(reference.getName()); //$NON-NLS-1$
            JsonArray items = new JsonArray();
            for (Object element : list) {
                if (items.size() >= MAX_COLLECTION_ITEMS) {
                    break;
                }
                if (element instanceof EObject item) {
                    items.add(tabular ? tabularSection(item) : entry(item, typed));
                }
            }
            result.add(reference.getName(), items);
        }

        // Компактна карта схем компоновки — щоб не тягнути весь get_skd заради того,
        // щоб дізнатись, чи є в об'єкта СКД і що в ній.
        JsonArray dcs = DcsSummary.forObject(v8Project.getProject(),
                MetadataIndex.objectFolder(configuration, kind, name));
        if (dcs != null) {
            result.add("dcsTemplates", dcs); //$NON-NLS-1$
            result.addProperty("dcsHint", //$NON-NLS-1$
                    "Повна схема (тексти запитів, поля, налаштування варіантів) — get_skd."); //$NON-NLS-1$
        }

        JsonObject base = baseObject(object, v8Project);
        if (base != null) {
            result.add("baseObject", base); //$NON-NLS-1$
        }
        return result;
    }

    static EObject findObject(EObject configuration, String kind, String name) {
        return MetadataIndex.findObject(configuration, kind, name);
    }

    /**
     * Для запозиченого об'єкта розширення — сам об'єкт базової конфігурації.
     *
     * <p>Без нього відповідь по об'єкту розширення показує лише його власні реквізити
     * (часто один-два), і не видно ані типів, ані складу базового об'єкта, з яким
     * фактично працює код. Зв'язок — атрибут {@code extendedConfigurationObject} (uuid
     * базового об'єкта); шукаємо його серед конфігурацій інших проєктів workspace.
     */
    private static JsonObject baseObject(EObject object, IV8Project extension) {
        String baseUuid = Emf.str(object, "extendedConfigurationObject"); //$NON-NLS-1$
        if (baseUuid == null || baseUuid.isBlank()) {
            return null;
        }
        String canonicalKind = object.eClass().getName();
        for (IV8Project candidate : V8Access.v8Projects()) {
            if (candidate.getProject().equals(extension.getProject())) {
                continue;
            }
            EObject candidateConfiguration = V8Access.configuration(candidate);
            if (candidateConfiguration == null) {
                continue;
            }
            List<?> collection = MetadataIndex.findCollection(candidateConfiguration, canonicalKind);
            if (collection == null) {
                continue;
            }
            for (Object element : collection) {
                if (!(element instanceof EObject candidateObject)
                        || !baseUuid.equalsIgnoreCase(Emf.str(candidateObject, "uuid"))) { //$NON-NLS-1$
                    continue;
                }
                JsonObject base = new JsonObject();
                base.addProperty("project", candidate.getProject().getName()); //$NON-NLS-1$
                base.addProperty("kind", candidateObject.eClass().getName()); //$NON-NLS-1$
                base.addProperty("name", Emf.name(candidateObject)); //$NON-NLS-1$
                base.addProperty("uuid", baseUuid); //$NON-NLS-1$
                JsonObject baseSynonym = Emf.synonym(candidateObject);
                if (baseSynonym != null) {
                    base.add("synonym", baseSynonym); //$NON-NLS-1$
                }
                for (EReference reference : candidateObject.eClass().getEAllReferences()) {
                    if (!reference.isMany() || !BASE_COLLECTIONS.contains(reference.getName())) {
                        continue;
                    }
                    if (!(candidateObject.eGet(reference) instanceof List<?> list) || list.isEmpty()) {
                        continue;
                    }
                    boolean typed = TYPED_COLLECTIONS.contains(reference.getName());
                    boolean tabular = "tabularSections".equals(reference.getName()); //$NON-NLS-1$
                    JsonArray items = new JsonArray();
                    for (Object element2 : list) {
                        if (items.size() >= MAX_COLLECTION_ITEMS) {
                            break;
                        }
                        if (element2 instanceof EObject item) {
                            items.add(tabular ? tabularSection(item) : entry(item, typed));
                        }
                    }
                    base.add(reference.getName(), items);
                }
                return base;
            }
        }
        JsonObject notFound = new JsonObject();
        notFound.addProperty("uuid", baseUuid); //$NON-NLS-1$
        notFound.addProperty("found", false); //$NON-NLS-1$
        notFound.addProperty("hint", "Базову конфігурацію не знайдено серед проєктів workspace — " //$NON-NLS-1$ //$NON-NLS-2$
                + "підключіть її, щоб бачити склад запозиченого об'єкта."); //$NON-NLS-1$
        return notFound;
    }

    private static JsonObject entry(EObject item, boolean withTypes) {
        JsonObject entry = new JsonObject();
        entry.addProperty("name", Emf.name(item)); //$NON-NLS-1$
        JsonObject synonym = Emf.synonym(item);
        if (synonym != null) {
            entry.add("synonym", synonym); //$NON-NLS-1$
        }
        if (withTypes) {
            JsonArray types = Emf.typeNames(item);
            if (types != null) {
                entry.add("types", types); //$NON-NLS-1$
            }
        }
        return entry;
    }

    private static JsonObject tabularSection(EObject section) {
        JsonObject entry = entry(section, false);
        Object attributes = Emf.get(section, "attributes"); //$NON-NLS-1$
        if (attributes instanceof List<?> list && !list.isEmpty()) {
            JsonArray items = new JsonArray();
            for (Object element : list) {
                if (element instanceof EObject attribute) {
                    items.add(entry(attribute, true));
                }
            }
            entry.add("attributes", items); //$NON-NLS-1$
        }
        return entry;
    }

    private static String required(JsonObject arguments, String name) {
        if (!arguments.has(name) || arguments.get(name).getAsString().isBlank()) {
            throw new IllegalArgumentException("Обов'язковий параметр відсутній: " + name); //$NON-NLS-1$
        }
        return arguments.get(name).getAsString();
    }
}
