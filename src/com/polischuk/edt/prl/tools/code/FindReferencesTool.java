/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.code;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com.polischuk.edt.prl.edt.MetadataIndex;
import com.polischuk.edt.prl.edt.V8Access;
import com.polischuk.edt.prl.tools.McpTool;
import com.polischuk.edt.prl.tools.code.BslReferences.Location;
import com.polischuk.edt.prl.tools.code.BslReferences.ResourceAccess;

/**
 * Семантичний пошук посилань через Xtext-індекс EDT (не текстовий пошук).
 *
 * Два режими цілі:
 * 1) path + method — посилання на метод модуля BSL: TargetURIs = URI вихідного
 *    методу (саме такі URI EDT записує в індекс через SourceObjectLinkProvider);
 *    додатково модуль-власник сканується на локальні виклики (AST), бо
 *    внутрішньомодульні виклики не потрапляють в індекс.
 * 2) kind + name — посилання на об'єкт метаданих (експериментально): TargetURIs =
 *    URI об'єкта конфігурації; знаходить згадки з BSL-коду, які індексує Xtext.
 */
public final class FindReferencesTool implements McpTool {

    private static final int DEFAULT_MAX_RESULTS = 200;

    @Override
    public String name() {
        return "find_references"; //$NON-NLS-1$
    }

    @Override
    public String description() {
        return "Семантичний пошук посилань через Xtext-індекс EDT. " //$NON-NLS-1$
                + "Або path+method (посилання на метод модуля BSL, включно з локальними викликами), " //$NON-NLS-1$
                + "або kind+name (посилання на об'єкт метаданих із BSL-коду; експериментально). " //$NON-NLS-1$
                + "Результат: список {project, path, line, method, snippet}."; //$NON-NLS-1$
    }

    @Override
    public JsonObject inputSchema() {
        return JsonParser.parseString("""
                {"type":"object","properties":{
                  "project":{"type":"string","description":"Ім'я проєкту EDT (необов'язково, якщо проєкт один)"},
                  "path":{"type":"string","description":"Шлях модуля відносно проєкту, напр. src/CommonModules/МійМодуль/Module.bsl"},
                  "method":{"type":"string","description":"Ім'я процедури або функції модуля"},
                  "kind":{"type":"string","description":"Вид метаданих (замість path+method), напр. Catalog"},
                  "name":{"type":"string","description":"Ім'я об'єкта метаданих (разом із kind)"},
                  "scope":{"type":"string","enum":["auto","project","workspace"],"default":"auto","description":"Область: auto — проєкт цілі + залежні від нього (розширення); project — лише проєкт цілі; workspace — весь індекс (повільно на типових конфігураціях)"},
                  "maxResults":{"type":"integer","default":200}
                }}""").getAsJsonObject(); //$NON-NLS-1$
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
        String projectName = optional(arguments, "project"); //$NON-NLS-1$
        String path = optional(arguments, "path"); //$NON-NLS-1$
        String methodName = optional(arguments, "method"); //$NON-NLS-1$
        String kind = optional(arguments, "kind"); //$NON-NLS-1$
        String objectName = optional(arguments, "name"); //$NON-NLS-1$
        String scope = optional(arguments, "scope"); //$NON-NLS-1$
        int maxResults = arguments.has("maxResults") //$NON-NLS-1$
                ? arguments.get("maxResults").getAsInt() : DEFAULT_MAX_RESULTS; //$NON-NLS-1$

        try {
            if (path != null) {
                if (methodName == null) {
                    throw new IllegalArgumentException("Разом із 'path' обов'язковий 'method'."); //$NON-NLS-1$
                }
                return findMethodReferences(projectName, path, methodName, scope, maxResults);
            }
            if (kind != null && objectName != null) {
                return findMetadataReferences(projectName, kind, objectName, scope, maxResults);
            }
            throw new IllegalArgumentException(
                    "Вкажіть або path+method (метод модуля BSL), або kind+name (об'єкт метаданих)."); //$NON-NLS-1$
        } catch (NoClassDefFoundError e) {
            throw new IllegalStateException(
                    "Xtext/BSL-інфраструктура EDT недоступна в runtime: " + e.getMessage() //$NON-NLS-1$
                    + ". Перевірте Import-Package (org.eclipse.xtext.findReferences, " //$NON-NLS-1$
                    + "org.eclipse.xtext.ui.shared, com._1c.g5.v8.dt.bsl.*) у MANIFEST.MF.", e); //$NON-NLS-1$
        }
    }

