/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.validation;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.polischuk.edt.prl.edt.V8Access;
import com.polischuk.edt.prl.edt.WorkspaceFiles;
import com.polischuk.edt.prl.tools.McpTool;

/**
 * Перевірка модулів BSL за стандартами 1С через BSL Language Server
 * (github.com/1c-syntax/bsl-language-server, LGPL-3.0-or-later) — запуск CLI
 * {@code analyze} зовнішнім процесом і парсинг JSON-звіту (bsl-json.json).
 * Jar не постачається з плагіном: шлях — параметр bslLsPath або типові місця.
 */
public final class CodeReviewTool implements McpTool {

    private static final String RELEASES_URL = "https://github.com/1c-syntax/bsl-language-server/releases"; //$NON-NLS-1$
    private static final int DEFAULT_TIMEOUT_SECONDS = 300;
    private static final int MAX_TIMEOUT_SECONDS = 1800;
    private static final int MAX_FULL_ISSUES = 200;
    private static final int MAX_SUMMARY_RULES = 50;

    @Override
    public String name() {
        return "code_review"; //$NON-NLS-1$
    }

    @Override
    public String description() {
        return "Перевіряє модулі BSL за стандартами 1С через BSL Language Server (зовнішній jar). " //$NON-NLS-1$
                + "path — один модуль, pathFilter — підмножина, без них — увесь src проєкту. " //$NON-NLS-1$
                + "format: summary (топ правил з count) або full (до 200 зауважень). " //$NON-NLS-1$
                + "Потрібен bsl-language-server-*-exec.jar (bslLsPath або %USERPROFILE%\\.bsl-language-server\\)."; //$NON-NLS-1$
    }

