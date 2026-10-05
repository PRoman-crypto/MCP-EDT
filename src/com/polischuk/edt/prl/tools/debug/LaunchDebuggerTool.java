/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.debug;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Path;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.ILineBreakpoint;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.debug.core.model.IValue;
import org.eclipse.debug.core.model.IVariable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.polischuk.edt.prl.edt.EdtServices;
import com.polischuk.edt.prl.edt.V8Access;
import com.polischuk.edt.prl.tools.McpTool;

import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugClientTarget;
import com._1c.g5.v8.dt.debug.core.model.breakpoints.IBslBreakpointFactory;
import com._1c.g5.v8.dt.debug.core.model.breakpoints.IBslLineBreakpoint;

/**
 * Керування відладчиком 1С через модель Eclipse Debug (org.eclipse.debug.core)
 * і експортоване API бандла com._1c.g5.v8.dt.debug.core.
 *
 * Скелет за результатами дослідження docs/research/debugger.md:
 * впевнені операції реалізовані через публічний Eclipse API (listTargets,
 * робота з точками останову, стан/стек, кроки, resume/pause); evaluate
 * свідомо не реалізований — потребує runtime-перевірки EvaluationEngine
 * (етап 2 плану в звіті).
 */
public final class LaunchDebuggerTool implements McpTool {

    /** Id debug-моделі BSL (IDebugConstants.ID_BSL_DEBUG_MODEL). */
    private static final String BSL_DEBUG_MODEL_ID = "com._1c.g5.v8.dt.debug"; //$NON-NLS-1$

    @Override
    public String name() {
        return "launch_debugger"; //$NON-NLS-1$
    }

    @Override
    public String description() {
        return "Керування відладчиком 1С: listTargets — активні debug-цілі та їх потоки; " //$NON-NLS-1$
                + "setBreakpoint/removeBreakpoint/listBreakpoints — точки останову в модулях BSL; " //$NON-NLS-1$
                + "getState — стан зупинки зі стеком; getVariables — змінні фрейму; " //$NON-NLS-1$
                + "stepOver/stepInto/resume/pause — керування виконанням. " //$NON-NLS-1$
                + "Потрібен запуск 1С:Підприємства з відладкою з EDT (крім операцій з точками останову)."; //$NON-NLS-1$
    }

    @Override
    public JsonObject inputSchema() {
        return JsonParser.parseString("""
                {"type":"object","properties":{
                  "operation":{"type":"string","enum":["help","listTargets","setBreakpoint","removeBreakpoint","listBreakpoints","getState","getVariables","evaluate","stepOver","stepInto","resume","pause"]},
                  "project":{"type":"string","description":"Ім'я проєкту EDT (необов'язково, якщо проєкт один)"},
                  "module":{"type":"string","description":"setBreakpoint/removeBreakpoint: шлях модуля відносно проєкту (src/CommonModules/X/Module.bsl)"},
                  "line":{"type":"integer","description":"setBreakpoint/removeBreakpoint: номер рядка (1-based)"},
                  "target":{"type":"integer","default":0,"description":"Індекс debug-цілі з listTargets"},
                  "thread":{"type":"integer","default":0,"description":"Індекс потоку в цілі (з getState)"},
                  "frame":{"type":"integer","default":0,"description":"getVariables/evaluate: рівень фрейму стека (0 — верхній)"},
                  "expression":{"type":"string","description":"evaluate: BSL-вираз"}
                },"required":["operation"]}""").getAsJsonObject(); //$NON-NLS-1$
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
        String operation = arguments.get("operation").getAsString(); //$NON-NLS-1$
        return switch (operation) {
        case "help" -> help(); //$NON-NLS-1$
        case "listTargets" -> listTargets(); //$NON-NLS-1$
        case "setBreakpoint" -> setBreakpoint(arguments); //$NON-NLS-1$
        case "removeBreakpoint" -> removeBreakpoint(arguments); //$NON-NLS-1$
        case "listBreakpoints" -> listBreakpoints(); //$NON-NLS-1$
        case "getState" -> getState(arguments); //$NON-NLS-1$
        case "getVariables" -> getVariables(arguments); //$NON-NLS-1$
        case "evaluate" -> evaluate(arguments); //$NON-NLS-1$
        case "stepOver" -> step(arguments, StepKind.OVER); //$NON-NLS-1$
        case "stepInto" -> step(arguments, StepKind.INTO); //$NON-NLS-1$
        case "resume" -> resumeOrPause(arguments, true); //$NON-NLS-1$
        case "pause" -> resumeOrPause(arguments, false); //$NON-NLS-1$
        default -> throw new IllegalArgumentException(
                "Невідома операція: " + operation + ". Викличте operation=help."); //$NON-NLS-1$ //$NON-NLS-2$
        };
    }