    /** Режим 1: посилання на метод модуля BSL. */
    private static JsonObject findMethodReferences(String projectName, String path,
            String methodName, String scope, int maxResults) {
        IProject project = V8Access.resolveEclipseProject(projectName);
        ResourceAccess access = new ResourceAccess();
        JsonObject scopeJson = applyScope(access, project, scope);
        Module module = BslReferences.loadModule(access, project, path);
        Method target = BslReferences.findMethod(module, methodName);

        // 1. Міжмодульні посилання з Xtext-індексу (плюс локальні, якщо фіндер їх знайде).
        long startedAt = System.currentTimeMillis();
        LinkedHashSet<URI> sources = BslReferences.findReferenceSources(
                List.of(EcoreUtil.getURI(target)), access);
        scopeJson.addProperty("searchMs", System.currentTimeMillis() - startedAt); //$NON-NLS-1$

        Map<String, String[]> linesCache = new HashMap<>();
        Map<String, Location> byPosition = new LinkedHashMap<>();
        for (URI source : sources) {
            Location location = BslReferences.locate(source, access, linesCache);
            byPosition.putIfAbsent(positionKey(location), location);
        }

        // 2. Локальні виклики всередині модуля-власника (AST): гарантія повноти,
        //    бо внутрішньомодульні виклики не зберігаються в індексі.
        for (Invocation invocation : BslReferences.localInvocations(module, target.getName())) {
            int line = BslReferences.nodeStartLine(invocation);
            if (line <= 0) {
                continue;
            }
            Method container = org.eclipse.xtext.EcoreUtil2.getContainerOfType(invocation, Method.class);
            Location location = new Location(project.getName(), path, line,
                    BslReferences.lineText(project.getName(), path, line, linesCache),
                    container == null ? null : container.getName());
            byPosition.putIfAbsent(positionKey(location), location);
        }

        JsonObject result = new JsonObject();
        JsonObject targetJson = new JsonObject();
        targetJson.addProperty("project", project.getName()); //$NON-NLS-1$
        targetJson.addProperty("path", path); //$NON-NLS-1$
        targetJson.addProperty("method", target.getName()); //$NON-NLS-1$
        targetJson.addProperty("export", target.isExport()); //$NON-NLS-1$
        result.add("target", targetJson); //$NON-NLS-1$
        result.add("scope", scopeJson); //$NON-NLS-1$
        fillReferences(result, byPosition, maxResults);
        return result;
    }

    /** Режим 2 (експериментально): посилання на об'єкт метаданих із BSL-коду. */
    private static JsonObject findMetadataReferences(String projectName, String kind,
            String objectName, String scope, int maxResults) {
        EObject configuration = V8Access.requireConfiguration(projectName);
        EObject object = MetadataIndex.findObject(configuration, kind, objectName);
        IProject project = V8Access.resolveEclipseProject(projectName);
        ResourceAccess access = new ResourceAccess();
        JsonObject scopeJson = applyScope(access, project, scope);
        // ResourceSet цільового проєкту — дефолтний для не-platform URI
        access.resourceSet(project);

        long startedAt = System.currentTimeMillis();
        LinkedHashSet<URI> sources = BslReferences.findReferenceSources(
                List.of(EcoreUtil.getURI(object)), access);
        scopeJson.addProperty("searchMs", System.currentTimeMillis() - startedAt); //$NON-NLS-1$

        Map<String, String[]> linesCache = new HashMap<>();
        Map<String, Location> byPosition = new LinkedHashMap<>();
        for (URI source : sources) {
            Location location = BslReferences.locate(source, access, linesCache);
            byPosition.putIfAbsent(positionKey(location), location);
        }

        JsonObject result = new JsonObject();
        JsonObject targetJson = new JsonObject();
        targetJson.addProperty("kind", kind); //$NON-NLS-1$
        targetJson.addProperty("name", objectName); //$NON-NLS-1$
        result.add("target", targetJson); //$NON-NLS-1$
        result.add("scope", scopeJson); //$NON-NLS-1$
        result.addProperty("note", "Експериментальний режим: враховуються лише посилання, " //$NON-NLS-1$ //$NON-NLS-2$
                + "які потрапили в Xtext-індекс (BSL-код); форми, запити та права не скануються."); //$NON-NLS-1$
        fillReferences(result, byPosition, maxResults);
        return result;
    }

