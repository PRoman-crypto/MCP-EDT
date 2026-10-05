/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.code;

import java.util.HashMap;
import java.util.HashSet;
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
import org.eclipse.xtext.EcoreUtil2;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.resource.DynamicFeatureAccessComputer;
import com.polischuk.edt.prl.edt.V8Access;
import com.polischuk.edt.prl.tools.McpTool;
import com.polischuk.edt.prl.tools.code.BslReferences.Callee;
import com.polischuk.edt.prl.tools.code.BslReferences.Location;
import com.polischuk.edt.prl.tools.code.BslReferences.ResourceAccess;

/**
 * Ієрархія викликів методу модуля BSL через Xtext-індекс EDT.
 *
 * incoming — хто викликає метод: пошук посилань (IReferenceFinder + індекс,
 * як у FindReferencesTool), згрупований за методом-викликачем; рекурсивно
 * до depth рівнів.
 *
 * outgoing — кого викликає метод: обхід AST методу (Invocation), цілі
 * розв'язуються так само, як у штатному Call Hierarchy EDT:
 * StaticFeatureAccess — метод свого модуля або глобальна функція;
 * DynamicFeatureAccess — через DynamicFeatureAccessComputer.getLastObject
 * і SourceObjectLinkProvider.getSourceUri (метод іншого модуля).
 */
public final class GetCallHierarchyTool implements McpTool {

    private static final int MAX_DEPTH = 3;
    private static final int DEFAULT_MAX_NODES = 100;

    @Override
    public String name() {
        return "get_call_hierarchy"; //$NON-NLS-1$
    }

    @Override
    public String description() {
        return "Ієрархія викликів методу модуля BSL (Xtext-індекс EDT). " //$NON-NLS-1$
                + "direction=incoming — хто викликає (згруповано за методом-викликачем), " //$NON-NLS-1$
                + "outgoing — кого викликає (локальні, міжмодульні, платформенні). " //$NON-NLS-1$
                + "depth до 3 рівнів."; //$NON-NLS-1$
    }

    @Override
    public JsonObject inputSchema() {
        return JsonParser.parseString("""
                {"type":"object","properties":{
                  "project":{"type":"string","description":"Ім'я проєкту EDT (необов'язково, якщо проєкт один)"},
                  "path":{"type":"string","description":"Шлях модуля відносно проєкту"},
                  "method":{"type":"string","description":"Ім'я процедури або функції"},
                  "direction":{"type":"string","enum":["incoming","outgoing"],"default":"incoming"},
                  "depth":{"type":"integer","default":1,"description":"Рівнів рекурсії, 1..3"},
                  "scope":{"type":"string","enum":["auto","project","workspace"],"default":"auto","description":"Область пошуку викликів: auto — проєкт цілі + залежні; project — лише проєкт цілі; workspace — весь індекс (повільно)"},
                  "maxNodes":{"type":"integer","default":100,"description":"Ліміт вузлів у відповіді"}
                },"required":["path","method"]}""").getAsJsonObject(); //$NON-NLS-1$
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
        String projectName = arguments.has("project") ? arguments.get("project").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$
        String path = arguments.get("path").getAsString(); //$NON-NLS-1$
        String methodName = arguments.get("method").getAsString(); //$NON-NLS-1$
        String direction = arguments.has("direction") //$NON-NLS-1$
                ? arguments.get("direction").getAsString() : "incoming"; //$NON-NLS-1$ //$NON-NLS-2$
        int depth = Math.max(1, Math.min(MAX_DEPTH,
                arguments.has("depth") ? arguments.get("depth").getAsInt() : 1)); //$NON-NLS-1$ //$NON-NLS-2$
        int maxNodes = arguments.has("maxNodes") //$NON-NLS-1$
                ? arguments.get("maxNodes").getAsInt() : DEFAULT_MAX_NODES; //$NON-NLS-1$

        try {
            IProject project = V8Access.resolveEclipseProject(projectName);
            ResourceAccess access = new ResourceAccess();
            JsonObject scopeJson = FindReferencesTool.applyScope(access, project,
                    arguments.has("scope") ? arguments.get("scope").getAsString() : null); //$NON-NLS-1$ //$NON-NLS-2$
            Module module = BslReferences.loadModule(access, project, path);
            Method target = BslReferences.findMethod(module, methodName);

            Context context = new Context(access, new HashMap<>(), new HashSet<>(), new int[] {maxNodes});
            JsonObject result = new JsonObject();
            result.addProperty("project", project.getName()); //$NON-NLS-1$
            result.addProperty("path", path); //$NON-NLS-1$
            result.addProperty("method", target.getName()); //$NON-NLS-1$
            result.addProperty("direction", direction); //$NON-NLS-1$
            result.addProperty("depth", depth); //$NON-NLS-1$
            result.add("scope", scopeJson); //$NON-NLS-1$

            context.visited().add(visitKey(project.getName(), path, target.getName()));
            if ("outgoing".equalsIgnoreCase(direction)) { //$NON-NLS-1$
                result.add("calls", outgoing(target, module, depth, context)); //$NON-NLS-1$
            } else {
                result.add("callers", incoming(target, project.getName(), path, depth, context)); //$NON-NLS-1$
            }
            if (context.budget()[0] <= 0) {
                result.addProperty("truncated", true); //$NON-NLS-1$
            }
            return result;
        } catch (NoClassDefFoundError e) {
            throw new IllegalStateException(
                    "Xtext/BSL-інфраструктура EDT недоступна в runtime: " + e.getMessage() //$NON-NLS-1$
                    + ". Перевірте Import-Package (org.eclipse.xtext.findReferences, " //$NON-NLS-1$
                    + "org.eclipse.xtext.ui.shared, com._1c.g5.v8.dt.bsl.*) у MANIFEST.MF.", e); //$NON-NLS-1$
        }
    }

