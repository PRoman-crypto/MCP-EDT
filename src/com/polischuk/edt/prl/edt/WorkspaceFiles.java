/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.edt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Path;

/** Читання файлів проєкту EDT (BSL та інших) з коректним кодуванням. */
public final class WorkspaceFiles {

    private WorkspaceFiles() {
    }

    public static IFile file(IProject project, String projectRelativePath) {
        String normalized = projectRelativePath.replace('\\', '/');
        IFile file = project.getFile(new Path(normalized));
        if (!file.exists()) {
            throw new IllegalArgumentException("Файл не знайдено в проєкті '" + project.getName() //$NON-NLS-1$
                    + "': " + normalized + ". Шлях має бути відносним до кореня проєкту, наприклад " //$NON-NLS-1$ //$NON-NLS-2$
                    + "src/CommonModules/МійМодуль/Module.bsl (див. list_modules)."); //$NON-NLS-1$
        }
        return file;
    }

    public static String read(IFile file) {
        try (InputStream stream = file.getContents(true)) {
            Charset charset = Charset.forName(file.getCharset(true));
            String text = new String(stream.readAllBytes(), charset);
            if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
                text = text.substring(1);
            }
            return text;
        } catch (CoreException | IOException e) {
            throw new IllegalStateException("Не вдалося прочитати " + file.getFullPath() + ": " + e.getMessage(), e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    public static String read(IProject project, String projectRelativePath) {
        return read(file(project, projectRelativePath));
    }

    /** Чи починається файл із UTF-8 BOM (EF BB BF) — важливо зберегти його при записі. */
    public static boolean hasUtf8Bom(IFile file) {
        try (InputStream stream = file.getContents(true)) {
            byte[] head = stream.readNBytes(3);
            return head.length == 3 && head[0] == (byte) 0xEF && head[1] == (byte) 0xBB && head[2] == (byte) 0xBF;
        } catch (CoreException | IOException e) {
            return false;
        }
    }

    /** Рекурсивний обхід контейнера з викликом consumer для файлів із розширенням extension. */
    public static void walk(IContainer container, String extension, Consumer<IFile> consumer) {
        try {
            for (IResource member : container.members()) {
                if (member instanceof IFile file) {
                    if (extension == null || extension.equalsIgnoreCase(file.getFileExtension())) {
                        consumer.accept(file);
                    }
                } else if (member instanceof IContainer child) {
                    walk(child, extension, consumer);
                }
            }
        } catch (CoreException e) {
            throw new IllegalStateException("Помилка обходу " + container.getFullPath() + ": " + e.getMessage(), e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
}
