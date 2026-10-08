/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.jobs;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Асинхронні джоби для довгих зовнішніх процесів (vrunner, збірки, тести):
 * старт повертає jobId одразу, статус і хвіст виводу — окремим запитом.
 */
public final class JobManager {

    private static final int MAX_OUTPUT_CHARS = 2_000_000;
    private static final Map<String, JobManager> JOBS = new ConcurrentHashMap<>();

    private final String id = UUID.randomUUID().toString().substring(0, 8);
    private final String commandLine;
    private final long startedAt = System.currentTimeMillis();
    private final StringBuilder output = new StringBuilder();
    private final Process process; // null для внутрішніх задач (worker-потік)
    private final Thread worker;   // null для зовнішніх процесів
    private volatile Integer taskExitCode; // для worker-задач: 0 — успіх, 1 — помилка
    private volatile JsonElement taskResult; // підсумок worker-задачі з результатом (yaxunit_tests)
    private volatile String taskError;
    private volatile boolean truncated;

    /** Задача з підсумком у JSON; {@code log} дописує рядки прогресу у вивід джоби. */
    public interface ResultTask {
        JsonElement run(Consumer<String> log) throws Exception;
    }

    private JobManager(List<String> command, File workDir, Charset outputCharset) throws IOException {
        this.commandLine = String.join(" ", command); //$NON-NLS-1$
        this.worker = null;
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (workDir != null) {
            builder.directory(workDir);
        }
        process = builder.start();
        pumpOutput(outputCharset);
    }

    private JobManager(Process externalProcess, String label, Charset outputCharset) {
        this.commandLine = label;
        this.worker = null;
        this.process = externalProcess;
        pumpOutput(outputCharset);
    }

    private JobManager(String label, java.util.concurrent.Callable<String> task) {
        this.commandLine = label;
        this.process = null;
        this.worker = new Thread(() -> {
            try {
                String result = task.call();
                appendLine(result == null ? "done" : result); //$NON-NLS-1$
                taskExitCode = 0;
            } catch (Throwable e) {
                appendLine(e.getClass().getSimpleName() + ": " + e.getMessage()); //$NON-NLS-1$
                taskExitCode = 1;
            }
        }, "mcp-prl-task-" + id); //$NON-NLS-1$
        worker.setDaemon(true);
        worker.start();
    }

    private JobManager(String label, ResultTask task) {
        this.commandLine = label;
        this.process = null;
        this.worker = new Thread(() -> {
            try {
                taskResult = task.run(this::appendLine);
                appendLine("done"); //$NON-NLS-1$
                taskExitCode = 0;
            } catch (Throwable e) {
                taskError = e.getClass().getSimpleName() + ": " + e.getMessage(); //$NON-NLS-1$
                appendLine(taskError);
                taskExitCode = 1;
            }
        }, "mcp-prl-task-" + id); //$NON-NLS-1$
        worker.setDaemon(true);
        worker.start();
    }

