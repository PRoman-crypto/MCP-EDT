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
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.polischuk.edt.prl.edt.EdtServices;
import com.polischuk.edt.prl.edt.Emf;
import com.polischuk.edt.prl.edt.KindRegistry;
import com.polischuk.edt.prl.edt.Types;
import com.polischuk.edt.prl.edt.V8Access;
import com.polischuk.edt.prl.edt.WriteGate;
import com.polischuk.edt.prl.tools.McpTool;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;

/**
 * Редагування метаданих через BM-транзакції EDT. Перші операції: setProperty
 * (скалярна властивість top-об'єкта), setSynonym (локалізований синонім).
 * dryRun виконує транзакцію і відкочує її нативним executeAndRollback.
 */
public final class EditMetadataTool implements McpTool {

    @Override
    public String name() {
        return "edit_metadata"; //$NON-NLS-1$
    }

    @Override
    public String description() {
        return "Редагування метаданих (BM-транзакції EDT). operation=help — список операцій. " //$NON-NLS-1$
                + "setProperty: скалярна властивість об'єкта (comment, version…); setSynonym: синонім мовою lang. " //$NON-NLS-1$
                + "dryRun:true виконує зміну в транзакції і відкочує — у відповіді old/new значення."; //$NON-NLS-1$
    }

