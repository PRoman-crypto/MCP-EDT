/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.export;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.polischuk.edt.prl.edt.EdtServices;
import com.polischuk.edt.prl.edt.V8Access;
import com.polischuk.edt.prl.tools.McpTool;

import com._1c.g5.v8.dt.core.platform.IExternalObjectProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.platform.services.core.dump.IExternalObjectDumper;
import com._1c.g5.v8.dt.platform.services.core.dump.IExternalObjectDumpSupport;

/**
 * Збирання зовнішньої обробки (.epf) або зовнішнього звіту (.erf) з вихідників
 * проєкту EDT. Використовує IExternalObjectDumper з
 * com._1c.g5.v8.dt.platform.services.core: EDT експортує об'єкт у XML у
 * тимчасовий каталог і конвертує його в бінарний файл через товстий клієнт
 * 1С:Підприємство (convertXmlExternalToBinary) пов'язаної інформаційної бази.
 * Тому потрібні: проєкт зовнішніх обробок/звітів у workspace, пов'язана ІБ
 * і встановлена платформа. Це операція читання — WriteGate не застосовується;
 * запис за межі workspace явно санкціонований обов'язковим параметром outputPath.
 */
public final class ExportObjectTool implements McpTool {

    private static final String EPF = ".epf"; //$NON-NLS-1$
    private static final String ERF = ".erf"; //$NON-NLS-1$
    private static final String CLASS_DATA_PROCESSOR = "ExternalDataProcessor"; //$NON-NLS-1$
    private static final String CLASS_REPORT = "ExternalReport"; //$NON-NLS-1$

    @Override
    public String name() {
        return "export_object"; //$NON-NLS-1$
    }

    @Override
    public String description() {
        return "Збирає зовнішню обробку (.epf) або зовнішній звіт (.erf) з вихідників проєкту EDT " //$NON-NLS-1$
                + "у бінарний файл outputPath. Потрібні проєкт зовнішніх обробок/звітів у workspace, " //$NON-NLS-1$
                + "пов'язана інформаційна база і встановлена платформа 1С (збирання виконує товстий клієнт)."; //$NON-NLS-1$
    }

    @Override
    public JsonObject inputSchema() {
        return JsonParser.parseString("""
                {"type":"object","properties":{
                  "project":{"type":"string","description":"Ім'я проєкту зовнішніх обробок/звітів (необов'язково, якщо такий проєкт один або objectName збігається з іменем проєкту)"},
                  "objectName":{"type":"string","description":"Ім'я зовнішньої обробки/звіту в проєкті або ім'я самого проєкту (необов'язково, якщо об'єкт у проєкті один)"},
                  "outputPath":{"type":"string","description":"Абсолютний шлях результату: .epf для обробки, .erf для звіту"}
                },"required":["outputPath"]}""").getAsJsonObject(); //$NON-NLS-1$
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
        String outputPath = required(arguments, "outputPath"); //$NON-NLS-1$
        String projectName = optional(arguments, "project"); //$NON-NLS-1$
        String objectName = optional(arguments, "objectName"); //$NON-NLS-1$

        Path target = Path.of(outputPath);
        if (!target.isAbsolute()) {
            throw new IllegalArgumentException("outputPath має бути абсолютним шляхом: " + outputPath); //$NON-NLS-1$
        }
        String extension = extensionOf(target);

        IExternalObjectProject externalProject = resolveExternalProject(projectName, objectName);
        IProject project = externalProject.getProject();
        MdObject object = resolveObject(externalProject, projectName, objectName);
        checkExtensionMatchesObject(object, extension);

        // передперевірка EDT: пов'язана ІБ, платформа тощо — щоб віддати зрозумілу помилку
        IExternalObjectDumpSupport dumpSupport = EdtServices.require(IExternalObjectDumpSupport.class);
        IStatus status = dumpSupport.validateDumpGeneration(project);
        if (status.getSeverity() >= IStatus.ERROR) {
            throw new IllegalStateException("Збирання неможливе для проєкту '" + project.getName() //$NON-NLS-1$
                    + "': " + status.getMessage() //$NON-NLS-1$
                    + ". Перевірте, що з проєктом пов'язана інформаційна база і встановлена платформа 1С."); //$NON-NLS-1$
        }

        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        // dump експортує об'єкт у XML у тимчасовий каталог і збирає бінарник товстим клієнтом;
        // UI-тред не потрібен, прогрес не транслюється — достатньо NullProgressMonitor
        EdtServices.require(IExternalObjectDumper.class)
                .dump(project, object, target, new NullProgressMonitor());

        if (!Files.isRegularFile(target)) {
            throw new IllegalStateException("Збирання завершилось без помилки, але файл не створено: " + target //$NON-NLS-1$
                    + ". Перевірте журнал EDT (Error Log)."); //$NON-NLS-1$
        }
        JsonObject result = new JsonObject();
        result.addProperty("project", project.getName()); //$NON-NLS-1$
        result.addProperty("object", object.getName()); //$NON-NLS-1$
        result.addProperty("class", object.eClass().getName()); //$NON-NLS-1$
        result.addProperty("outputPath", target.toString()); //$NON-NLS-1$
        result.addProperty("sizeBytes", Files.size(target)); //$NON-NLS-1$
        return result;
    }

