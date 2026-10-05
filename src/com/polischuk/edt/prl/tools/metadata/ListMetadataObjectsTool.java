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
import java.util.Locale;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.polischuk.edt.prl.edt.Emf;
import com.polischuk.edt.prl.edt.KindRegistry;
import com.polischuk.edt.prl.edt.MetadataIndex;
import com.polischuk.edt.prl.edt.V8Access;
import com.polischuk.edt.prl.tools.McpTool;

/**
 * Огляд об'єктів метаданих: без kind — зведення з кількістю за видами,
 * з kind — список імен об'єктів цього виду (з пагінацією).
 */
public final class ListMetadataObjectsTool implements McpTool {

    private static final int DEFAULT_LIMIT = 200;

    @Override
    public String name() {
        return "list_metadata_objects"; //$NON-NLS-1$
    }

    @Override
    public String description() {
        return "Об'єкти метаданих конфігурації. Без kind — зведення за видами з кількістю. " //$NON-NLS-1$
                + "З kind (Catalog/Справочник/Довідник, Document/Документ…) — імена об'єктів виду; " //$NON-NLS-1$
                + "nameFilter — підрядок імені; offset/limit — пагінація."; //$NON-NLS-1$
    }

    @Override
    public JsonObject inputSchema() {
        return JsonParser.parseString("""
                {"type":"object","properties":{
                  "project":{"type":"string","description":"Ім'я проєкту EDT (необов'язково, якщо проєкт один)"},
                  "kind":{"type":"string","description":"Вид метаданих (Catalog, Document, Справочник, Документ…)"},
                  "nameFilter":{"type":"string","description":"Підрядок імені (без регістру)"},
                  "offset":{"type":"integer","default":0},
                  "limit":{"type":"integer","default":200}
                }}""").getAsJsonObject(); //$NON-NLS-1$
    }

    @Override
    public JsonElement execute(JsonObject arguments) {
        String projectName = optional(arguments, "project"); //$NON-NLS-1$
        String kind = optional(arguments, "kind"); //$NON-NLS-1$
        String nameFilter = optional(arguments, "nameFilter"); //$NON-NLS-1$
        int offset = arguments.has("offset") ? arguments.get("offset").getAsInt() : 0; //$NON-NLS-1$ //$NON-NLS-2$
        int limit = arguments.has("limit") ? arguments.get("limit").getAsInt() : DEFAULT_LIMIT; //$NON-NLS-1$ //$NON-NLS-2$

        EObject configuration = V8Access.requireConfiguration(projectName);
        return kind == null || kind.isBlank()
                ? summary(configuration)
                : listKind(configuration, kind, nameFilter, offset, limit);
    }

    private static JsonObject summary(EObject configuration) {
        JsonArray kinds = new JsonArray();
        for (EReference reference : configuration.eClass().getEAllReferences()) {
            if (!reference.isMany()) {
                continue;
            }
            Object value = configuration.eGet(reference);
            if (!(value instanceof List<?> list) || list.isEmpty()) {
                continue;
            }
            if (!(list.get(0) instanceof EObject first) || Emf.name(first) == null) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("kind", first.eClass().getName()); //$NON-NLS-1$
            entry.addProperty("collection", reference.getName()); //$NON-NLS-1$
            entry.addProperty("count", list.size()); //$NON-NLS-1$
            kinds.add(entry);
        }
        JsonObject result = new JsonObject();
        result.addProperty("configuration", Emf.name(configuration)); //$NON-NLS-1$
        result.add("kinds", kinds); //$NON-NLS-1$
        return result;
    }

    /** Розмір колекції об'єкта в окреме поле; порожні колекції не засмічують відповідь. */
    private static void addCount(JsonObject entry, EObject object, String feature, String property) {
        if (Emf.get(object, feature) instanceof List<?> list && !list.isEmpty()) {
            entry.addProperty(property, list.size());
        }
    }

    private static JsonObject listKind(EObject configuration, String kind, String nameFilter, int offset, int limit) {
        String canonical = KindRegistry.canonical(kind);
        List<?> objects = findCollection(configuration, canonical);
        if (objects == null) {
            throw new IllegalArgumentException("Вид метаданих не знайдено: " + kind //$NON-NLS-1$
                    + " (канонічно: " + canonical + "). Викличте інструмент без kind, щоб побачити доступні види."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String filter = nameFilter == null ? null : nameFilter.toLowerCase(Locale.ROOT);
        JsonArray items = new JsonArray();
        int matched = 0;
        for (Object item : objects) {
            if (!(item instanceof EObject object)) {
                continue;
            }
            String name = Emf.name(object);
            if (name == null || (filter != null && !name.toLowerCase(Locale.ROOT).contains(filter))) {
                continue;
            }
            matched++;
            if (matched <= offset || items.size() >= limit) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("name", name); //$NON-NLS-1$
            entry.addProperty("fullName", canonical + "." + name); //$NON-NLS-1$ //$NON-NLS-2$
            JsonObject synonym = Emf.synonym(object);
            if (synonym != null) {
                entry.add("synonym", synonym); //$NON-NLS-1$
            }
            String uuid = Emf.str(object, "uuid"); //$NON-NLS-1$
            if (uuid != null) {
                entry.addProperty("uuid", uuid); //$NON-NLS-1$
            }
            // лічильники дозволяють одразу бачити «вагу» об'єкта, не смикаючи get_object_details
            addCount(entry, object, "attributes", "attributesCount"); //$NON-NLS-1$ //$NON-NLS-2$
            addCount(entry, object, "tabularSections", "tabularSectionsCount"); //$NON-NLS-1$ //$NON-NLS-2$
            addCount(entry, object, "forms", "formsCount"); //$NON-NLS-1$ //$NON-NLS-2$
            addCount(entry, object, "commands", "commandsCount"); //$NON-NLS-1$ //$NON-NLS-2$
            Object hierarchical = Emf.get(object, "hierarchical"); //$NON-NLS-1$
            if (hierarchical instanceof Boolean flag) {
                entry.addProperty("hierarchical", flag); //$NON-NLS-1$
            }
            items.add(entry);
        }
        JsonObject result = new JsonObject();
        result.addProperty("kind", canonical); //$NON-NLS-1$
        result.addProperty("totalMatched", matched); //$NON-NLS-1$
        result.addProperty("offset", offset); //$NON-NLS-1$
        result.addProperty("returned", items.size()); //$NON-NLS-1$
        result.add("objects", items); //$NON-NLS-1$
        return result;
    }

    static List<?> findCollection(EObject configuration, String canonicalKind) {
        return MetadataIndex.findCollection(configuration, canonicalKind);
    }

    private static String optional(JsonObject arguments, String name) {
        return arguments.has(name) && !arguments.get(name).isJsonNull() ? arguments.get(name).getAsString() : null;
    }
}
