/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.write;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.polischuk.edt.prl.edt.EdtServices;
import com.polischuk.edt.prl.edt.Emf;
import com.polischuk.edt.prl.edt.MetadataIndex;
import com.polischuk.edt.prl.edt.V8Access;

import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.md.extension.adopt.IModelObjectAdopter;

/**
 * Операції роботи з розширеннями конфігурації (CFE): заимствование (adopt)
 * об'єктів базової конфігурації в розширення та перегляд заимствованных.
 *
 * Використовує штатний сервіс EDT {@link IModelObjectAdopter} (OSGi-сервіс бандла
 * com._1c.g5.v8.dt.md.extension). ВАЖЛИВО: adoptAndAttach сам відкриває
 * BM-транзакцію через getGlobalContext().execute(...) усередині workspace-runnable
 * (плюс тимчасово вимикає implicit waiting derived data), тому ці операції
 * НЕ МОЖНА викликати зсередини вже відкритої BM-транзакції EditMetadataTool —
 * вони викликаються до створення AbstractBmTask. dryRun тут — це повна валідація
 * входів (проєкт розширення, вихідний об'єкт, isAdoptable, чи вже заимствован)
 * без виклику adoptAndAttach.
 */
public final class ExtensionOps {

    private ExtensionOps() {
    }

    /**
     * Заимствование об'єкта базової конфігурації в розширення.
     * Параметри arguments: project (проєкт РОЗШИРЕННЯ; необов'язковий, якщо розширення одне),
     * kind + name (об'єкт БАЗОВОЇ конфігурації), необов'язково tabularSection/attribute
     * (заимствовать конкретний дочірній елемент — батьки заимствуются автоматично), dryRun.
     */
    public static JsonObject adoptObject(JsonObject arguments) {
        String projectName = optional(arguments, "project"); //$NON-NLS-1$
        String kind = required(arguments, "kind"); //$NON-NLS-1$
        String name = required(arguments, "name"); //$NON-NLS-1$
        boolean dryRun = arguments.has("dryRun") && arguments.get("dryRun").getAsBoolean(); //$NON-NLS-1$ //$NON-NLS-2$

        IExtensionProject extension = resolveExtensionProject(projectName);
        IConfigurationProject parent = extension.getParent();
        if (parent == null) {
            throw new IllegalStateException("Проєкт розширення '" + extension.getProject().getName() //$NON-NLS-1$
                    + "' не прив'язаний до базової конфігурації (getParent() == null). " //$NON-NLS-1$
                    + "Перевірте властивості проєкту в EDT."); //$NON-NLS-1$
        }
        EObject baseConfiguration = parent.getConfiguration();
        if (baseConfiguration == null) {
            throw new IllegalStateException("Базова конфігурація проєкту '" //$NON-NLS-1$
                    + parent.getProject().getName() + "' ще не завантажена в модель EDT."); //$NON-NLS-1$
        }

        EObject source = MetadataIndex.findObject(baseConfiguration, kind, name);
        String fqn = source.eClass().getName() + "." + Emf.name(source); //$NON-NLS-1$
        EObject target = resolveChild(source, arguments);
        String targetLabel = target == source ? fqn
                : fqn + " → " + target.eClass().getName() + "." + Emf.name(target); //$NON-NLS-1$ //$NON-NLS-2$

        IModelObjectAdopter adopter = EdtServices.require(IModelObjectAdopter.class);

        JsonObject result = new JsonObject();
        result.addProperty("operation", "adoptObject"); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("extensionProject", extension.getProject().getName()); //$NON-NLS-1$
        result.addProperty("baseProject", parent.getProject().getName()); //$NON-NLS-1$
        result.addProperty("object", targetLabel); //$NON-NLS-1$
        result.addProperty("dryRun", dryRun); //$NON-NLS-1$

        EObject existing = adopter.getAdopted(target, extension);
        if (existing != null) {
            result.addProperty("applied", false); //$NON-NLS-1$
            result.addProperty("alreadyAdopted", true); //$NON-NLS-1$
            result.add("adopted", describeAdopted(existing)); //$NON-NLS-1$
            result.addProperty("note", "Об'єкт уже заимствован у це розширення — повторне заимствование не потрібне."); //$NON-NLS-1$ //$NON-NLS-2$
            return result;
        }
        if (!adopter.isAdoptable(target)) {
            throw new IllegalArgumentException("Об'єкт не підлягає заимствованию (isAdoptable=false): " //$NON-NLS-1$
                    + targetLabel + ". Такий вид метаданих не підтримується механізмом розширень."); //$NON-NLS-1$
        }

        if (dryRun) {
            result.addProperty("applied", false); //$NON-NLS-1$
            result.addProperty("adoptable", true); //$NON-NLS-1$
            result.addProperty("note", //$NON-NLS-1$
                    "Перевірки пройдено: проєкт розширення знайдено, об'єкт існує в базовій конфігурації " //$NON-NLS-1$
                    + "і підлягає заимствованию. Повторіть без dryRun для застосування."); //$NON-NLS-1$
            return result;
        }

        EObject adopted;
        try {
            adopted = adopter.adoptAndAttach(target, extension, new NullProgressMonitor());
        } catch (CoreException e) {
            throw new IllegalStateException("adoptAndAttach failed: " + e.getMessage(), e); //$NON-NLS-1$
        }
        result.addProperty("applied", true); //$NON-NLS-1$
        result.add("adopted", describeAdopted(adopted)); //$NON-NLS-1$
        result.addProperty("note", //$NON-NLS-1$
                "Об'єкт заимствован; серіалізація .mdo у вихідники розширення відбувається автоматично. " //$NON-NLS-1$
                + "Далі: addAttribute (реквізит у розширенні) або write_module_source (&Вместо/&После/&Перед)."); //$NON-NLS-1$
        return result;
    }