    @Override
    public JsonObject inputSchema() {
        return JsonParser.parseString("""
                {"type":"object","properties":{
                  "operation":{"type":"string","enum":["help","setProperty","unsetProperty","setSynonym","addAttribute","deleteAttribute","addTabularSection","deleteTabularSection","createObject","deleteObject","adoptObject","listAdopted","renameObject","addFormField","addFormCommand","addFormGroup","deleteFormItem","addFormHandler","createTemplate","setTemplateContent","setDataSetQuery","batch"]},
                  "project":{"type":"string","description":"Ім'я проєкту EDT (необов'язково, якщо проєкт один)"},
                  "kind":{"type":"string","description":"Вид метаданих (Catalog, Document, Справочник…)"},
                  "name":{"type":"string","description":"Ім'я об'єкта"},
                  "property":{"type":"string","description":"setProperty: ім'я властивості (EMF-атрибут, напр. comment)"},
                  "value":{"type":"string","description":"Нове значення (для enum — ім'я літерала)"},
                  "attribute":{"type":"string","description":"addAttribute/deleteAttribute: ім'я реквізита"},
                  "tabularSection":{"type":"string","description":"Ім'я ТЧ: ціль для addTabularSection/deleteTabularSection або власник реквізита"},
                  "types":{"type":"array","items":{"type":"string"},"description":"addAttribute: типи (Строка, Число, СправочникСсылка.X…)"},
                  "length":{"type":"integer","description":"Кваліфікатор Строка: довжина (0 — необмежена)"},
                  "precision":{"type":"integer","description":"Кваліфікатор Число: розрядність"},
                  "scale":{"type":"integer","description":"Кваліфікатор Число: точність (знаків після коми)"},
                  "newName":{"type":"string","description":"renameObject: нове ім'я"},
                  "form":{"type":"string","description":"Форм-операції: ім'я форми об'єкта (для CommonForm не потрібне)"},
                  "dataPath":{"type":"string","description":"addFormField: шлях даних, напр. Объект.Артикул чи Объект.Товары.Кол"},
                  "item":{"type":"string","description":"addFormField: ім'я елемента (авто за dataPath); deleteFormItem: елемент для видалення"},
                  "command":{"type":"string","description":"addFormCommand: ім'я команди"},
                  "handler":{"type":"string","description":"addFormCommand: метод модуля форми (за замовч. = command)"},
                  "button":{"type":"boolean","default":true,"description":"addFormCommand: створити кнопку"},
                  "group":{"type":"string","description":"addFormGroup: ім'я групи"},
                  "groupType":{"type":"string","description":"addFormGroup: UsualGroup|Pages|Page|ButtonGroup|ColumnGroup|CommandBar|Popup"},
                  "parent":{"type":"string","description":"Форм-операції: ім'я групи-контейнера (за замовч. корінь форми)"},
                  "title":{"type":"string","description":"Заголовок елемента/групи/команди мовою lang"},
                  "operations":{"type":"array","items":{"type":"object"},"description":"batch: список транзакційних операцій [{operation, kind, name, ...}] — виконуються атомарно однією транзакцією"},
                  "template":{"type":"string","description":"createTemplate/setTemplateContent: ім'я макета"},
                  "spec":{"type":"object","description":"createTemplate/setTemplateContent: опис макета. Для spreadsheet: {columns, areas:[{name,type,rows:[{cells:[{text|parameter|format}]}]}]}; для dcs: {dataSets, parameters, resources, variants}"},
                  "templateFormat":{"type":"string","enum":["spreadsheet","dcs"],"default":"spreadsheet","description":"createTemplate: формат макета — табличний документ або схема компоновки даних"},
                  "dataSet":{"type":"string","description":"setDataSetQuery: ім'я набору даних у схемі СКД"},
                  "query":{"type":"string","description":"setDataSetQuery: новий текст запиту"},
                  "force":{"type":"boolean","default":false,"description":"renameObject: виконати попри проблеми плану (напр. об'єкт на замку)"},
                  "synonym":{"type":"string","description":"addAttribute/addTabularSection: синонім"},
                  "lang":{"type":"string","default":"ru","description":"Код мови для synonym"},
                  "dryRun":{"type":"boolean","default":false}
                },"required":["operation"]}""").getAsJsonObject(); //$NON-NLS-1$
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
        String operation = arguments.get("operation").getAsString(); //$NON-NLS-1$
        if ("help".equals(operation)) { //$NON-NLS-1$
            return help();
        }
        if ("listAdopted".equals(operation)) { //$NON-NLS-1$
            return ExtensionOps.listAdoptedObjects(
                    arguments.has("project") ? arguments.get("project").getAsString() : null); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if ("adoptObject".equals(operation)) { //$NON-NLS-1$
            // adopt не кладемо в нашу BM-транзакцію: IModelObjectAdopter сам відкриває свою
            WriteGate.check();
            return ExtensionOps.adoptObject(arguments);
        }
        if ("addFormField".equals(operation)) { //$NON-NLS-1$
            WriteGate.check();
            return FormOps.addFormField(arguments);
        }
        if ("addFormCommand".equals(operation)) { //$NON-NLS-1$
            WriteGate.check();
            return FormOps.addFormCommand(arguments);
        }
        if ("addFormHandler".equals(operation)) { //$NON-NLS-1$
            WriteGate.check();
            return FormHandlerOps.addFormHandler(arguments);
        }
        if ("addFormGroup".equals(operation)) { //$NON-NLS-1$
            WriteGate.check();
            return FormOps.addFormGroup(arguments);
        }
        if ("deleteFormItem".equals(operation)) { //$NON-NLS-1$
            WriteGate.check();
            return FormOps.deleteFormItem(arguments);
        }
        if ("createTemplate".equals(operation)) { //$NON-NLS-1$
            return TemplateOps.createTemplate(arguments);
        }
        if ("setTemplateContent".equals(operation)) { //$NON-NLS-1$
            return TemplateOps.setTemplateContent(arguments);
        }
        if ("setDataSetQuery".equals(operation)) { //$NON-NLS-1$
            return TemplateOps.setDataSetQuery(arguments);
        }
        if ("renameObject".equals(operation)) { //$NON-NLS-1$
            // refactoring-сервіс відкриває власні транзакції — ДО AbstractBmTask
            WriteGate.check();
            return RenameOps.renameObject(arguments);
        }
        WriteGate.check();

        boolean dryRun = arguments.has("dryRun") && arguments.get("dryRun").getAsBoolean(); //$NON-NLS-1$ //$NON-NLS-2$
        String projectName = arguments.has("project") ? arguments.get("project").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$
        IProject project = V8Access.resolveEclipseProject(projectName);
        IBmModel model = EdtServices.require(IBmModelManager.class).getModel(project);

        if ("batch".equals(operation)) { //$NON-NLS-1$
            return executeBatch(project, model, arguments, dryRun);
        }

        String kind = required(arguments, "kind"); //$NON-NLS-1$
        String name = required(arguments, "name"); //$NON-NLS-1$
        String fqn = KindRegistry.canonical(kind) + "." + name; //$NON-NLS-1$

        AbstractBmTask<JsonObject> task = new AbstractBmTask<>("MCP:PRL edit_metadata " + operation) { //$NON-NLS-1$
            @Override
            public JsonObject execute(IBmTransaction transaction, IProgressMonitor monitor) {
                return applyTransactionalOperation(transaction, project, operation, arguments);
            }
        };

        // запис — через глобальний editing-контекст: він маршрутизує зміни через конвеєр EDT
        // (derived data → серіалізація у файли), а не в "сирий" рушій BM
        JsonObject change = dryRun ? model.executeAndRollback(task) : model.getGlobalContext().execute(task);
        if (!dryRun && "createObject".equals(operation)) { //$NON-NLS-1$
            createModuleFileIfNeeded(project, change);
        }
        if (!dryRun && "deleteObject".equals(operation)) { //$NON-NLS-1$
            deleteObjectFolderIfLeft(project, change);
        }
        JsonObject result = new JsonObject();
        result.addProperty("operation", operation); //$NON-NLS-1$
        result.addProperty("object", fqn); //$NON-NLS-1$
        result.addProperty("dryRun", dryRun); //$NON-NLS-1$
        result.addProperty("applied", !dryRun); //$NON-NLS-1$
        result.add("change", change); //$NON-NLS-1$
        if (!dryRun) {
            result.addProperty("note", //$NON-NLS-1$
                    "Зміна застосована в модель EDT; серіалізація у файли відбувається автоматично."); //$NON-NLS-1$
        }
        return result;
    }

    private static JsonObject setProperty(EObject object, String property, String value) {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(property);
        if (!(feature instanceof EAttribute attribute)) {
            throw new IllegalArgumentException("Властивість не знайдена або не скалярна: " + property //$NON-NLS-1$
                    + " (клас " + object.eClass().getName() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (attribute.isMany()) {
            throw new IllegalArgumentException("Властивість " + property + " — колекція, setProperty не застосовний"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        Object oldValue = object.eGet(attribute);
        Object newValue = attribute.getEAttributeType().getEPackage().getEFactoryInstance()
                .createFromString(attribute.getEAttributeType(), value);
        object.eSet(attribute, newValue);
        JsonObject change = new JsonObject();
        change.addProperty("property", property); //$NON-NLS-1$
        change.addProperty("old", oldValue == null ? null : String.valueOf(oldValue)); //$NON-NLS-1$
        change.addProperty("new", String.valueOf(newValue)); //$NON-NLS-1$
        return change;
    }

    @SuppressWarnings("unchecked")
    private static JsonObject setSynonym(EObject object, String value, String lang) {
        EStructuralFeature feature = object.eClass().getEStructuralFeature("synonym"); //$NON-NLS-1$
        if (feature == null) {
            throw new IllegalArgumentException("Об'єкт " + object.eClass().getName() + " не має синоніма"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        EMap<String, String> synonym = (EMap<String, String>) object.eGet(feature);
        String oldValue = synonym.get(lang);
        synonym.put(lang, value);
        JsonObject change = new JsonObject();
        change.addProperty("lang", lang); //$NON-NLS-1$
        change.addProperty("old", oldValue); //$NON-NLS-1$
        change.addProperty("new", value); //$NON-NLS-1$
        return change;
    }

    private static JsonObject help() {
        return JsonParser.parseString("""
                {"operations":{
                  "setProperty":{"params":"kind, name, property, value [, project, dryRun]",
                    "description":"Задає скалярну властивість top-об'єкта метаданих (EMF-атрибут). Приклади property: comment, version (конфігурація). Імена властивостей видно у get_object_details → properties.",
                    "example":{"operation":"setProperty","kind":"Справочник","name":"Номенклатура","property":"comment","value":"Тест","dryRun":true}},
                  "setSynonym":{"params":"kind, name, value [, lang=ru, project, dryRun]",
                    "description":"Задає синонім об'єкта вказаною мовою.",
                    "example":{"operation":"setSynonym","kind":"Справочник","name":"Номенклатура","value":"Товари","lang":"ru","dryRun":true}},
                  "addAttribute":{"params":"kind, name, attribute, types [, tabularSection, length, precision, scale, synonym, lang, project, dryRun]",
                    "description":"Додає реквізит об'єкта або ТЧ (з tabularSection). types — масив: Строка, Число, Булево, Дата, СправочникСсылка.X, ДокументСсылка.X…",
                    "example":{"operation":"addAttribute","kind":"Справочник","name":"Номенклатура","attribute":"МійРеквізит","types":["Строка"],"length":100,"synonym":"Мой реквизит","dryRun":true}},
                  "deleteAttribute":{"params":"kind, name, attribute [, tabularSection, project, dryRun]",
                    "description":"Видаляє реквізит. УВАГА: посилання в коді/формах/запитах не чистяться автоматично."},
                  "addTabularSection":{"params":"kind, name, tabularSection [, synonym, lang, project, dryRun]",
                    "description":"Додає табличну частину (реквізити до неї — addAttribute із tabularSection)."},
                  "deleteTabularSection":{"params":"kind, name, tabularSection [, project, dryRun]",
                    "description":"Видаляє табличну частину з усіма реквізитами."},
                  "createObject":{"params":"kind, name [, synonym, lang, properties, project, dryRun]",
                    "description":"Створює top-об'єкт метаданих (ОбщийМодуль, Справочник, Документ…). properties — об'єкт {ім'я: значення} для скалярних властивостей, напр. {\\"server\\":\\"true\\"} для загального модуля.",
                    "example":{"operation":"createObject","kind":"ОбщийМодуль","name":"МійМодуль","properties":{"server":"true"},"dryRun":true}},
                  "deleteObject":{"params":"kind, name [, project, dryRun]",
                    "description":"Видаляє top-об'єкт метаданих. УВАГА: посилання в коді/формах/запитах не чистяться автоматично."},
                  "adoptObject":{"params":"project (розширення), kind, name [, tabularSection, attribute, dryRun]",
                    "description":"Заимствует об'єкт базової конфігурації в розширення (CFE) штатним сервісом EDT; дочірній елемент (реквізит/ТЧ) — авто-заимствує батьків. dryRun — повна валідація без застосування.",
                    "example":{"operation":"adoptObject","project":"МоєРозширення","kind":"Справочник","name":"Номенклатура","dryRun":true}},
                  "listAdopted":{"params":"[project]",
                    "description":"Список об'єктів проєкту розширення: Adopted (заимствованные) і Native (власні)."},
                  "renameObject":{"params":"kind, name, newName [, tabularSection, attribute, project, dryRun, force]",
                    "description":"Перейменування об'єкта/реквізита/ТЧ З ОНОВЛЕННЯМ ПОСИЛАНЬ (BSL-код, форми, RLS) через refactoring-сервіс EDT. dryRun повертає план без застосування. Рядкові літерали в коді не оновлюються.",
                    "example":{"operation":"renameObject","kind":"Справочник","name":"СтареІмʼя","newName":"НовеІмʼя","dryRun":true}},
                  "addFormField":{"params":"kind, name, form, dataPath|attribute [, item, title, lang, parent, project, dryRun]",
                    "description":"Додає поле у форму штатним сервісом EDT (id, ім'я, тип поля — автоматично). dataPath: Объект.Реквизит або Объект.ТЧ.Колонка.",
                    "example":{"operation":"addFormField","kind":"Справочник","name":"Номенклатура","form":"ФормаЭлемента","attribute":"Артикул","dryRun":true}},
                  "addFormCommand":{"params":"kind, name, form, command [, handler, title, button=true, parent, lang, project, dryRun]",
                    "description":"Додає команду форми з обробником-посиланням і (типово) кнопку. Метод-обробник у модулі форми створюйте через write_module_source."},
                  "addFormGroup":{"params":"kind, name, form, group [, groupType=UsualGroup, title, parent, lang, project, dryRun]",
                    "description":"Додає групу елементів (UsualGroup, Pages/Page, ButtonGroup, ColumnGroup, CommandBar, Popup)."},
                  "deleteFormItem":{"params":"kind, name, form, item [, project, dryRun]",
                    "description":"Видаляє елемент форми. Реквізити/команди/обробники, на які він посилався, НЕ чистяться."},
                  "addFormHandler":{"params":"kind, name, form, command [, handler, title, button, parent, lang, project, dryRun]",
                    "description":"КОМПОЗИТ: команда форми + кнопка + каркас методу-обробника (&НаКлиенте) у модулі форми — одним викликом. Тіло обробника далі наповнюйте write_module_source (replaceMethod).",
                    "example":{"operation":"addFormHandler","kind":"Справочник","name":"Номенклатура","form":"ФормаЭлемента","command":"ЗаполнитьПоШаблону","title":"Заполнить по шаблону","dryRun":true}},
                  "createTemplate":{"params":"kind, name, template [, spec, synonym, lang, project, dryRun]",
                    "description":"Створює макет табличного документа (друковану форму): реєструє в метаданих і генерує Template.mxlx зі spec. Без spec — порожній макет. dryRun показує XML-прев'ю.",
                    "example":{"operation":"createTemplate","kind":"Обработка","name":"МояОбробка","template":"ПечатнаяФорма","spec":{"columns":6,"areas":[{"name":"Шапка","type":"Rows","rows":[{"cells":[{"text":"Рахунок №"},{"parameter":"Номер"}]}]},{"name":"Строка","type":"Rows","rows":[{"cells":[{"parameter":"Товар"},{"parameter":"Количество"}]}]}]},"dryRun":true}},
                  "createTemplateDcs":{"params":"kind, name, template, templateFormat=dcs, spec [, project, dryRun]",
                    "description":"Створення схеми компоновки даних (СКД): spec={dataSets:[{name,type:Query,query,fields}],parameters,resources,variants}. Без fields поля витягуються з тексту запиту. Перевірка — get_skd.",
                    "example":{"operation":"createTemplate","kind":"Отчет","name":"МійЗвіт","template":"ОсновнаяСхемаКомпоновкиДанных","templateFormat":"dcs","spec":{"dataSets":[{"name":"НаборДанных1","type":"Query","query":"ВЫБРАТЬ Ссылка КАК Номенклатура ИЗ Справочник.Номенклатура"}]},"dryRun":true}},
                  "setTemplateContent":{"params":"kind, name, template, spec [, project, dryRun]",
                    "description":"Перезаписує вміст існуючого макета зі spec (формат визначається за наявним файлом: mxlx або dcs). Перевірка — get_mxl / get_skd."},
                  "setDataSetQuery":{"params":"kind, name, template, dataSet, query [, project, dryRun]",
                    "description":"Точкова заміна тексту запиту набору даних у схемі СКД — решта схеми (поля, параметри, ресурси, варіанти) зберігається.",
                    "example":{"operation":"setDataSetQuery","kind":"Отчет","name":"МійЗвіт","template":"ОсновнаяСхемаКомпоновкиДанных","dataSet":"НаборДанных1","query":"ВЫБРАТЬ ...","dryRun":true}},
                  "batch":{"params":"operations=[{operation, kind, name, ...}] [, project, dryRun]",
                    "description":"Список транзакційних операцій (setProperty/setSynonym/addAttribute/addTabularSection/create-deleteObject тощо) ОДНІЄЮ атомарною транзакцією: помилка кроку відкочує все. renameObject/adoptObject/форм-операції в batch не допускаються.",
                    "example":{"operation":"batch","dryRun":true,"operations":[{"operation":"createObject","kind":"Справочник","name":"МійДовідник"},{"operation":"addAttribute","kind":"Справочник","name":"МійДовідник","attribute":"Код1С","types":["Строка"],"length":10}]}}
                },
                "notes":["dryRun виконує транзакцію і відкочує її (executeAndRollback) — безпечна перевірка параметрів.",
                  "Створення top-об'єктів (довідників, документів) — у наступних версіях."]}""").getAsJsonObject(); //$NON-NLS-1$
    }

    /**
     * Для нового загального модуля EDT створює Module.bsl лише при відкритті редактора —
     * створюємо порожній файл одразу, щоб write_module_source міг писати код без UI.
     */
    private static void createModuleFileIfNeeded(IProject project, JsonObject change) {
        try {
            String fqn = change.has("created") ? change.get("created").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$
            if (fqn == null || !fqn.startsWith("CommonModule.")) { //$NON-NLS-1$
                return;
            }
            String moduleName = fqn.substring("CommonModule.".length()); //$NON-NLS-1$
            org.eclipse.core.resources.IFolder folder = project.getFolder(
                    "src/CommonModules/" + moduleName); //$NON-NLS-1$
            if (!folder.exists()) {
                mkdirs(folder);
            }
            org.eclipse.core.resources.IFile moduleFile = folder.getFile("Module.bsl"); //$NON-NLS-1$
            if (!moduleFile.exists()) {
                byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
                moduleFile.create(new java.io.ByteArrayInputStream(bom), true, null);
                change.addProperty("moduleFile", moduleFile.getProjectRelativePath().toString()); //$NON-NLS-1$
            }
        } catch (Exception e) {
            change.addProperty("moduleFileWarning", //$NON-NLS-1$
                    "Не вдалося створити Module.bsl: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Серіалізатор EDT прибирає .mdo видаленого об'єкта, але не рукотворні файли
     * (Module.bsl, створений нами) — прибираємо теку об'єкта, якщо вона лишилась.
     */
    private static void deleteObjectFolderIfLeft(IProject project, JsonObject change) {
        try {
            String fqn = change.has("deleted") ? change.get("deleted").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$
            if (fqn == null) {
                return;
            }
            int dot = fqn.indexOf('.');
            if (dot <= 0) {
                return;
            }
            EObject configuration = V8Access.configuration(V8Access.resolveProject(project.getName()));
            String folderPath = com.polischuk.edt.prl.edt.MetadataIndex.objectFolder(
                    configuration, fqn.substring(0, dot), fqn.substring(dot + 1));
            org.eclipse.core.resources.IFolder folder = project.getFolder(folderPath);
            // серіалізація асинхронна — даємо їй прибрати .mdo, потім чистимо залишки
            for (int attempt = 0; attempt < 3 && folder.exists(); attempt++) {
                Thread.sleep(2500);
                folder.refreshLocal(org.eclipse.core.resources.IResource.DEPTH_INFINITE, null);
                if (folder.exists()) {
                    boolean onlyLeftovers = true;
                    for (org.eclipse.core.resources.IResource member : folder.members()) {
                        if (member.getName().endsWith(".mdo")) { //$NON-NLS-1$
                            onlyLeftovers = false; // .mdo ще існує — модель не досеріалізувалась
                            break;
                        }
                    }
                    if (onlyLeftovers) {
                        folder.delete(true, null);
                        change.addProperty("folderRemoved", folderPath); //$NON-NLS-1$
                        return;
                    }
                }
            }
        } catch (Exception e) {
            change.addProperty("folderCleanupWarning", String.valueOf(e.getMessage())); //$NON-NLS-1$
        }
    }

    private static void mkdirs(org.eclipse.core.resources.IFolder folder) throws org.eclipse.core.runtime.CoreException {
        if (folder.getParent() instanceof org.eclipse.core.resources.IFolder parent && !parent.exists()) {
            mkdirs(parent);
        }
        folder.create(true, true, null);
    }

    /** Одна транзакційна операція над метаданими всередині відкритої BM-транзакції. */
    private static JsonObject applyTransactionalOperation(IBmTransaction transaction, IProject project,
            String operation, JsonObject arguments) {
        String kind = required(arguments, "kind"); //$NON-NLS-1$
        String name = required(arguments, "name"); //$NON-NLS-1$
        if ("createObject".equals(operation)) { //$NON-NLS-1$
            return createTopObject(transaction, KindRegistry.canonical(kind), name, arguments);
        }
        if ("deleteObject".equals(operation)) { //$NON-NLS-1$
            return deleteTopObject(transaction, KindRegistry.canonical(kind), name);
        }
        String fqn = KindRegistry.canonical(kind) + "." + name; //$NON-NLS-1$
        IBmObject bmObject = transaction.getTopObjectByFqn(fqn);
        if (bmObject == null) {
            throw new IllegalArgumentException("Об'єкт не знайдено за FQN: " + fqn); //$NON-NLS-1$
        }
        EObject object = bmObject;
        return switch (operation) {
        case "setProperty" -> setProperty(object, //$NON-NLS-1$
                required(arguments, "property"), textValue(arguments)); //$NON-NLS-1$
        case "unsetProperty" -> unsetProperty(object, required(arguments, "property")); //$NON-NLS-1$ //$NON-NLS-2$
        case "setSynonym" -> setSynonym(object, textValue(arguments),
                arguments.has("lang") ? arguments.get("lang").getAsString() : "ru"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        case "addAttribute" -> addChild(project, attributesOwner(object, arguments), //$NON-NLS-1$
                "attributes", required(arguments, "attribute"), arguments, true); //$NON-NLS-1$ //$NON-NLS-2$
        case "deleteAttribute" -> deleteChild(attributesOwner(object, arguments), //$NON-NLS-1$
                "attributes", required(arguments, "attribute")); //$NON-NLS-1$ //$NON-NLS-2$
        case "addTabularSection" -> addChild(project, object, "tabularSections", //$NON-NLS-1$ //$NON-NLS-2$
                required(arguments, "tabularSection"), arguments, false); //$NON-NLS-1$
        case "deleteTabularSection" -> deleteChild(object, "tabularSections", //$NON-NLS-1$ //$NON-NLS-2$
                required(arguments, "tabularSection")); //$NON-NLS-1$
        default -> throw new IllegalArgumentException("Операція недоступна (чи не підтримується в batch): " //$NON-NLS-1$
                + operation + ". Викличте operation=help."); //$NON-NLS-1$
        };
    }

    /**
     * batch: список транзакційних операцій ОДНІЄЮ BM-транзакцією — атомарно
     * (помилка будь-якого кроку відкочує все). Нетранзакційні операції
     * (renameObject, adoptObject, форм-операції) в batch не допускаються.
     */
    private JsonObject executeBatch(IProject project, IBmModel model, JsonObject arguments, boolean dryRun) {
        if (!arguments.has("operations") || !arguments.get("operations").isJsonArray() //$NON-NLS-1$ //$NON-NLS-2$
                || arguments.getAsJsonArray("operations").isEmpty()) { //$NON-NLS-1$
            throw new IllegalArgumentException("batch: потрібен непорожній масив operations " //$NON-NLS-1$
                    + "[{operation, kind, name, ...}, ...]"); //$NON-NLS-1$
        }
        com.google.gson.JsonArray operations = arguments.getAsJsonArray("operations"); //$NON-NLS-1$

        AbstractBmTask<JsonObject> task = new AbstractBmTask<>("MCP:PRL edit_metadata batch") { //$NON-NLS-1$
            @Override
            public JsonObject execute(IBmTransaction transaction, IProgressMonitor monitor) {
                com.google.gson.JsonArray steps = new com.google.gson.JsonArray();
                int index = 0;
                for (com.google.gson.JsonElement element : operations) {
                    index++;
                    if (!element.isJsonObject()) {
                        throw new IllegalArgumentException("batch: крок " + index + " не є об'єктом"); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    JsonObject step = element.getAsJsonObject();
                    String stepOperation = step.has("operation") ? step.get("operation").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$
                    if (stepOperation == null) {
                        throw new IllegalArgumentException("batch: крок " + index + " без operation"); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    try {
                        JsonObject change = applyTransactionalOperation(transaction, project, stepOperation, step);
                        JsonObject entry = new JsonObject();
                        entry.addProperty("step", index); //$NON-NLS-1$
                        entry.addProperty("operation", stepOperation); //$NON-NLS-1$
                        entry.add("change", change); //$NON-NLS-1$
                        steps.add(entry);
                    } catch (RuntimeException e) {
                        throw new IllegalArgumentException("batch: крок " + index + " (" + stepOperation //$NON-NLS-1$ //$NON-NLS-2$
                                + ") провалився, всю транзакцію відкочено: " + e.getMessage(), e); //$NON-NLS-1$
                    }
                }
                JsonObject batchResult = new JsonObject();
                batchResult.add("steps", steps); //$NON-NLS-1$
                return batchResult;
            }
        };

        JsonObject change = dryRun ? model.executeAndRollback(task) : model.getGlobalContext().execute(task);
        if (!dryRun && change.has("steps")) { //$NON-NLS-1$
            for (com.google.gson.JsonElement element : change.getAsJsonArray("steps")) { //$NON-NLS-1$
                JsonObject step = element.getAsJsonObject();
                JsonObject stepChange = step.getAsJsonObject("change"); //$NON-NLS-1$
                if ("createObject".equals(step.get("operation").getAsString())) { //$NON-NLS-1$ //$NON-NLS-2$
                    createModuleFileIfNeeded(project, stepChange);
                } else if ("deleteObject".equals(step.get("operation").getAsString())) { //$NON-NLS-1$ //$NON-NLS-2$
                    deleteObjectFolderIfLeft(project, stepChange);
                }
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("operation", "batch"); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("stepCount", operations.size()); //$NON-NLS-1$
        result.addProperty("dryRun", dryRun); //$NON-NLS-1$
        result.addProperty("applied", !dryRun); //$NON-NLS-1$
        result.add("change", change); //$NON-NLS-1$
        return result;
    }

    /** Створення top-об'єкта метаданих (загальний модуль, довідник…) із реєстрацією FQN. */
    @SuppressWarnings("unchecked")
    private static JsonObject createTopObject(IBmTransaction transaction, String canonicalKind,
            String name, JsonObject arguments) {
        IBmObject configurationBm = transaction.getTopObjectByFqn("Configuration"); //$NON-NLS-1$
        if (configurationBm == null) {
            throw new IllegalStateException("Configuration не знайдена в BM-моделі"); //$NON-NLS-1$
        }
        EObject configuration = configurationBm;
        EReference reference = com.polischuk.edt.prl.edt.MetadataIndex.findCollectionRef(configuration, canonicalKind);
        if (reference == null) {
            throw new IllegalArgumentException("Вид метаданих не знайдено: " + canonicalKind); //$NON-NLS-1$
        }
        if (reference.getEReferenceType().isAbstract()) {
            throw new IllegalArgumentException("Створення виду " + canonicalKind + " не підтримується (абстрактний клас)"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String fqn = reference.getEReferenceType().getName() + "." + name; //$NON-NLS-1$
        if (transaction.getTopObjectByFqn(fqn) != null) {
            throw new IllegalArgumentException("Об'єкт уже існує: " + fqn); //$NON-NLS-1$
        }
        EObject child = EcoreUtil.create(reference.getEReferenceType());
        setScalar(child, "name", name); //$NON-NLS-1$
        EStructuralFeature uuidFeature = child.eClass().getEStructuralFeature("uuid"); //$NON-NLS-1$
        if (uuidFeature != null) {
            child.eSet(uuidFeature, UUID.randomUUID());
        }
        String lang = arguments.has("lang") ? arguments.get("lang").getAsString() : "ru"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (arguments.has("synonym") && !arguments.get("synonym").getAsString().isBlank()) { //$NON-NLS-1$ //$NON-NLS-2$
            Object synonym = Emf.get(child, "synonym"); //$NON-NLS-1$
            if (synonym instanceof EMap) {
                ((EMap<String, String>) synonym).put(lang, arguments.get("synonym").getAsString()); //$NON-NLS-1$
            }
        }
        transaction.attachTopObject((IBmObject) child, fqn);
        ((List<EObject>) configuration.eGet(reference)).add(child);
        // додаткові скалярні властивості (напр. server/clientManagedApplication для CommonModule)
        JsonObject appliedProperties = new JsonObject();
        if (arguments.has("properties") && arguments.get("properties").isJsonObject()) { //$NON-NLS-1$ //$NON-NLS-2$
            for (var entry : arguments.get("properties").getAsJsonObject().entrySet()) { //$NON-NLS-1$
                appliedProperties.add(entry.getKey(),
                        setProperty(child, entry.getKey(), entry.getValue().getAsString()));
            }
        }
        JsonObject change = new JsonObject();
        change.addProperty("created", fqn); //$NON-NLS-1$
        change.addProperty("collection", reference.getName()); //$NON-NLS-1$
        if (appliedProperties.size() > 0) {
            change.add("properties", appliedProperties); //$NON-NLS-1$
        }
        return change;
    }

    @SuppressWarnings("unchecked")
    private static JsonObject deleteTopObject(IBmTransaction transaction, String canonicalKind, String name) {
        String fqn = canonicalKind + "." + name; //$NON-NLS-1$
        IBmObject bmObject = transaction.getTopObjectByFqn(fqn);
        if (bmObject == null) {
            throw new IllegalArgumentException("Об'єкт не знайдено: " + fqn); //$NON-NLS-1$
        }
        IBmObject configurationBm = transaction.getTopObjectByFqn("Configuration"); //$NON-NLS-1$
        if (configurationBm != null) {
            EObject configuration = configurationBm;
            EReference reference = com.polischuk.edt.prl.edt.MetadataIndex.findCollectionRef(configuration, canonicalKind);
            if (reference != null) {
                ((List<EObject>) configuration.eGet(reference)).remove(bmObject);
            }
        }
        transaction.detachTopObject(bmObject);
        JsonObject change = new JsonObject();
        change.addProperty("deleted", fqn); //$NON-NLS-1$
        change.addProperty("warning", //$NON-NLS-1$
                "Посилання на об'єкт у коді/формах/запитах НЕ чистяться автоматично — перевірте get_validation_errors."); //$NON-NLS-1$
        return change;
    }

    /** Власник реквізитів: сам об'єкт або його таблична частина, якщо задано tabularSection. */
    private static EObject attributesOwner(EObject object, JsonObject arguments) {
        if (!arguments.has("tabularSection") || arguments.get("tabularSection").getAsString().isBlank()) { //$NON-NLS-1$ //$NON-NLS-2$
            return object;
        }
        String sectionName = arguments.get("tabularSection").getAsString(); //$NON-NLS-1$
        Object sections = Emf.get(object, "tabularSections"); //$NON-NLS-1$
        if (sections instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof EObject section && sectionName.equalsIgnoreCase(Emf.name(section))) {
                    return section;
                }
            }
        }
        throw new IllegalArgumentException("Таблична частина не знайдена: " + sectionName); //$NON-NLS-1$
    }

    /** Створює дочірній елемент containment-колекції (реквізит або ТЧ) з uuid, синонімом і типом. */
    @SuppressWarnings("unchecked")
    private static JsonObject addChild(IProject project, EObject owner, String refName,
            String childName, JsonObject arguments, boolean withType) {
        EStructuralFeature feature = owner.eClass().getEStructuralFeature(refName);
        if (!(feature instanceof EReference reference) || !reference.isContainment() || !reference.isMany()) {
            throw new IllegalArgumentException("Колекція " + refName + " недоступна для " //$NON-NLS-1$ //$NON-NLS-2$
                    + owner.eClass().getName());
        }
        List<EObject> collection = (List<EObject>) owner.eGet(reference);
        for (EObject existing : collection) {
            if (childName.equalsIgnoreCase(Emf.name(existing))) {
                throw new IllegalArgumentException("Елемент уже існує: " + childName); //$NON-NLS-1$
            }
        }
        EObject child = EcoreUtil.create(reference.getEReferenceType());
        setScalar(child, "name", childName); //$NON-NLS-1$
        EStructuralFeature uuidFeature = child.eClass().getEStructuralFeature("uuid"); //$NON-NLS-1$
        if (uuidFeature != null) {
            child.eSet(uuidFeature, UUID.randomUUID());
        }
        String lang = arguments.has("lang") ? arguments.get("lang").getAsString() : "ru"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (arguments.has("synonym") && !arguments.get("synonym").getAsString().isBlank()) { //$NON-NLS-1$ //$NON-NLS-2$
            Object synonym = Emf.get(child, "synonym"); //$NON-NLS-1$
            if (synonym instanceof EMap) {
                ((EMap<String, String>) synonym).put(lang, arguments.get("synonym").getAsString()); //$NON-NLS-1$
            }
        }
        List<String> typeNames = new ArrayList<>();
        if (withType) {
            if (!arguments.has("types") || arguments.get("types").getAsJsonArray().isEmpty()) { //$NON-NLS-1$ //$NON-NLS-2$
                throw new IllegalArgumentException("Обов'язковий параметр types (масив імен типів)"); //$NON-NLS-1$
            }
            arguments.get("types").getAsJsonArray().forEach(t -> typeNames.add(t.getAsString())); //$NON-NLS-1$
            Integer length = intOrNull(arguments, "length"); //$NON-NLS-1$
            Integer precision = intOrNull(arguments, "precision"); //$NON-NLS-1$
            Integer scale = intOrNull(arguments, "scale"); //$NON-NLS-1$
            EStructuralFeature typeFeature = child.eClass().getEStructuralFeature("type"); //$NON-NLS-1$
            if (typeFeature == null) {
                throw new IllegalArgumentException("Елемент " + child.eClass().getName() + " не має типу"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            child.eSet(typeFeature, Types.build(project, typeNames, length, precision, scale));
        }
        collection.add(child);
        JsonObject change = new JsonObject();
        change.addProperty("added", childName); //$NON-NLS-1$
        change.addProperty("class", child.eClass().getName()); //$NON-NLS-1$
        change.addProperty("collection", refName); //$NON-NLS-1$
        change.addProperty("owner", Emf.name(owner)); //$NON-NLS-1$
        if (!typeNames.isEmpty()) {
            change.addProperty("types", String.join(", ", typeNames)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return change;
    }

    private static JsonObject deleteChild(EObject owner, String refName, String childName) {
        Object value = Emf.get(owner, refName);
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof EObject child && childName.equalsIgnoreCase(Emf.name(child))) {
                    EcoreUtil.delete(child);
                    JsonObject change = new JsonObject();
                    change.addProperty("deleted", childName); //$NON-NLS-1$
                    change.addProperty("collection", refName); //$NON-NLS-1$
                    change.addProperty("warning", //$NON-NLS-1$
                            "Посилання на видалений елемент у коді/формах/запитах НЕ видаляються автоматично — " //$NON-NLS-1$
                            + "перевірте get_validation_errors і code_search."); //$NON-NLS-1$
                    return change;
                }
            }
        }
        throw new IllegalArgumentException("Елемент не знайдено: " + childName + " у " + refName); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void setScalar(EObject object, String featureName, Object value) {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (feature != null) {
            object.eSet(feature, value);
        }
    }

    private static Integer intOrNull(JsonObject arguments, String name) {
        return arguments.has(name) && !arguments.get(name).isJsonNull()
                ? Integer.valueOf(arguments.get(name).getAsInt()) : null;
    }

    /** value обов'язковий за присутністю, але порожній рядок — легальне значення (очистити властивість). */
    private static String textValue(JsonObject arguments) {
        if (!arguments.has("value") || arguments.get("value").isJsonNull()) { //$NON-NLS-1$ //$NON-NLS-2$
            throw new IllegalArgumentException("Обов'язковий параметр відсутній: value"); //$NON-NLS-1$
        }
        return arguments.get("value").getAsString(); //$NON-NLS-1$
    }

    private static JsonObject unsetProperty(EObject object, String property) {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(property);
        if (!(feature instanceof EAttribute attribute) || attribute.isMany()) {
            throw new IllegalArgumentException("Властивість не знайдена або не скалярна: " + property); //$NON-NLS-1$
        }
        Object oldValue = object.eGet(attribute);
        object.eUnset(attribute);
        JsonObject change = new JsonObject();
        change.addProperty("property", property); //$NON-NLS-1$
        change.addProperty("old", oldValue == null ? null : String.valueOf(oldValue)); //$NON-NLS-1$
        change.addProperty("new", (String) null); //$NON-NLS-1$
        return change;
    }

    private static String required(JsonObject arguments, String name) {
        if (!arguments.has(name) || arguments.get(name).getAsString().isBlank()) {
            throw new IllegalArgumentException("Обов'язковий параметр відсутній: " + name); //$NON-NLS-1$
        }
        return arguments.get(name).getAsString();
    }
}