    @Override
    public JsonObject inputSchema() {
        return JsonParser.parseString("""
                {"type":"object","properties":{
                  "project":{"type":"string","description":"Ім'я проєкту EDT (необов'язково, якщо проєкт один)"},
                  "path":{"type":"string","description":"Один модуль: шлях відносно кореня проєкту, напр. src/CommonModules/МійМодуль/Module.bsl"},
                  "pathFilter":{"type":"string","description":"Підрядок шляху (без регістру) — аналізувати лише модулі, що містять його"},
                  "bslLsPath":{"type":"string","description":"Шлях до bsl-language-server-*-exec.jar або каталогу з ним"},
                  "format":{"type":"string","enum":["summary","full"],"default":"summary","description":"summary — правила з кількістю, full — до 200 зауважень"},
                  "timeoutSeconds":{"type":"integer","default":300,"description":"Ліміт часу аналізу (макс. 1800); великий проєкт — збільште"}
                }}""").getAsJsonObject(); //$NON-NLS-1$
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
        IProject project = V8Access.resolveEclipseProject(
                arguments.has("project") ? arguments.get("project").getAsString() : null); //$NON-NLS-1$ //$NON-NLS-2$
        String singlePath = arguments.has("path") ? arguments.get("path").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$
        String pathFilter = arguments.has("pathFilter") //$NON-NLS-1$
                ? arguments.get("pathFilter").getAsString().toLowerCase(Locale.ROOT) : null; //$NON-NLS-1$
        String format = arguments.has("format") //$NON-NLS-1$
                ? arguments.get("format").getAsString().toLowerCase(Locale.ROOT) : "summary"; //$NON-NLS-1$ //$NON-NLS-2$
        int timeoutSeconds = arguments.has("timeoutSeconds") //$NON-NLS-1$
                ? Math.min(MAX_TIMEOUT_SECONDS, Math.max(10, arguments.get("timeoutSeconds").getAsInt())) //$NON-NLS-1$
                : DEFAULT_TIMEOUT_SECONDS;

        File jar = resolveBslLsJar(
                arguments.has("bslLsPath") ? arguments.get("bslLsPath").getAsString() : null, project); //$NON-NLS-1$ //$NON-NLS-2$
        String java = resolveJava();

        Path tempRoot = Files.createTempDirectory("edt-mcp-codereview-"); //$NON-NLS-1$
        try {
            Path outDir = Files.createDirectories(tempRoot.resolve("out")); //$NON-NLS-1$
            File srcDir = prepareSrcDir(project, singlePath, pathFilter, tempRoot);

            List<String> command = new ArrayList<>(List.of(
                    java, "-Xmx2g", "-jar", jar.getAbsolutePath(), //$NON-NLS-1$ //$NON-NLS-2$
                    "--analyze", //$NON-NLS-1$
                    "--srcDir", srcDir.getAbsolutePath(), //$NON-NLS-1$
                    "--outputDir", outDir.toFile().getAbsolutePath(), //$NON-NLS-1$
                    "--reporter", "json", //$NON-NLS-1$ //$NON-NLS-2$
                    "--silent")); //$NON-NLS-1$
            File config = new File(project.getLocation().toFile(), ".bsl-language-server.json"); //$NON-NLS-1$
            if (config.isFile()) {
                command.add("--configuration"); //$NON-NLS-1$
                command.add(config.getAbsolutePath());
            }

            long started = System.currentTimeMillis();
            String output = runProcess(command, timeoutSeconds);
            long elapsed = (System.currentTimeMillis() - started) / 1000;

            File report = outDir.resolve("bsl-json.json").toFile(); //$NON-NLS-1$
            if (!report.isFile()) {
                throw new IllegalStateException("BSL LS завершився без JSON-звіту (" + report //$NON-NLS-1$
                        + "). Хвіст виводу:\n" + tail(output, 2000)); //$NON-NLS-1$
            }
            JsonObject analysis = JsonParser
                    .parseString(Files.readString(report.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();

            JsonObject result = buildResult(analysis, srcDir, format);
            result.addProperty("project", project.getName()); //$NON-NLS-1$
            result.addProperty("bslLsJar", jar.getName()); //$NON-NLS-1$
            result.addProperty("elapsedSeconds", elapsed); //$NON-NLS-1$
            return result;
        } finally {
            deleteRecursively(tempRoot);
        }
    }

    // ---------- підготовка джерел ----------

    /** Каталог для --srcDir: увесь src проєкту напряму або тимчасова копія підмножини. */
    private static File prepareSrcDir(IProject project, String singlePath, String pathFilter, Path tempRoot)
            throws Exception {
        if (singlePath == null && pathFilter == null) {
            IFolder src = project.getFolder("src"); //$NON-NLS-1$
            File dir = (src.exists() ? src.getLocation() : project.getLocation()).toFile();
            if (!dir.isDirectory()) {
                throw new IllegalStateException("Каталог джерел не знайдено: " + dir); //$NON-NLS-1$
            }
            return dir;
        }
        Path copyRoot = Files.createDirectories(tempRoot.resolve("src")); //$NON-NLS-1$
        if (singlePath != null) {
            IFile file = WorkspaceFiles.file(project, singlePath); // кидає зрозумілу помилку, якщо немає
            copyPreservingPath(file, copyRoot);
            return copyRoot.toFile();
        }
        int[] copied = {0};
        IFolder src = project.getFolder("src"); //$NON-NLS-1$
        WorkspaceFiles.walk(src.exists() ? src : project, "bsl", file -> { //$NON-NLS-1$
            String relative = file.getProjectRelativePath().toString();
            if (relative.toLowerCase(Locale.ROOT).contains(pathFilter)) {
                try {
                    copyPreservingPath(file, copyRoot);
                    copied[0]++;
                } catch (IOException e) {
                    throw new IllegalStateException("Не вдалося скопіювати " + relative + ": " + e.getMessage(), e); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        });
        if (copied[0] == 0) {
            throw new IllegalArgumentException("Жоден .bsl не відповідає pathFilter: " + pathFilter //$NON-NLS-1$
                    + ". Шляхи модулів — list_modules."); //$NON-NLS-1$
        }
        return copyRoot.toFile();
    }

    private static void copyPreservingPath(IFile file, Path copyRoot) throws IOException {
        Path target = copyRoot.resolve(file.getProjectRelativePath().toString().replace('\\', '/'));
        Files.createDirectories(target.getParent());
        Files.copy(file.getLocation().toFile().toPath(), target, StandardCopyOption.REPLACE_EXISTING);
    }

    // ---------- пошук jar і java ----------

    /** Пошук bsl-language-server jar: параметр → %USERPROFILE%\.bsl-language-server → поруч із проєктом. */
    private static File resolveBslLsJar(String bslLsPath, IProject project) {
        if (bslLsPath != null && !bslLsPath.isBlank()) {
            File given = new File(bslLsPath);
            if (given.isFile()) {
                return given;
            }
            if (given.isDirectory()) {
                File found = newestJar(given);
                if (found != null) {
                    return found;
                }
            }
            throw new IllegalStateException("За шляхом bslLsPath немає jar: " + bslLsPath); //$NON-NLS-1$
        }
        List<File> candidates = new ArrayList<>();
        String userProfile = System.getProperty("user.home"); //$NON-NLS-1$
        if (userProfile != null) {
            candidates.add(new File(userProfile, ".bsl-language-server")); //$NON-NLS-1$
        }
        File projectDir = project.getLocation() == null ? null : project.getLocation().toFile();
        if (projectDir != null) {
            candidates.add(projectDir);
            candidates.add(projectDir.getParentFile());
        }
        for (File dir : candidates) {
            if (dir != null && dir.isDirectory()) {
                File found = newestJar(dir);
                if (found != null) {
                    return found;
                }
            }
        }
        throw new IllegalStateException("bsl-language-server jar не знайдено. Завантажте " //$NON-NLS-1$
                + "bsl-language-server-<версія>-exec.jar зі сторінки релізів " + RELEASES_URL //$NON-NLS-1$
                + " і покладіть у %USERPROFILE%\\.bsl-language-server\\ (створіть каталог) " //$NON-NLS-1$
                + "або передайте параметр bslLsPath із повним шляхом до jar."); //$NON-NLS-1$
    }

    /** Найновіший (за іменем) jar bsl-language-server у каталозі; -exec.jar має пріоритет. */
    private static File newestJar(File dir) {
        File[] jars = dir.listFiles((d, name) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            return lower.startsWith("bsl-language-server") && lower.endsWith(".jar") //$NON-NLS-1$ //$NON-NLS-2$
                    && !lower.endsWith("-sources.jar") && !lower.endsWith("-javadoc.jar"); //$NON-NLS-1$ //$NON-NLS-2$
        });
        if (jars == null || jars.length == 0) {
            return null;
        }
        File best = null;
        for (File jar : jars) {
            boolean exec = jar.getName().toLowerCase(Locale.ROOT).endsWith("-exec.jar"); //$NON-NLS-1$
            boolean bestExec = best != null && best.getName().toLowerCase(Locale.ROOT).endsWith("-exec.jar"); //$NON-NLS-1$
            if (best == null || (exec && !bestExec)
                    || (exec == bestExec && jar.getName().compareTo(best.getName()) > 0)) {
                best = jar;
            }
        }
        return best;
    }

    /** java.exe: JVM самого EDT (java.home) → JAVA_HOME → PATH → типовий Zulu. */
    private static String resolveJava() {
        String javaHome = System.getProperty("java.home"); //$NON-NLS-1$
        if (javaHome != null) {
            File exe = new File(javaHome, "bin\\java.exe"); //$NON-NLS-1$
            if (exe.isFile()) {
                return exe.getAbsolutePath();
            }
        }
        String envHome = System.getenv("JAVA_HOME"); //$NON-NLS-1$
        if (envHome != null) {
            File exe = new File(envHome, "bin\\java.exe"); //$NON-NLS-1$
            if (exe.isFile()) {
                return exe.getAbsolutePath();
            }
        }
        String path = System.getenv("PATH"); //$NON-NLS-1$
        if (path != null) {
            for (String dir : path.split(";")) { //$NON-NLS-1$
                File exe = new File(dir.strip(), "java.exe"); //$NON-NLS-1$
                if (exe.isFile()) {
                    return exe.getAbsolutePath();
                }
            }
        }
        File zulu = new File("C:\\Program Files\\Zulu\\zulu-17\\bin\\java.exe"); //$NON-NLS-1$
        if (zulu.isFile()) {
            return zulu.getAbsolutePath();
        }
        throw new IllegalStateException("java.exe не знайдено (java.home, JAVA_HOME, PATH). " //$NON-NLS-1$
                + "BSL LS потребує Java 17+."); //$NON-NLS-1$
    }

    // ---------- запуск процесу ----------

    private static String runProcess(List<String> command, int timeoutSeconds)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        Thread pump = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (output) {
                        if (output.length() < 500_000) {
                            output.append(line).append('\n');
                        }
                    }
                }
            } catch (IOException ignored) {
                // процес завершився
            }
        }, "mcp-prl-codereview"); //$NON-NLS-1$
        pump.setDaemon(true);
        pump.start();
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Аналіз не завершився за " + timeoutSeconds //$NON-NLS-1$
                    + " с — збільште timeoutSeconds або звузьте pathFilter. Хвіст виводу:\n" //$NON-NLS-1$
                    + tail(snapshot(output), 2000));
        }
        pump.join(5000);
        return snapshot(output);
    }

    private static String snapshot(StringBuilder output) {
        synchronized (output) {
            return output.toString();
        }
    }

    private static String tail(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(text.length() - maxChars);
    }

    // ---------- парсинг звіту ----------

    private static JsonObject buildResult(JsonObject analysis, File srcDir, String format) {
        String srcPrefix = srcDir.getAbsolutePath().replace('\\', '/');
        JsonArray fileinfos = analysis.has("fileinfos") //$NON-NLS-1$
                ? analysis.getAsJsonArray("fileinfos") : new JsonArray(); //$NON-NLS-1$

        int total = 0;
        int filesWithIssues = 0;
        Map<String, Integer> bySeverity = new HashMap<>();
        Map<String, int[]> ruleCounts = new HashMap<>();
        Map<String, String> ruleSeverity = new HashMap<>();
        Map<String, String> ruleSample = new HashMap<>();
        JsonArray issues = new JsonArray();
        boolean issuesTruncated = false;

        for (JsonElement fileElement : fileinfos) {
            JsonObject fileInfo = fileElement.getAsJsonObject();
            JsonArray diagnostics = fileInfo.has("diagnostics") //$NON-NLS-1$
                    ? fileInfo.getAsJsonArray("diagnostics") : new JsonArray(); //$NON-NLS-1$
            if (diagnostics.size() == 0) {
                continue;
            }
            filesWithIssues++;
            String path = relativePath(fileInfo, srcPrefix);
            for (JsonElement diagnosticElement : diagnostics) {
                JsonObject diagnostic = diagnosticElement.getAsJsonObject();
                total++;
                String severity = diagnostic.has("severity") //$NON-NLS-1$
                        ? diagnostic.get("severity").getAsString().toLowerCase(Locale.ROOT) : "unknown"; //$NON-NLS-1$ //$NON-NLS-2$
                String rule = ruleCode(diagnostic);
                String message = diagnostic.has("message") ? diagnostic.get("message").getAsString() : ""; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                bySeverity.merge(severity, 1, Integer::sum);
                ruleCounts.computeIfAbsent(rule, k -> new int[1])[0]++;
                ruleSeverity.putIfAbsent(rule, severity);
                ruleSample.putIfAbsent(rule, message);
                if ("full".equals(format)) { //$NON-NLS-1$
                    if (issues.size() >= MAX_FULL_ISSUES) {
                        issuesTruncated = true;
                        continue;
                    }
                    JsonObject issue = new JsonObject();
                    issue.addProperty("path", path); //$NON-NLS-1$
                    issue.addProperty("line", startLine(diagnostic)); //$NON-NLS-1$
                    issue.addProperty("rule", rule); //$NON-NLS-1$
                    issue.addProperty("severity", severity); //$NON-NLS-1$
                    issue.addProperty("message", message); //$NON-NLS-1$
                    issues.add(issue);
                }
            }
        }

        JsonObject result = new JsonObject();
        result.addProperty("filesAnalyzed", fileinfos.size()); //$NON-NLS-1$
        result.addProperty("filesWithIssues", filesWithIssues); //$NON-NLS-1$
        result.addProperty("totalDiagnostics", total); //$NON-NLS-1$
        JsonObject severityCounts = new JsonObject();
        bySeverity.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> severityCounts.addProperty(e.getKey(), e.getValue()));
        result.add("bySeverity", severityCounts); //$NON-NLS-1$

        if ("full".equals(format)) { //$NON-NLS-1$
            result.add("issues", issues); //$NON-NLS-1$
            if (issuesTruncated) {
                result.addProperty("issuesTruncated", true); //$NON-NLS-1$
                result.addProperty("hint", //$NON-NLS-1$
                        "Понад " + MAX_FULL_ISSUES + " зауважень — звузьте pathFilter або path."); //$NON-NLS-1$ //$NON-NLS-2$
            }
        } else {
            List<Map.Entry<String, int[]>> sorted = new ArrayList<>(ruleCounts.entrySet());
            sorted.sort(Comparator.comparingInt((Map.Entry<String, int[]> e) -> e.getValue()[0]).reversed());
            JsonArray rules = new JsonArray();
            for (Map.Entry<String, int[]> entry : sorted) {
                if (rules.size() >= MAX_SUMMARY_RULES) {
                    result.addProperty("rulesTruncated", true); //$NON-NLS-1$
                    break;
                }
                JsonObject rule = new JsonObject();
                rule.addProperty("rule", entry.getKey()); //$NON-NLS-1$
                rule.addProperty("severity", ruleSeverity.get(entry.getKey())); //$NON-NLS-1$
                rule.addProperty("count", entry.getValue()[0]); //$NON-NLS-1$
                rule.addProperty("sampleMessage", ruleSample.get(entry.getKey())); //$NON-NLS-1$
                rules.add(rule);
            }
            result.add("rules", rules); //$NON-NLS-1$
            result.addProperty("hint", "Деталі із рядками — format=\"full\" (за потреби з pathFilter)."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return result;
    }

    /** Шлях файлу зі звіту (може бути file:///-URI) відносно srcDir. */
    private static String relativePath(JsonObject fileInfo, String srcPrefix) {
        String raw = fileInfo.has("path") ? fileInfo.get("path").getAsString() : ""; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (raw.startsWith("file:///")) { //$NON-NLS-1$
            raw = URLDecoder.decode(raw.substring("file:///".length()), StandardCharsets.UTF_8); //$NON-NLS-1$
        } else if (raw.startsWith("file://")) { //$NON-NLS-1$
            raw = URLDecoder.decode(raw.substring("file://".length()), StandardCharsets.UTF_8); //$NON-NLS-1$
        }
        raw = raw.replace('\\', '/');
        String lower = raw.toLowerCase(Locale.ROOT);
        String prefixLower = srcPrefix.toLowerCase(Locale.ROOT);
        if (lower.startsWith(prefixLower)) {
            raw = raw.substring(srcPrefix.length());
            if (raw.startsWith("/")) { //$NON-NLS-1$
                raw = raw.substring(1);
            }
        }
        return raw;
    }

    /** code діагностики: рядок або LSP-об'єкт {value: ...}. */
    private static String ruleCode(JsonObject diagnostic) {
        if (!diagnostic.has("code")) { //$NON-NLS-1$
            return "unknown"; //$NON-NLS-1$
        }
        JsonElement code = diagnostic.get("code"); //$NON-NLS-1$
        if (code.isJsonObject()) {
            JsonObject object = code.getAsJsonObject();
            return object.has("value") ? object.get("value").getAsString() : object.toString(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return code.getAsString();
    }

    /** Рядок початку діагностики, 1-based (у звіті LSP-range 0-based). */
    private static int startLine(JsonObject diagnostic) {
        try {
            return diagnostic.getAsJsonObject("range") //$NON-NLS-1$
                    .getAsJsonObject("start") //$NON-NLS-1$
                    .get("line").getAsInt() + 1; //$NON-NLS-1$
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static void deleteRecursively(Path root) {
        try {
            if (!Files.exists(root)) {
                return;
            }
            Files.walk(root)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> path.toFile().delete());
        } catch (IOException ignored) {
            // тимчасові файли — приберуться системою
        }
    }
}