    /**
     * Обмежує область пошуку. auto (типово) — проєкт цілі та проєкти, що від нього
     * залежать; project — лише проєкт цілі; workspace — увесь індекс.
     * Повний скан індексу на конфігурації в 17 проєктів коштує ~1 хв на виклик.
     */
    static JsonObject applyScope(ResourceAccess access, IProject project, String scope) {
        String mode = scope == null || scope.isBlank() ? "auto" : scope.toLowerCase(Locale.ROOT); //$NON-NLS-1$
        JsonObject scopeJson = new JsonObject();

        if ("workspace".equals(mode)) { //$NON-NLS-1$
            scopeJson.addProperty("mode", "workspace"); //$NON-NLS-1$ //$NON-NLS-2$
            return scopeJson;
        }
        Set<String> projects = "project".equals(mode) //$NON-NLS-1$
                ? Set.of(project.getName())
                : BslReferences.dependencyClosure(project);
        if (projects.isEmpty()) {
            // залежності не визначились — краще повільно, ніж неповно
            scopeJson.addProperty("mode", "workspace"); //$NON-NLS-1$ //$NON-NLS-2$
            scopeJson.addProperty("note", "Залежності проєкту не визначено — скан по всьому індексу."); //$NON-NLS-1$ //$NON-NLS-2$
            return scopeJson;
        }
        Set<URI> uris = BslReferences.resourceUrisOfProjects(projects);
        access.restrictTo(uris);
        scopeJson.addProperty("mode", mode); //$NON-NLS-1$
        JsonArray projectsJson = new JsonArray();
        projects.forEach(projectsJson::add);
        scopeJson.add("projects", projectsJson); //$NON-NLS-1$
        scopeJson.addProperty("scannedResources", uris.size()); //$NON-NLS-1$
        return scopeJson;
    }

    private static void fillReferences(JsonObject result, Map<String, Location> byPosition, int maxResults) {
        List<Location> locations = new ArrayList<>(byPosition.values());
        locations.sort(Comparator
                .comparing((Location l) -> l.project() == null ? "" : l.project()) //$NON-NLS-1$
                .thenComparing(Location::path)
                .thenComparingInt(Location::line));

        JsonArray references = new JsonArray();
        for (Location location : locations) {
            if (references.size() >= maxResults) {
                break;
            }
            JsonObject entry = new JsonObject();
            if (location.project() != null) {
                entry.addProperty("project", location.project()); //$NON-NLS-1$
            }
            entry.addProperty("path", location.path()); //$NON-NLS-1$
            entry.addProperty("line", location.line()); //$NON-NLS-1$
            if (location.method() != null) {
                entry.addProperty("method", location.method()); //$NON-NLS-1$
            }
            entry.addProperty("snippet", location.snippet()); //$NON-NLS-1$
            references.add(entry);
        }
        result.addProperty("totalReferences", locations.size()); //$NON-NLS-1$
        result.addProperty("returned", references.size()); //$NON-NLS-1$
        result.add("references", references); //$NON-NLS-1$
    }

    private static String positionKey(Location location) {
        return location.project() + "|" + location.path() + "|" + location.line(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String optional(JsonObject arguments, String name) {
        return arguments.has(name) && !arguments.get(name).getAsString().isBlank()
                ? arguments.get(name).getAsString() : null;
    }
}
