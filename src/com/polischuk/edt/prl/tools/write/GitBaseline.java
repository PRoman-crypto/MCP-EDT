/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.write;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IProject;

/**
 * Вміст файлу з git — базлайн для diff_module.
 *
 * <p>Через CLI, а не JGit: git на машині розробника 1С є завжди, а тягнути в плагін
 * ще одну бібліотеку заради `show` немає сенсу. Процес запускається без оболонки
 * (аргументи масивом), тому шляхи з кирилицею і пробілами проходять як є.
 */
final class GitBaseline {

    private static final long TIMEOUT_SECONDS = 20;

    /** Результат читання: вміст, шлях у репозиторії, ознака «файла в цьому ref немає». */
    record Result(String content, String repositoryRoot, String repositoryPath, boolean absentInRef) {
    }

    private GitBaseline() {
    }

    /**
     * Вміст файлу в указаному ref.
     *
     * @throws IllegalStateException якщо git недоступний або проєкт поза репозиторієм
     */
    static Result read(IProject project, String projectRelativePath, String ref) {
        Path projectLocation = toPath(project);
        String root = run(projectLocation, "rev-parse", "--show-toplevel").strip(); //$NON-NLS-1$ //$NON-NLS-2$
        if (root.isEmpty()) {
            throw new IllegalStateException("Проєкт " + project.getName() //$NON-NLS-1$
                    + " не лежить у git-репозиторії — базлайн з git недоступний. " //$NON-NLS-1$
                    + "Використайте against=session (порівняння зі станом до правок цієї сесії)."); //$NON-NLS-1$
        }
        Path repositoryRoot = Path.of(root);
        Path file = projectLocation.resolve(projectRelativePath.replace('\\', '/'));
        String repositoryPath = repositoryRoot.relativize(file).toString().replace('\\', '/');

        Process process = start(repositoryRoot, "show", ref + ":" + repositoryPath); //$NON-NLS-1$ //$NON-NLS-2$
        byte[] out = readAll(process.getInputStream());
        String error = new String(readAll(process.getErrorStream()), StandardCharsets.UTF_8);
        int exitCode = waitFor(process);
        if (exitCode != 0) {
            // git не розрізняє «немає файлу» і «немає ref» кодом виходу — дивимось текст
            if (error.contains("does not exist") || error.contains("exists on disk, but not in")) { //$NON-NLS-1$ //$NON-NLS-2$
                return new Result(null, repositoryRoot.toString(), repositoryPath, true);
            }
            throw new IllegalStateException("git show " + ref + ":" + repositoryPath //$NON-NLS-1$ //$NON-NLS-2$
                    + " → код " + exitCode + ": " + error.strip()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return new Result(stripBom(new String(out, StandardCharsets.UTF_8)),
                repositoryRoot.toString(), repositoryPath, false);
    }

    /** Короткий опис ref: хеш і заголовок коміту — щоб було видно, з чим порівняли. */
    static String describeRef(IProject project, String ref) {
        try {
            return run(toPath(project), "log", "-1", "--format=%h %s", ref).strip(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Path toPath(IProject project) {
        if (project.getLocation() == null) {
            throw new IllegalStateException("Проєкт " + project.getName() + " не має шляху у файловій системі"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return project.getLocation().toFile().toPath();
    }

    private static String run(Path workDir, String... args) {
        Process process = start(workDir, args);
        String out = new String(readAll(process.getInputStream()), StandardCharsets.UTF_8);
        String error = new String(readAll(process.getErrorStream()), StandardCharsets.UTF_8);
        int exitCode = waitFor(process);
        if (exitCode != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) //$NON-NLS-1$ //$NON-NLS-2$
                    + " → код " + exitCode + ": " + error.strip()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return out;
    }

    private static Process start(Path workDir, String... args) {
        String[] command = new String[args.length + 1];
        command[0] = "git"; //$NON-NLS-1$
        System.arraycopy(args, 0, command, 1, args.length);
        try {
            return new ProcessBuilder(command).directory(workDir.toFile()).start();
        } catch (IOException e) {
            throw new IllegalStateException("Не вдалося запустити git: " + e.getMessage() //$NON-NLS-1$
                    + ". Перевірте, що git є у PATH.", e); //$NON-NLS-1$
        }
    }

    private static byte[] readAll(InputStream stream) {
        try (InputStream in = stream) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static int waitFor(Process process) {
        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("git не відповів за " + TIMEOUT_SECONDS + " с"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return process.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IllegalStateException("Читання git перервано", e); //$NON-NLS-1$
        }
    }

    private static String stripBom(String text) {
        return !text.isEmpty() && text.charAt(0) == '﻿' ? text.substring(1) : text;
    }
}