    /** Перелік заимствованных (Adopted) top-об'єктів проєкту розширення. */
    public static JsonObject listAdoptedObjects(String projectName) {
        IExtensionProject extension = resolveExtensionProject(projectName);
        EObject configuration = extension.getConfiguration();
        if (configuration == null) {
            throw new IllegalStateException("Конфігурація розширення '" + extension.getProject().getName() //$NON-NLS-1$
                    + "' ще не завантажена в модель EDT."); //$NON-NLS-1$
        }
        JsonArray adopted = new JsonArray();
        JsonArray own = new JsonArray();
        for (EReference reference : configuration.eClass().getEAllReferences()) {
            if (!reference.isMany() || !reference.isContainment()) {
                continue;
            }
            Object value = configuration.eGet(reference);
            if (!(value instanceof List<?> list)) {
                continue;
            }
            for (Object item : list) {
                if (!(item instanceof EObject object) || Emf.name(object) == null) {
                    continue;
                }
                String belonging = Emf.str(object, "objectBelonging"); //$NON-NLS-1$
                if ("Adopted".equalsIgnoreCase(belonging)) { //$NON-NLS-1$
                    adopted.add(describeAdopted(object));
                } else if (belonging != null) {
                    // власний (Native) об'єкт розширення
                    JsonObject entry = new JsonObject();
                    entry.addProperty("kind", object.eClass().getName()); //$NON-NLS-1$
                    entry.addProperty("name", Emf.name(object)); //$NON-NLS-1$
                    own.add(entry);
                }
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("operation", "listAdopted"); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("extensionProject", extension.getProject().getName()); //$NON-NLS-1$
        IConfigurationProject parent = extension.getParent();
        if (parent != null) {
            result.addProperty("baseProject", parent.getProject().getName()); //$NON-NLS-1$
        }
        String prefix = Emf.str(configuration, "namePrefix"); //$NON-NLS-1$
        if (prefix != null && !prefix.isBlank()) {
            result.addProperty("namePrefix", prefix); //$NON-NLS-1$
        }
        String purpose = Emf.str(configuration, "configurationExtensionPurpose"); //$NON-NLS-1$
        if (purpose != null) {
            result.addProperty("purpose", purpose); //$NON-NLS-1$
        }
        result.addProperty("adoptedCount", adopted.size()); //$NON-NLS-1$
        result.add("adopted", adopted); //$NON-NLS-1$
        result.addProperty("ownCount", own.size()); //$NON-NLS-1$
        result.add("own", own); //$NON-NLS-1$
        return result;
    }

    /**
     * Проєкт розширення за іменем; якщо name порожній і розширення у workspace одне —
     * повертає його. Чемно повідомляє, коли розширень немає взагалі.
     */
    public static IExtensionProject resolveExtensionProject(String name) {
        List<IExtensionProject> extensions =
                new ArrayList<>(V8Access.projectManager().getProjects(IExtensionProject.class));
        if (extensions.isEmpty()) {
            throw new IllegalArgumentException("У workspace немає проєктів розширення (CFE). " //$NON-NLS-1$
                    + "Створіть його в EDT: File → New → Project → «Розширення конфігурації», " //$NON-NLS-1$
                    + "вкажіть базову конфігурацію — і повторіть виклик."); //$NON-NLS-1$
        }
        if (name == null || name.isBlank()) {
            if (extensions.size() == 1) {
                return extensions.get(0);
            }
            throw new IllegalArgumentException("У workspace " + extensions.size() //$NON-NLS-1$
                    + " проєктів розширення — вкажіть параметр 'project'. Доступні: " //$NON-NLS-1$
                    + extensionNames(extensions));
        }
        for (IExtensionProject extension : extensions) {
            if (name.equalsIgnoreCase(extension.getProject().getName())) {
                return extension;
            }
        }
        // ім'я вказує на існуючий, але не-розширювальний проєкт — окреме чемне повідомлення
        for (IV8Project project : V8Access.v8Projects()) {
            if (name.equalsIgnoreCase(project.getProject().getName())) {
                throw new IllegalArgumentException("Проєкт '" + name + "' не є розширенням (" //$NON-NLS-1$ //$NON-NLS-2$
                        + project.getClass().getSimpleName() + "). Проєкти розширень: " //$NON-NLS-1$
                        + extensionNames(extensions));
            }
        }
        throw new IllegalArgumentException("Проєкт розширення не знайдено: " + name //$NON-NLS-1$
                + ". Доступні: " + extensionNames(extensions)); //$NON-NLS-1$
    }

    /** Дочірній елемент для заимствования: ТЧ і/або реквізит; без параметрів — сам top-об'єкт. */
    private static EObject resolveChild(EObject topObject, JsonObject arguments) {
        EObject owner = topObject;
        String section = optional(arguments, "tabularSection"); //$NON-NLS-1$
        if (section != null) {
            owner = findByName(owner, "tabularSections", section); //$NON-NLS-1$
        }
        String attribute = optional(arguments, "attribute"); //$NON-NLS-1$
        if (attribute != null) {
            owner = findByName(owner, "attributes", attribute); //$NON-NLS-1$
        }
        return owner;
    }

    private static EObject findByName(EObject owner, String collection, String childName) {
        Object value = Emf.get(owner, collection);
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof EObject child && childName.equalsIgnoreCase(Emf.name(child))) {
                    return child;
                }
            }
        }
        throw new IllegalArgumentException("Елемент не знайдено: " + childName + " у " + collection //$NON-NLS-1$ //$NON-NLS-2$
                + " об'єкта " + Emf.name(owner)); //$NON-NLS-1$
    }

    /** Опис заимствованного об'єкта: вид, ім'я, uuid, зв'язок із базовим об'єктом. */
    private static JsonObject describeAdopted(EObject object) {
        JsonObject entry = new JsonObject();
        entry.addProperty("kind", object.eClass().getName()); //$NON-NLS-1$
        entry.addProperty("name", Emf.name(object)); //$NON-NLS-1$
        String uuid = Emf.str(object, "uuid"); //$NON-NLS-1$
        if (uuid != null) {
            entry.addProperty("uuid", uuid); //$NON-NLS-1$
        }
        String extended = Emf.str(object, "extendedConfigurationObject"); //$NON-NLS-1$
        if (extended != null) {
            entry.addProperty("extendedConfigurationObject", extended); //$NON-NLS-1$
        }
        String belonging = Emf.str(object, "objectBelonging"); //$NON-NLS-1$
        if (belonging != null) {
            entry.addProperty("objectBelonging", belonging); //$NON-NLS-1$
        }
        return entry;
    }

    private static String required(JsonObject arguments, String name) {
        if (!arguments.has(name) || arguments.get(name).getAsString().isBlank()) {
            throw new IllegalArgumentException("Обов'язковий параметр відсутній: " + name); //$NON-NLS-1$
        }
        return arguments.get(name).getAsString();
    }

    private static String optional(JsonObject arguments, String name) {
        return arguments.has(name) && !arguments.get(name).isJsonNull()
                && !arguments.get(name).getAsString().isBlank() ? arguments.get(name).getAsString() : null;
    }

    private static String extensionNames(List<IExtensionProject> extensions) {
        List<String> names = new ArrayList<>();
        extensions.forEach(p -> names.add(p.getProject().getName()));
        return String.join(", ", names); //$NON-NLS-1$
    }
}