    private static JsonObject help() {
        JsonObject help = new JsonObject();
        help.addProperty("listTargets", "активні debug-цілі (запущені сеанси 1С) та їх потоки"); //$NON-NLS-1$ //$NON-NLS-2$
        help.addProperty("setBreakpoint", "module + line (+project): точка останову в модулі BSL"); //$NON-NLS-1$ //$NON-NLS-2$
        help.addProperty("removeBreakpoint", "module + line (+project): зняти точку останову"); //$NON-NLS-1$ //$NON-NLS-2$
        help.addProperty("listBreakpoints", "усі BSL-точки останову workspace"); //$NON-NLS-1$ //$NON-NLS-2$
        help.addProperty("getState", "target: потоки, suspended-стан, стек викликів"); //$NON-NLS-1$ //$NON-NLS-2$
        help.addProperty("getVariables", "target + thread + frame: змінні фрейму"); //$NON-NLS-1$ //$NON-NLS-2$
        help.addProperty("evaluate", "не реалізовано (див. docs/research/debugger.md)"); //$NON-NLS-1$ //$NON-NLS-2$
        help.addProperty("stepOver/stepInto", "target + thread: крок (команда асинхронна — стан питайте getState)"); //$NON-NLS-1$ //$NON-NLS-2$
        help.addProperty("resume/pause", "target + thread: продовжити/призупинити виконання"); //$NON-NLS-1$ //$NON-NLS-2$
        return help;
    }

    // ---------------------------------------------------------------- targets