    private void pumpOutput(Charset outputCharset) {
        Thread reader = new Thread(() -> {
            try (BufferedReader stream = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), outputCharset))) {
                String line;
                while ((line = stream.readLine()) != null) {
                    appendLine(line);
                }
            } catch (IOException ignored) {
                // процес завершився — потік читання закривається
            }
        }, "mcp-prl-job-" + id); //$NON-NLS-1$
        reader.setDaemon(true);
        reader.start();
    }

    private void appendLine(String line) {
        synchronized (output) {
            if (output.length() < MAX_OUTPUT_CHARS) {
                output.append(line).append('\n');
            } else {
                truncated = true;
            }
        }
    }

    private boolean isAlive() {
        return process != null ? process.isAlive() : worker.isAlive();
    }

    private Integer exitCode() {
        if (process != null) {
            return process.isAlive() ? null : Integer.valueOf(process.exitValue());
        }
        return taskExitCode;
    }

    public static JsonObject start(List<String> command, File workDir, Charset outputCharset) throws IOException {
        return register(new JobManager(command, workDir, outputCharset));
    }

    /** Обгортає вже запущений зовнішній процес (напр., 1С:Підприємство від IApplicationManager). */
    public static JsonObject wrapProcess(Process externalProcess, String label, Charset outputCharset) {
        return register(new JobManager(externalProcess, label, outputCharset));
    }

    /** Довга внутрішня операція (оновлення ІБ тощо) у фоновому потоці. */
    public static JsonObject startTask(String label, java.util.concurrent.Callable<String> task) {
        return register(new JobManager(label, task));
    }

    /**
     * Довга внутрішня операція з підсумком: результат читається з {@link #status} (поле result),
     * а викликач може почекати завершення через {@link #await}, не тримаючи HTTP-запит.
     */
    public static JsonObject startResultTask(String label, ResultTask task) {
        return register(new JobManager(label, task));
    }

    /** Чекає завершення джоби не довше за {@code millis}; true — джоба вже завершена. */
    public static boolean await(String jobId, long millis) throws InterruptedException {
        JobManager job = JOBS.get(jobId);
        if (job == null) {
            throw new IllegalArgumentException("Джоба не знайдена: " + jobId); //$NON-NLS-1$
        }
        if (job.worker != null) {
            job.worker.join(millis);
        }
        return !job.isAlive();
    }

    private static JsonObject register(JobManager job) {
        JOBS.put(job.id, job);
        JsonObject result = new JsonObject();
        result.addProperty("jobId", job.id); //$NON-NLS-1$
        result.addProperty("command", job.commandLine); //$NON-NLS-1$
        result.addProperty("status", "running"); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("hint", "Статус і вивід — get_job_status із цим jobId."); //$NON-NLS-1$ //$NON-NLS-2$
        return result;
    }

    public static JsonObject status(String jobId, int tailLines) {
        JobManager job = JOBS.get(jobId);
        if (job == null) {
            throw new IllegalArgumentException("Джоба не знайдена: " + jobId); //$NON-NLS-1$
        }
        JsonObject result = new JsonObject();
        result.addProperty("jobId", job.id); //$NON-NLS-1$
        result.addProperty("command", job.commandLine); //$NON-NLS-1$
        boolean alive = job.isAlive();
        result.addProperty("status", alive ? "running" : "finished"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Integer exitCode = job.exitCode();
        if (exitCode != null) {
            result.addProperty("exitCode", exitCode); //$NON-NLS-1$
        }
        result.addProperty("elapsedSeconds", (System.currentTimeMillis() - job.startedAt) / 1000); //$NON-NLS-1$
        if (!alive && job.taskResult != null) {
            result.add("result", job.taskResult); //$NON-NLS-1$
        }
        if (!alive && job.taskError != null) {
            result.addProperty("error", job.taskError); //$NON-NLS-1$
        }
        String text;
        synchronized (job.output) {
            text = job.output.toString();
        }
        String[] lines = text.split("\n", -1); //$NON-NLS-1$
        int from = Math.max(0, lines.length - Math.max(1, tailLines));
        result.addProperty("totalOutputLines", lines.length); //$NON-NLS-1$
        if (job.truncated) {
            result.addProperty("outputTruncated", true); //$NON-NLS-1$
        }
        StringBuilder tail = new StringBuilder();
        for (int i = from; i < lines.length; i++) {
            tail.append(lines[i]);
            if (i < lines.length - 1) {
                tail.append('\n');
            }
        }
        result.addProperty("outputTail", tail.toString()); //$NON-NLS-1$
        return result;
    }

    public static JsonObject stop(String jobId) {
        JobManager job = JOBS.get(jobId);
        if (job == null) {
            throw new IllegalArgumentException("Джоба не знайдена: " + jobId); //$NON-NLS-1$
        }
        if (job.process != null) {
            job.process.destroy();
        } else if (job.worker != null) {
            job.worker.interrupt();
        }
        JsonObject result = new JsonObject();
        result.addProperty("jobId", jobId); //$NON-NLS-1$
        result.addProperty("stopped", true); //$NON-NLS-1$
        return result;
    }

    public static JsonArray list() {
        JsonArray result = new JsonArray();
        for (JobManager job : JOBS.values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("jobId", job.id); //$NON-NLS-1$
            entry.addProperty("command", job.commandLine); //$NON-NLS-1$
            entry.addProperty("status", job.isAlive() ? "running" : "finished"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            result.add(entry);
        }
        return result;
    }
}
