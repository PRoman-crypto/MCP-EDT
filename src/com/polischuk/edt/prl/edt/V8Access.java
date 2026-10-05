/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.edt;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;

/** Доступ до 1С-проєктів workspace через IV8ProjectManager. */
public final class V8Access {

    private V8Access() {
    }

    public static IV8ProjectManager projectManager() {
        return EdtServices.require(IV8ProjectManager.class);
    }

    public static List<IV8Project> v8Projects() {
        return new ArrayList<>(projectManager().getProjects());
    }

    /** Проєкт за іменем; якщо name порожній і 1С-проєкт у workspace один — повертає його. */
    public static IV8Project resolveProject(String name) {
        List<IV8Project> projects = v8Projects();
        if (name == null || name.isBlank()) {
            if (projects.size() == 1) {
                return projects.get(0);
            }
            throw new IllegalArgumentException("У workspace " + projects.size() //$NON-NLS-1$
                    + " 1С-проєктів — вкажіть параметр 'project'. Доступні: " + projectNames(projects)); //$NON-NLS-1$
        }
        for (IV8Project project : projects) {
            if (name.equalsIgnoreCase(project.getProject().getName())) {
                return project;
            }
        }
        throw new IllegalArgumentException("Проєкт не знайдено: " + name //$NON-NLS-1$
                + ". Доступні: " + projectNames(projects)); //$NON-NLS-1$
    }

    /** Eclipse-проєкт (для роботи з файлами) за тими самими правилами. */
    public static IProject resolveEclipseProject(String name) {
        return resolveProject(name).getProject();
    }

    /**
     * Конфігурація проєкту (для конфігурацій і розширень). Виклик через Java-рефлексію,
     * бо getConfiguration() оголошений в різних підтипах IV8Project.
     */
    public static EObject configuration(IV8Project project) {
        try {
            Object result = project.getClass().getMethod("getConfiguration").invoke(project); //$NON-NLS-1$
            return result instanceof EObject configuration ? configuration : null;
        } catch (NoSuchMethodException e) {
            return null;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("getConfiguration() failed: " + e, e); //$NON-NLS-1$
        }
    }

    public static EObject requireConfiguration(String projectName) {
        IV8Project project = resolveProject(projectName);
        EObject configuration = configuration(project);
        if (configuration == null) {
            throw new IllegalArgumentException("Проєкт '" + project.getProject().getName() //$NON-NLS-1$
                    + "' не є конфігурацією або розширенням (" + project.getClass().getSimpleName() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return configuration;
    }

    private static String projectNames(List<IV8Project> projects) {
        List<String> names = new ArrayList<>();
        projects.forEach(p -> names.add(p.getProject().getName()));
        return String.join(", ", names); //$NON-NLS-1$
    }

    /** Усі відкриті Eclipse-проєкти workspace (для файлових інструментів). */
    public static List<IProject> openEclipseProjects() {
        List<IProject> result = new ArrayList<>();
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (project.isOpen()) {
                result.add(project);
            }
        }
        return result;
    }
}