    /** Усі debug-цілі LaunchManager; BSL-цілі збагачуються даними EDT. */
    private static JsonObject listTargets() {
        JsonArray targets = new JsonArray();
        IDebugTarget[] debugTargets = DebugPlugin.getDefault().getLaunchManager().getDebugTargets();
        for (int i = 0; i < debugTargets.length; i++) {
            IDebugTarget target = debugTargets[i];
            JsonObject entry = new JsonObject();
            entry.addProperty("target", i); //$NON-NLS-1$
            try {
                entry.addProperty("name", target.getName()); //$NON-NLS-1$
            } catch (Exception e) {
                entry.addProperty("name", "?"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            entry.addProperty("modelId", target.getModelIdentifier()); //$NON-NLS-1$
            entry.addProperty("terminated", target.isTerminated()); //$NON-NLS-1$
            entry.addProperty("suspended", target.isSuspended()); //$NON-NLS-1$
            describeBslTarget(target, entry);
            try {
                JsonArray threads = new JsonArray();
                IThread[] targetThreads = target.getThreads();
                for (int t = 0; t < targetThreads.length; t++) {
                    threads.add(describeThread(targetThreads[t], t));
                }
                entry.add("threads", threads); //$NON-NLS-1$
            } catch (Exception e) {
                entry.addProperty("threadsError", String.valueOf(e.getMessage())); //$NON-NLS-1$
            }
            targets.add(entry);
        }
        JsonObject result = new JsonObject();
        result.addProperty("count", targets.size()); //$NON-NLS-1$
        result.add("targets", targets); //$NON-NLS-1$
        if (targets.size() == 0) {
            result.addProperty("hint", //$NON-NLS-1$
                    "Немає активних debug-цілей: запустіть 1С:Підприємство з відладкою з EDT " //$NON-NLS-1$
                            + "(конфігурація запуску «1С:Предприятие», режим Debug)."); //$NON-NLS-1$
        }
        return result;
    }

    /** EDT-специфіка цілі; ізольовано на випадок нерозв'язаного optional-імпорту. */
    private static void describeBslTarget(IDebugTarget target, JsonObject entry) {
        try {
            if (target instanceof IRuntimeDebugClientTarget bslTarget) {
                entry.addProperty("debugServerUrl", bslTarget.getDebugServerUrl()); //$NON-NLS-1$
                bslTarget.getApplication().ifPresent(application -> {
                    entry.addProperty("applicationId", application.getId()); //$NON-NLS-1$
                    entry.addProperty("applicationName", application.getName()); //$NON-NLS-1$
                    entry.addProperty("project", application.getProject() == null ? null //$NON-NLS-1$
                            : application.getProject().getName());
                });
            }
        } catch (NoClassDefFoundError e) {
            entry.addProperty("bslInfo", "бандл com._1c.g5.v8.dt.debug.core недоступний: " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static JsonObject describeThread(IThread thread, int index) {
        JsonObject entry = new JsonObject();
        entry.addProperty("thread", index); //$NON-NLS-1$
        try {
            entry.addProperty("name", thread.getName()); //$NON-NLS-1$
        } catch (Exception e) {
            entry.addProperty("name", "?"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        entry.addProperty("suspended", thread.isSuspended()); //$NON-NLS-1$
        entry.addProperty("canResume", thread.canResume()); //$NON-NLS-1$
        entry.addProperty("canSuspend", thread.canSuspend()); //$NON-NLS-1$
        entry.addProperty("canStepOver", thread.canStepOver()); //$NON-NLS-1$
        entry.addProperty("canStepInto", thread.canStepInto()); //$NON-NLS-1$
        return entry;
    }

    // ------------------------------------------------------------ breakpoints

    private static JsonObject setBreakpoint(JsonObject arguments) throws Exception {
        IFile file = resolveModuleFile(arguments);
        int line = requiredLine(arguments);
        IBslBreakpointFactory factory = EdtServices.get(IBslBreakpointFactory.class);
        if (factory == null) {
            throw new IllegalStateException(
                    "IBslBreakpointFactory відсутній в OSGi-реєстрі. Потрібен runtime-probe " //$NON-NLS-1$
                            + "(див. docs/research/debugger.md, «Ризики», п.1): можливо, сервіс " //$NON-NLS-1$
                            + "публікується під іншим інтерфейсом або лише через Guice-injector бандла."); //$NON-NLS-1$
        }
        IBslLineBreakpoint breakpoint = factory.createLineBreakpoint(file, line);
        DebugPlugin.getDefault().getBreakpointManager().addBreakpoint(breakpoint);
        JsonObject result = new JsonObject();
        result.addProperty("set", true); //$NON-NLS-1$
        result.addProperty("module", file.getProjectRelativePath().toString()); //$NON-NLS-1$
        result.addProperty("line", breakpoint.getLineNumber()); //$NON-NLS-1$
        return result;
    }

    private static JsonObject removeBreakpoint(JsonObject arguments) throws Exception {
        IFile file = resolveModuleFile(arguments);
        int line = requiredLine(arguments);
        for (IBreakpoint breakpoint : bslBreakpoints()) {
            if (breakpoint instanceof ILineBreakpoint lineBreakpoint
                    && file.equals(breakpoint.getMarker().getResource())
                    && lineBreakpoint.getLineNumber() == line) {
                DebugPlugin.getDefault().getBreakpointManager().removeBreakpoint(breakpoint, true);
                JsonObject result = new JsonObject();
                result.addProperty("removed", true); //$NON-NLS-1$
                return result;
            }
        }
        throw new IllegalArgumentException("Точку останову не знайдено: " //$NON-NLS-1$
                + file.getProjectRelativePath() + ":" + line); //$NON-NLS-1$
    }

    private static JsonObject listBreakpoints() throws Exception {
        JsonArray breakpoints = new JsonArray();
        for (IBreakpoint breakpoint : bslBreakpoints()) {
            JsonObject entry = new JsonObject();
            if (breakpoint.getMarker() != null && breakpoint.getMarker().getResource() != null) {
                entry.addProperty("resource", breakpoint.getMarker().getResource() //$NON-NLS-1$
                        .getFullPath().toString());
            }
            if (breakpoint instanceof ILineBreakpoint lineBreakpoint) {
                entry.addProperty("line", lineBreakpoint.getLineNumber()); //$NON-NLS-1$
            }
            entry.addProperty("enabled", breakpoint.isEnabled()); //$NON-NLS-1$
            breakpoints.add(entry);
        }
        JsonObject result = new JsonObject();
        result.addProperty("count", breakpoints.size()); //$NON-NLS-1$
        result.add("breakpoints", breakpoints); //$NON-NLS-1$
        return result;
    }

    private static IBreakpoint[] bslBreakpoints() {
        return DebugPlugin.getDefault().getBreakpointManager().getBreakpoints(BSL_DEBUG_MODEL_ID);
    }

    // ------------------------------------------------------------------ state

    /** Потоки цілі зі стеком suspended-потоків. */
    private static JsonObject getState(JsonObject arguments) throws Exception {
        IDebugTarget target = resolveTarget(arguments);
        JsonObject result = new JsonObject();
        result.addProperty("suspended", target.isSuspended()); //$NON-NLS-1$
        result.addProperty("terminated", target.isTerminated()); //$NON-NLS-1$
        JsonArray threads = new JsonArray();
        IThread[] targetThreads = target.getThreads();
        for (int t = 0; t < targetThreads.length; t++) {
            JsonObject entry = describeThread(targetThreads[t], t);
            if (targetThreads[t].isSuspended()) {
                JsonArray stack = new JsonArray();
                IStackFrame[] frames = targetThreads[t].getStackFrames();
                for (int f = 0; f < frames.length; f++) {
                    JsonObject frame = new JsonObject();
                    frame.addProperty("frame", f); //$NON-NLS-1$
                    frame.addProperty("name", frames[f].getName()); //$NON-NLS-1$
                    frame.addProperty("line", frames[f].getLineNumber()); //$NON-NLS-1$
                    stack.add(frame);
                }
                entry.add("stack", stack); //$NON-NLS-1$
            }
            threads.add(entry);
        }
        result.add("threads", threads); //$NON-NLS-1$
        return result;
    }

    // -------------------------------------------------------------- variables

    /**
     * Змінні фрейму через стандартний IVariable/IValue. Ліниві значення EDT
     * (IBslVariable.isEvaluated()==false) не дообчислюються — це синхронний
     * мережевий виклик до dbgs, вмикається на етапі 2 (див. звіт).
     */
    private static JsonObject getVariables(JsonObject arguments) throws Exception {
        IStackFrame frame = resolveFrame(arguments);
        JsonArray variables = new JsonArray();
        for (IVariable variable : frame.getVariables()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", variable.getName()); //$NON-NLS-1$
            try {
                IValue value = variable.getValue();
                if (value != null) {
                    entry.addProperty("value", value.getValueString()); //$NON-NLS-1$
                    entry.addProperty("type", value.getReferenceTypeName()); //$NON-NLS-1$
                    entry.addProperty("hasChildren", value.hasVariables()); //$NON-NLS-1$
                }
            } catch (Exception e) {
                entry.addProperty("valueError", String.valueOf(e.getMessage())); //$NON-NLS-1$
            }
            variables.add(entry);
        }
        JsonObject result = new JsonObject();
        result.addProperty("count", variables.size()); //$NON-NLS-1$
        result.add("variables", variables); //$NON-NLS-1$
        return result;
    }

    // ------------------------------------------------------------- exec flow

    private enum StepKind {
        OVER, INTO
    }

    private static JsonObject step(JsonObject arguments, StepKind kind) throws Exception {
        IThread thread = resolveThread(arguments);
        if (kind == StepKind.OVER) {
            if (!thread.canStepOver()) {
                throw new IllegalStateException("Потік не може виконати stepOver (не suspended?)."); //$NON-NLS-1$
            }
            thread.stepOver();
        } else {
            if (!thread.canStepInto()) {
                throw new IllegalStateException("Потік не може виконати stepInto (не suspended?)."); //$NON-NLS-1$
            }
            thread.stepInto();
        }
        return commandAccepted();
    }

    private static JsonObject resumeOrPause(JsonObject arguments, boolean resume) throws Exception {
        IThread thread = resolveThread(arguments);
        if (resume) {
            if (!thread.canResume()) {
                throw new IllegalStateException("Потік не може виконати resume (не suspended?)."); //$NON-NLS-1$
            }
            thread.resume();
        } else {
            if (!thread.canSuspend()) {
                throw new IllegalStateException("Потік не може бути призупинений."); //$NON-NLS-1$
            }
            thread.suspend();
        }
        return commandAccepted();
    }

    /** Команди виконання асинхронні: підтвердження != нового стану. */
    private static JsonObject commandAccepted() {
        JsonObject result = new JsonObject();
        result.addProperty("accepted", true); //$NON-NLS-1$
        result.addProperty("hint", "Команда надіслана; актуальний стан — operation=getState."); //$NON-NLS-1$ //$NON-NLS-2$
        return result;
    }

    // -------------------------------------------------------------- resolvers

    private static IDebugTarget resolveTarget(JsonObject arguments) {
        int index = arguments.has("target") ? arguments.get("target").getAsInt() : 0; //$NON-NLS-1$ //$NON-NLS-2$
        IDebugTarget[] targets = DebugPlugin.getDefault().getLaunchManager().getDebugTargets();
        if (targets.length == 0) {
            throw new IllegalStateException(
                    "Немає активних debug-цілей: запустіть 1С:Підприємство з відладкою з EDT."); //$NON-NLS-1$
        }
        if (index < 0 || index >= targets.length) {
            throw new IllegalArgumentException("Невірний індекс цілі " + index //$NON-NLS-1$
                    + "; доступно: 0.." + (targets.length - 1)); //$NON-NLS-1$
        }
        return targets[index];
    }

    private static IThread resolveThread(JsonObject arguments) throws Exception {
        IDebugTarget target = resolveTarget(arguments);
        int index = arguments.has("thread") ? arguments.get("thread").getAsInt() : 0; //$NON-NLS-1$ //$NON-NLS-2$
        IThread[] threads = target.getThreads();
        if (threads.length == 0) {
            throw new IllegalStateException("У цілі немає потоків (сеанс завершився?)."); //$NON-NLS-1$
        }
        if (index < 0 || index >= threads.length) {
            throw new IllegalArgumentException("Невірний індекс потоку " + index //$NON-NLS-1$
                    + "; доступно: 0.." + (threads.length - 1)); //$NON-NLS-1$
        }
        return threads[index];
    }

    /** Обчислення BSL-виразу на зупиненому фреймі (механіка — DebugEvaluator). */
    private static JsonObject evaluate(JsonObject arguments) throws Exception {
        if (!arguments.has("expression")) { //$NON-NLS-1$
            throw new IllegalArgumentException("Не вказано expression (BSL-вираз)."); //$NON-NLS-1$
        }
        IStackFrame frame = resolveFrame(arguments);
        if (!(frame instanceof com._1c.g5.v8.dt.debug.core.model.IBslStackFrame bslFrame)) {
            throw new IllegalStateException(
                    "Фрейм не є IBslStackFrame — ціль не є BSL-відладкою EDT."); //$NON-NLS-1$
        }
        long timeoutMs = arguments.has("timeoutMs") ? arguments.get("timeoutMs").getAsLong() : 0; //$NON-NLS-1$
        return DebugEvaluator.evaluate(bslFrame.getDebugTarget(), bslFrame,
                arguments.get("expression").getAsString(), timeoutMs); //$NON-NLS-1$
    }

    private static IStackFrame resolveFrame(JsonObject arguments) throws Exception {
        IThread thread = resolveThread(arguments);
        if (!thread.isSuspended()) {
            throw new IllegalStateException(
                    "Потік не зупинений — стек і змінні доступні лише в suspended-стані."); //$NON-NLS-1$
        }
        int index = arguments.has("frame") ? arguments.get("frame").getAsInt() : 0; //$NON-NLS-1$ //$NON-NLS-2$
        IStackFrame[] frames = thread.getStackFrames();
        if (index < 0 || index >= frames.length) {
            throw new IllegalArgumentException("Невірний рівень фрейму " + index //$NON-NLS-1$
                    + "; доступно: 0.." + (frames.length - 1)); //$NON-NLS-1$
        }
        return frames[index];
    }

    private static IFile resolveModuleFile(JsonObject arguments) {
        String projectName = arguments.has("project") ? arguments.get("project").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$
        if (!arguments.has("module")) { //$NON-NLS-1$
            throw new IllegalArgumentException("Не вказано module (шлях модуля відносно проєкту)."); //$NON-NLS-1$
        }
        String modulePath = arguments.get("module").getAsString(); //$NON-NLS-1$
        IProject project = V8Access.resolveEclipseProject(projectName);
        IFile file = project.getFile(new Path(modulePath));
        if (!file.exists()) {
            throw new IllegalArgumentException("Модуль не знайдено: " + project.getName() //$NON-NLS-1$
                    + "/" + modulePath); //$NON-NLS-1$
        }
        return file;
    }

    private static int requiredLine(JsonObject arguments) {
        if (!arguments.has("line")) { //$NON-NLS-1$
            throw new IllegalArgumentException("Не вказано line (номер рядка, 1-based)."); //$NON-NLS-1$
        }
        int line = arguments.get("line").getAsInt(); //$NON-NLS-1$
        if (line < 1) {
            throw new IllegalArgumentException("line має бути >= 1 (рядки 1-based)."); //$NON-NLS-1$
        }
        return line;
    }
}
