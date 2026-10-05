/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.code;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.eclipse.core.resources.IProject;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.polischuk.edt.prl.edt.V8Access;
import com.polischuk.edt.prl.edt.WorkspaceFiles;
import com.polischuk.edt.prl.tools.McpTool;

/** Список модулів BSL проєкту (шляхи відносно кореня проєкту). */
public final class ListModulesTool implements McpTool {

    private static final int DEFAULT_LIMIT = 500;

    @Override
    public String name() {
        return "list_modules"; //$NON-NLS-1$
    }

    @Override
    public String description() {
        return "Модулі BSL проєкту: шлях, тип модуля, власник, розмір і кількість рядків. " //$NON-NLS-1$
                + "Фільтри: pathFilter (підрядок шляху), moduleType (ObjectModule, ManagerModule, " //$NON-NLS-1$
                + "FormModule, CommonModule…), objectType (Catalog, Document…), objectName. " //$NON-NLS-1$
                + "sortBy: path|size|lines. compact=true — по рядку на модуль замість об'єктів."; //$NON-NLS-1$
    }

    @Override
    public JsonObject inputSchema() {
        return JsonParser.parseString("""
                {"type":"object","properties":{
                  "project":{"type":"string","description":"Ім'я проєкту EDT (необов'язково, якщо проєкт один)"},
                  "pathFilter":{"type":"string","description":"Підрядок шляху, напр. ім'я об'єкта чи CommonModules"},
                  "moduleType":{"type":"string","description":"Тип модуля: ObjectModule, ManagerModule, FormModule, CommonModule, CommandModule, RecordSetModule…"},
                  "objectType":{"type":"string","description":"Вид власника у шляху: Catalog, Document, InformationRegister… (однина або множина)"},
                  "objectName":{"type":"string","description":"Ім'я об'єкта-власника"},
                  "sortBy":{"type":"string","enum":["path","size","lines"],"default":"path"},
                  "compact":{"type":"boolean","default":false,"description":"Рядки 'шлях;тип;байт;рядків' замість об'єктів"},
                  "offset":{"type":"integer","default":0},
                  "limit":{"type":"integer","default":500}
                }}""").getAsJsonObject(); //$NON-NLS-1$
    }

    @Override
    public JsonElement execute(JsonObject arguments) {
        String projectName = arguments.has("project") ? arguments.get("project").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$
        String pathFilter = lower(arguments, "pathFilter"); //$NON-NLS-1$
        String moduleTypeFilter = lower(arguments, "moduleType"); //$NON-NLS-1$
        String objectTypeFilter = lower(arguments, "objectType"); //$NON-NLS-1$
        String objectNameFilter = lower(arguments, "objectName"); //$NON-NLS-1$
        String sortBy = arguments.has("sortBy") //$NON-NLS-1$
                ? arguments.get("sortBy").getAsString().toLowerCase(Locale.ROOT) : "path"; //$NON-NLS-1$ //$NON-NLS-2$
        boolean compact = arguments.has("compact") && arguments.get("compact").getAsBoolean(); //$NON-NLS-1$ //$NON-NLS-2$
        int offset = arguments.has("offset") ? arguments.get("offset").getAsInt() : 0; //$NON-NLS-1$ //$NON-NLS-2$
        int limit = arguments.has("limit") ? arguments.get("limit").getAsInt() : DEFAULT_LIMIT; //$NON-NLS-1$ //$NON-NLS-2$

        IProject project = V8Access.resolveEclipseProject(projectName);
        // Спершу збираємо всі збіги, бо sortBy має впорядкувати весь набір,
        // а не лише сторінку, яку встигли набрати під час обходу.
        List<Entry> entries = new ArrayList<>();
        WorkspaceFiles.walk(project, "bsl", file -> { //$NON-NLS-1$
            String path = file.getProjectRelativePath().toString();
            if (pathFilter != null && !path.toLowerCase(Locale.ROOT).contains(pathFilter)) {
                return;
            }
            String moduleType = moduleType(path);
            if (moduleTypeFilter != null && !moduleType.toLowerCase(Locale.ROOT).equals(moduleTypeFilter)) {
                return;
            }
            String owner = owner(path);
            if (objectTypeFilter != null && !matchesObjectType(path, objectTypeFilter)) {
                return;
            }
            if (objectNameFilter != null
                    && (owner == null || !owner.toLowerCase(Locale.ROOT).contains(objectNameFilter))) {
                return;
            }
            Entry entry = new Entry();
            entry.path = path;
            entry.moduleType = moduleType;
            entry.owner = owner;
            entry.ownerType = ownerType(path);
            String source = WorkspaceFiles.read(file);
            entry.sizeBytes = source.getBytes(StandardCharsets.UTF_8).length;
            entry.linesCount = source.isEmpty() ? 0 : source.split("\r?\n", -1).length; //$NON-NLS-1$
            entries.add(entry);
        });

        Comparator<Entry> comparator = switch (sortBy) {
        case "size" -> Comparator.comparingInt((Entry e) -> e.sizeBytes).reversed(); //$NON-NLS-1$
        case "lines" -> Comparator.comparingInt((Entry e) -> e.linesCount).reversed(); //$NON-NLS-1$
        default -> Comparator.comparing((Entry e) -> e.path);
        };
        entries.sort(comparator);

        JsonArray modules = new JsonArray();
        int end = Math.min(entries.size(), offset + limit);
        for (int i = Math.max(0, offset); i < end; i++) {
            Entry entry = entries.get(i);
            if (compact) {
                modules.add(entry.path + ";" + entry.moduleType //$NON-NLS-1$
                        + ";" + entry.sizeBytes + ";" + entry.linesCount); //$NON-NLS-1$ //$NON-NLS-2$
                continue;
            }
            JsonObject item = new JsonObject();
            item.addProperty("path", entry.path); //$NON-NLS-1$
            item.addProperty("moduleType", entry.moduleType); //$NON-NLS-1$
            if (entry.owner != null) {
                item.addProperty("owner", entry.owner); //$NON-NLS-1$
            }
            if (entry.ownerType != null) {
                item.addProperty("ownerObjectType", entry.ownerType); //$NON-NLS-1$
            }
            item.addProperty("sizeBytes", entry.sizeBytes); //$NON-NLS-1$
            item.addProperty("linesCount", entry.linesCount); //$NON-NLS-1$
            modules.add(item);
        }

        JsonObject result = new JsonObject();
        result.addProperty("project", project.getName()); //$NON-NLS-1$
        result.addProperty("totalMatched", entries.size()); //$NON-NLS-1$
        result.addProperty("offset", offset); //$NON-NLS-1$
        result.addProperty("returned", modules.size()); //$NON-NLS-1$
        result.addProperty("hasMore", end < entries.size()); //$NON-NLS-1$
        result.addProperty("sortBy", sortBy); //$NON-NLS-1$
        result.add("modules", modules); //$NON-NLS-1$
        return result;
    }