    /** Спільний стан обходу: доступ до ресурсів, кеш рядків, анти-цикл, ліміт вузлів. */
    private record Context(ResourceAccess access, Map<String, String[]> linesCache,
            Set<String> visited, int[] budget) {
    }

    // ------------------------------------------------------------------
    // incoming: хто викликає метод
    // ------------------------------------------------------------------

    private static JsonArray incoming(Method target, String projectName, String path,
            int depth, Context context) {
        JsonArray callers = new JsonArray();
        LinkedHashSet<URI> sources = BslReferences.findReferenceSources(
                List.of(EcoreUtil.getURI(target)), context.access());

        // додатково — локальні виклики всередині модуля-власника (їх немає в індексі)
        Module ownModule = EcoreUtil2.getContainerOfType(target, Module.class);
        Map<String, JsonObject> byCaller = new LinkedHashMap<>();
        for (URI source : sources) {
            Location location = BslReferences.locate(source, context.access(), context.linesCache());
            addCall(byCaller, location);
        }
        if (ownModule != null) {
            for (Invocation invocation : BslReferences.localInvocations(ownModule, target.getName())) {
                int line = BslReferences.nodeStartLine(invocation);
                if (line <= 0) {
                    continue;
                }
                Method container = EcoreUtil2.getContainerOfType(invocation, Method.class);
                Location location = new Location(projectName, path, line,
                        BslReferences.lineText(projectName, path, line, context.linesCache()),
                        container == null ? null : container.getName());
                addCall(byCaller, location);
            }
        }

        for (Map.Entry<String, JsonObject> entry : byCaller.entrySet()) {
            if (context.budget()[0] <= 0) {
                break;
            }
            context.budget()[0]--;
            JsonObject caller = entry.getValue();
            if (depth > 1 && caller.has("method")) { //$NON-NLS-1$
                String callerProject = caller.get("project").getAsString(); //$NON-NLS-1$
                String callerPath = caller.get("path").getAsString(); //$NON-NLS-1$
                String callerMethod = caller.get("method").getAsString(); //$NON-NLS-1$
                String key = visitKey(callerProject, callerPath, callerMethod);
                if (context.visited().add(key)) {
                    try {
                        IProject callerEclipseProject = V8Access.resolveEclipseProject(callerProject);
                        Module callerModule = BslReferences.loadModule(
                                context.access(), callerEclipseProject, callerPath);
                        Method callerMethodObject = BslReferences.findMethod(callerModule, callerMethod);
                        caller.add("callers", incoming(callerMethodObject, //$NON-NLS-1$
                                callerProject, callerPath, depth - 1, context));
                    } catch (RuntimeException e) {
                        caller.addProperty("note", "Рекурсія не вдалася: " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                } else {
                    caller.addProperty("recursion", true); //$NON-NLS-1$
                }
            }
            callers.add(caller);
        }
        return callers;
    }

    /** Групує місце виклику під вузлом методу-викликача. */
    private static void addCall(Map<String, JsonObject> byCaller, Location location) {
        String callerKey = location.project() + "|" + location.path() + "|" //$NON-NLS-1$ //$NON-NLS-2$
                + (location.method() == null ? "" : location.method().toLowerCase(Locale.ROOT)); //$NON-NLS-1$
        JsonObject caller = byCaller.computeIfAbsent(callerKey, key -> {
            JsonObject node = new JsonObject();
            if (location.project() != null) {
                node.addProperty("project", location.project()); //$NON-NLS-1$
            }
            node.addProperty("path", location.path()); //$NON-NLS-1$
            if (location.method() != null) {
                node.addProperty("method", location.method()); //$NON-NLS-1$
            }
            node.add("calls", new JsonArray()); //$NON-NLS-1$
            return node;
        });
        JsonArray calls = caller.getAsJsonArray("calls"); //$NON-NLS-1$
        for (JsonElement existing : calls) {
            if (existing.getAsJsonObject().get("line").getAsInt() == location.line()) { //$NON-NLS-1$
                return; // дублікат позиції (індекс + локальний скан)
            }
        }
        JsonObject call = new JsonObject();
        call.addProperty("line", location.line()); //$NON-NLS-1$
        call.addProperty("snippet", location.snippet()); //$NON-NLS-1$
        calls.add(call);
    }

    // ------------------------------------------------------------------
    // outgoing: кого викликає метод
    // ------------------------------------------------------------------

    private static JsonArray outgoing(Method method, Module module, int depth, Context context) {
        JsonArray calls = new JsonArray();
        DynamicFeatureAccessComputer computer = BslReferences.bslService(DynamicFeatureAccessComputer.class);
        for (Invocation invocation : EcoreUtil2.getAllContentsOfType(method, Invocation.class)) {
            if (context.budget()[0] <= 0) {
                break;
            }
            Callee callee = BslReferences.resolveCallee(invocation, module, computer);
            if (callee == null) {
                continue;
            }
            context.budget()[0]--;
            JsonObject node = new JsonObject();
            node.addProperty("name", callee.name()); //$NON-NLS-1$
            node.addProperty("kind", callee.kind()); //$NON-NLS-1$
            node.addProperty("line", BslReferences.nodeStartLine(invocation)); //$NON-NLS-1$

            if (callee.methodUri() != null) {
                Location location = BslReferences.locate(callee.methodUri(),
                        context.access(), context.linesCache());
                if (location.project() != null) {
                    node.addProperty("project", location.project()); //$NON-NLS-1$
                }
                node.addProperty("path", location.path()); //$NON-NLS-1$
                if (depth > 1) {
                    String key = visitKey(location.project(), location.path(), callee.name());
                    if (context.visited().add(key)) {
                        try {
                            Method calleeMethod = resolveMethod(callee.methodUri(), context.access());
                            Module calleeModule = EcoreUtil2.getContainerOfType(calleeMethod, Module.class);
                            if (calleeMethod != null && calleeModule != null) {
                                node.add("calls", outgoing(calleeMethod, calleeModule, //$NON-NLS-1$
                                        depth - 1, context));
                            }
                        } catch (RuntimeException e) {
                            node.addProperty("note", "Рекурсія не вдалася: " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
                        }
                    } else {
                        node.addProperty("recursion", true); //$NON-NLS-1$
                    }
                }
            }
            calls.add(node);
        }
        return calls;
    }

    /** BSL-метод за URI (URI вихідного методу з SourceObjectLinkProvider). */
    private static Method resolveMethod(URI methodUri, ResourceAccess access) {
        EObject eObject = access.resourceSet(methodUri).getEObject(methodUri, true);
        if (eObject instanceof Method method) {
            return method;
        }
        throw new IllegalStateException("URI не вказує на метод BSL: " + methodUri); //$NON-NLS-1$
    }

    private static String visitKey(String project, String path, String method) {
        return (project + "|" + path + "|" + method).toLowerCase(Locale.ROOT); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