    /**
     * Проєкт зовнішніх об'єктів: за параметром project; інакше — за objectName,
     * якщо він збігається з іменем проєкту; інакше — єдиний такий проєкт workspace.
     */
    private static IExternalObjectProject resolveExternalProject(String projectName, String objectName) {
        if (projectName != null) {
            IV8Project project = V8Access.resolveProject(projectName);
            if (project instanceof IExternalObjectProject externalProject) {
                return externalProject;
            }
            throw new IllegalArgumentException("Проєкт '" + project.getProject().getName() //$NON-NLS-1$
                    + "' не є проєктом зовнішніх обробок/звітів (" + project.getClass().getSimpleName() //$NON-NLS-1$
                    + "). Експорт .epf/.erf можливий лише з такого проєкту."); //$NON-NLS-1$
        }
        List<IExternalObjectProject> candidates = externalProjects();
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException(
                    "У workspace немає проєктів зовнішніх обробок/звітів — збирати нема з чого. " //$NON-NLS-1$
                    + "Створіть проєкт зовнішньої обробки в EDT (File > New > Project) або відкрийте наявний."); //$NON-NLS-1$
        }
        if (objectName != null) {
            for (IExternalObjectProject candidate : candidates) {
                if (objectName.equalsIgnoreCase(candidate.getProject().getName())) {
                    return candidate;
                }
            }
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        throw new IllegalArgumentException("У workspace " + candidates.size() //$NON-NLS-1$
                + " проєктів зовнішніх обробок/звітів — вкажіть параметр 'project'. Доступні: " //$NON-NLS-1$
                + names(candidates));
    }

    /** Об'єкт у проєкті: за objectName або єдиний об'єкт проєкту. */
    private static MdObject resolveObject(IExternalObjectProject externalProject,
            String projectName, String objectName) {
        List<MdObject> objects = new ArrayList<>(externalProject.getExternalObjects());
        if (objects.isEmpty()) {
            throw new IllegalArgumentException("Проєкт '" + externalProject.getProject().getName() //$NON-NLS-1$
                    + "' не містить зовнішніх обробок або звітів."); //$NON-NLS-1$
        }
        // objectName міг зіграти роль імені проєкту — тоді беремо єдиний об'єкт
        boolean nameIsProject = objectName != null && projectName == null
                && objectName.equalsIgnoreCase(externalProject.getProject().getName());
        if (objectName == null || nameIsProject) {
            if (objects.size() == 1) {
                return objects.get(0);
            }
            throw new IllegalArgumentException("У проєкті '" + externalProject.getProject().getName() //$NON-NLS-1$
                    + "' кілька об'єктів — вкажіть objectName. Доступні: " + objectNames(objects)); //$NON-NLS-1$
        }
        for (MdObject object : objects) {
            if (objectName.equalsIgnoreCase(object.getName())) {
                return object;
            }
        }
        throw new IllegalArgumentException("Об'єкт не знайдено: " + objectName //$NON-NLS-1$
                + ". Доступні в проєкті '" + externalProject.getProject().getName() + "': " //$NON-NLS-1$ //$NON-NLS-2$
                + objectNames(objects));
    }

    /** Розширення файлу має відповідати типу об'єкта: обробка — .epf, звіт — .erf. */
    private static void checkExtensionMatchesObject(MdObject object, String extension) {
        String className = object.eClass().getName();
        String expected = switch (className) {
        case CLASS_DATA_PROCESSOR -> EPF;
        case CLASS_REPORT -> ERF;
        default -> throw new IllegalArgumentException("Непідтримуваний тип об'єкта: " + className //$NON-NLS-1$
                + " (очікується ExternalDataProcessor або ExternalReport)"); //$NON-NLS-1$
        };
        if (!expected.equals(extension)) {
            throw new IllegalArgumentException("Об'єкт '" + object.getName() + "' має тип " + className //$NON-NLS-1$ //$NON-NLS-2$
                    + " — outputPath повинен закінчуватись на " + expected); //$NON-NLS-1$
        }
    }

    private static String extensionOf(Path target) {
        String name = target.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(EPF)) {
            return EPF;
        }
        if (name.endsWith(ERF)) {
            return ERF;
        }
        throw new IllegalArgumentException(
                "outputPath повинен закінчуватись на .epf (обробка) або .erf (звіт): " + target); //$NON-NLS-1$
    }

    private static List<IExternalObjectProject> externalProjects() {
        List<IExternalObjectProject> result = new ArrayList<>();
        for (IV8Project project : V8Access.v8Projects()) {
            if (project instanceof IExternalObjectProject externalProject) {
                result.add(externalProject);
            }
        }
        return result;
    }

    private static String names(List<IExternalObjectProject> projects) {
        List<String> names = new ArrayList<>();
        projects.forEach(p -> names.add(p.getProject().getName()));
        return String.join(", ", names); //$NON-NLS-1$
    }

    private static String objectNames(List<MdObject> objects) {
        List<String> names = new ArrayList<>();
        objects.forEach(o -> names.add(o.getName() + " (" + o.eClass().getName() + ")")); //$NON-NLS-1$ //$NON-NLS-2$
        return String.join(", ", names); //$NON-NLS-1$
    }

    private static String required(JsonObject arguments, String name) {
        if (!arguments.has(name) || arguments.get(name).isJsonNull()
                || arguments.get(name).getAsString().isBlank()) {
            throw new IllegalArgumentException("Обов'язковий параметр відсутній: " + name); //$NON-NLS-1$
        }
        return arguments.get(name).getAsString();
    }

    private static String optional(JsonObject arguments, String name) {
        if (!arguments.has(name) || arguments.get(name).isJsonNull()) {
            return null;
        }
        String value = arguments.get(name).getAsString();
        return value.isBlank() ? null : value;
    }
}