    /** Зібраний рядок списку — щоб відсортувати весь набір до нарізання на сторінки. */
    private static final class Entry {
        String path;
        String moduleType;
        String owner;
        String ownerType;
        int sizeBytes;
        int linesCount;
    }

    private static String lower(JsonObject arguments, String name) {
        return arguments.has(name) && !arguments.get(name).getAsString().isBlank()
                ? arguments.get(name).getAsString().toLowerCase(Locale.ROOT) : null;
    }

    /** Вид власника з другого сегмента шляху: src/Catalogs/… → Catalog. */
    private static String ownerType(String path) {
        String[] segments = path.split("/"); //$NON-NLS-1$
        if (segments.length < 2 || !"src".equals(segments[0])) { //$NON-NLS-1$
            return null;
        }
        String collection = segments[1];
        return collection.endsWith("s") ? collection.substring(0, collection.length() - 1) : collection; //$NON-NLS-1$
    }

    /** Фільтр за видом власника приймає і однину (Catalog), і множину (Catalogs). */
    private static boolean matchesObjectType(String path, String filter) {
        String ownerType = ownerType(path);
        if (ownerType == null) {
            return false;
        }
        String normalized = ownerType.toLowerCase(Locale.ROOT);
        return normalized.equals(filter) || (normalized + "s").equals(filter); //$NON-NLS-1$
    }

    private static String moduleType(String path) {
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        String base = fileName.endsWith(".bsl") ? fileName.substring(0, fileName.length() - 4) : fileName; //$NON-NLS-1$
        if ("Module".equals(base)) { //$NON-NLS-1$
            if (path.contains("/CommonModules/")) { //$NON-NLS-1$
                return "CommonModule"; //$NON-NLS-1$
            }
            if (path.contains("/Forms/")) { //$NON-NLS-1$
                return "FormModule"; //$NON-NLS-1$
            }
            return "Module"; //$NON-NLS-1$
        }
        return base; // ObjectModule, ManagerModule, CommandModule, RecordSetModule…
    }

    /** Власник за шляхом: src/Catalogs/Номенклатура/… → Catalogs.Номенклатура (+ форма, якщо є). */
    private static String owner(String path) {
        String[] segments = path.split("/"); //$NON-NLS-1$
        if (segments.length < 3 || !"src".equals(segments[0])) { //$NON-NLS-1$
            return null;
        }
        StringBuilder owner = new StringBuilder(segments[1]);
        if (segments.length > 3) {
            owner.append('.').append(segments[2]);
            for (int i = 3; i < segments.length - 1; i++) {
                if ("Forms".equals(segments[i]) && i + 1 < segments.length - 1) { //$NON-NLS-1$
                    owner.append(".Форма.").append(segments[i + 1]); //$NON-NLS-1$
                    break;
                }
            }
        }
        return owner.toString();
    }
}
