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
                  "operation":{"type":"string","enum":["help","setProperty","unsetProperty","setSynonym","addAttribute","deleteAttribute","addTabularSection","deleteTabularSection","createObject","deleteObject","adoptObject","listAdopted","renameObject","addFormField","addFormCommand","addFormGroup","deleteFormItem","addFormHandler","createTemplate","setTemplateContent","setDataSetQuery","addItem","deleteItem","setItemProperty","setItemType","addDimension","addResource","addSubsystemContent","removeSubsystemContent","addExchangePlanContent","removeExchangePlanContent","setRoleRights","addRegisterField","removeRegisterField","addEnumValue","setValueType","setRoleRight","setDefinedTypeTypes","addRecorder","removeRecorder","addAccountExtDimensionType","removeAccountExtDimensionType","setXdtoNamespace","addXdtoObjectType","addXdtoValueType","addXdtoProperty","removeXdtoType","removeXdtoProperty","xdtoDiagnostics","batch"]},
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
                  "item":{"type":"string","description":"addFormField: ім'я елемента (авто за dataPath); deleteFormItem: елемент для видалення; addItem/deleteItem/setItemProperty/setItemType/addDimension/addResource: ім'я елемента колекції"},
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
                  "collection":{"type":"string","description":"addItem/deleteItem/setItemProperty/setItemType: колекція-контейнер (dimensions, resources, attributes, enumValues, columns…); помилка перелічить доступні"},
                  "properties":{"type":"object","description":"createObject/addItem/addDimension/addResource/addAttribute: початкові скалярні властивості {ім'я: значення}"},
                  "dateFractions":{"type":"string","description":"Кваліфікатор Дата: Date | Time | DateTime"},
                  "nonNegative":{"type":"boolean","description":"Кваліфікатор Число: невід'ємне"},
                  "objects":{"type":"array","items":{"type":"string"},"description":"Склад підсистеми/плану обміну/цілі прав: ['Справочник.Номенклатура','Document.Заказ']"},
                  "autoRecord":{"type":"string","description":"addExchangePlanContent: Allow | Deny (авторегістрація змін)"},
                  "rights":{"type":"array","items":{"type":"string"},"description":"setRoleRights: імена прав (Read/Чтение, Insert/Добавление, View/Просмотр…)"},
                  "recorders":{"type":"array","items":{"type":"string"},"description":"addRecorder/removeRecorder: документи-регістратори ['Документ.Заказ'] (аліас objects/document)"},
                  "account":{"type":"string","description":"add/removeAccountExtDimensionType: ім'я предвизначеного рахунку (чи підрахунку)"},
                  "extDimension":{"type":"string","description":"add/removeAccountExtDimensionType: ім'я виду субконто (предвизначений елемент ПВХ)"},
                  "turnover":{"type":"boolean","description":"addAccountExtDimensionType: субконто оборотне"},
                  "flags":{"type":"array","items":{"type":"string"},"description":"addAccountExtDimensionType: ознаки обліку субконто"},
                  "cleanupForms":{"type":"boolean","default":true,"description":"deleteAttribute/deleteTabularSection: прибрати з форм об'єкта елементи з відповідним dataPath"},
                  "namespace":{"type":"string","description":"setXdtoNamespace/createObject XDTOPackage: URI простору імен пакета"},
                  "xdtoType":{"type":"string","description":"addXdtoProperty/removeXdtoProperty: ім'я об'єктного типу пакета"},
                  "propertyType":{"type":"string","description":"addXdtoProperty: тип властивості (xs:string, xs:dateTime, v8:UUID, {uri}ім'я, або ім'я типу пакета)"},
                  "xdtoProperties":{"type":"array","items":{"type":"object"},"description":"addXdtoObjectType: властивості одразу [{property|name, propertyType|type, lowerBound, upperBound, nillable, form}]"},
                  "baseType":{"type":"string","description":"addXdtoValueType/addXdtoObjectType: базовий тип (за замовч. xs:string для значення)"},
                  "enumerations":{"type":"array","items":{"type":"string"},"description":"addXdtoValueType: значення переліку"},
                  "lowerBound":{"type":"integer"},"upperBound":{"type":"integer"},"nillable":{"type":"boolean"},
                  "fieldKind":{"type":"string","description":"addRegisterField/removeRegisterField: dimension | resource | attribute"},
                  "field":{"type":"string","description":"addRegisterField/removeRegisterField/setValueType: ім'я поля (аліас item)"},
                  "role":{"type":"string","description":"setRoleRight: ім'я ролі (аліас name)"},
                  "grant":{"type":"boolean","default":true,"description":"setRoleRights: true — надати права, false — забрати"},
                  "setForNewObjects":{"type":"boolean","description":"setRoleRights: прапорець ролі «Права для нових об'єктів»"},
                  "setForAttributesByDefault":{"type":"boolean","description":"setRoleRights: прапорець ролі «Права для реквізитів за замовчуванням»"},
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

        JsonObject adoption = ExtensionTypeAdopt.prepare(project, arguments, dryRun);
        if (adoption != null && dryRun && adoption.has("wouldAdopt") && adoption.getAsJsonArray("wouldAdopt").size() > 0) { //$NON-NLS-1$ //$NON-NLS-2$
            JsonObject preview = new JsonObject();
            preview.addProperty("operation", operation); //$NON-NLS-1$
            preview.addProperty("dryRun", true); //$NON-NLS-1$
            preview.addProperty("applied", false); //$NON-NLS-1$
            preview.add("extensionTypes", adoption); //$NON-NLS-1$
            preview.addProperty("note", //$NON-NLS-1$
                    "Проєкт — розширення: ссылочные типи вимагають заимствования об'єктів (wouldAdopt). " //$NON-NLS-1$
                    + "Без dryRun вони заимствуються автоматично; повний dryRun операції можливий після заимствования."); //$NON-NLS-1$
            return preview;
        }
        if ("batch".equals(operation)) { //$NON-NLS-1$
            return withAdoption(executeBatch(project, model, arguments, dryRun), adoption);
        }

        JsonObject normalized = normalizeAliases(operation, arguments);
        String kind = required(normalized, "kind"); //$NON-NLS-1$
        String name = required(normalized, "name"); //$NON-NLS-1$
        String fqn = KindRegistry.canonical(kind) + "." + name; //$NON-NLS-1$

        AbstractBmTask<JsonObject> task = new AbstractBmTask<>("MCP:PRL edit_metadata " + operation) { //$NON-NLS-1$
            @Override
            public JsonObject execute(IBmTransaction transaction, IProgressMonitor monitor) {
                Types.setTransaction(transaction);
                try {
                    return applyTransactionalOperation(transaction, project, operation, arguments);
                } finally {
                    Types.setTransaction(null);
                }
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
        return withAdoption(result, adoption);
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
        Object newValue = convertScalar(attribute, value);
        object.eSet(attribute, newValue);
        JsonObject change = new JsonObject();
        change.addProperty("property", property); //$NON-NLS-1$
        change.addProperty("old", oldValue == null ? null : String.valueOf(oldValue)); //$NON-NLS-1$
        change.addProperty("new", String.valueOf(newValue)); //$NON-NLS-1$
        return change;
    }

    /** Рядок → значення EMF-атрибута; для enum приймає ім'я або літерал без урахування регістру. */
    static Object convertScalar(EAttribute attribute, String value) {
        if (attribute.getEAttributeType() instanceof org.eclipse.emf.ecore.EEnum eenum) {
            for (org.eclipse.emf.ecore.EEnumLiteral literal : eenum.getELiterals()) {
                if (literal.getName().equalsIgnoreCase(value) || literal.getLiteral().equalsIgnoreCase(value)) {
                    return literal.getInstance();
                }
            }
            StringBuilder available = new StringBuilder();
            for (org.eclipse.emf.ecore.EEnumLiteral literal : eenum.getELiterals()) {
                available.append(available.length() == 0 ? "" : ", ").append(literal.getName()); //$NON-NLS-1$ //$NON-NLS-2$
            }
            throw new IllegalArgumentException("Недопустиме значення '" + value + "' для " + attribute.getName() //$NON-NLS-1$ //$NON-NLS-2$
                    + ". Допустимі: " + available); //$NON-NLS-1$
        }
        return attribute.getEAttributeType().getEPackage().getEFactoryInstance()
                .createFromString(attribute.getEAttributeType(), value);
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
                  "addDimension":{"params":"kind, name, item, types [, length, precision, scale, dateFractions, nonNegative, synonym, lang, properties, project, dryRun]",
                    "description":"Додає ВИМІР регістру (відомостей/накопичення/бухгалтерії/розрахунку). Типи — як в addAttribute, у т.ч. ссылочные: СправочникСсылка.X, ДокументСсылка.X, ПеречислениеСсылка.X, ЛюбаяСсылка, ОпределяемыйТип.X. properties — початкові властивості виміру, напр. {\\"master\\":\\"true\\",\\"denyIncompleteValues\\":\\"true\\"}.",
                    "example":{"operation":"addDimension","kind":"РегистрСведений","name":"МійРегистр","item":"Товар","types":["СправочникСсылка.Номенклатура"],"properties":{"master":"true"},"dryRun":true}},
                  "addResource":{"params":"kind, name, item, types [, length, precision, scale, nonNegative, synonym, lang, properties, project, dryRun]",
                    "description":"Додає РЕСУРС регістру. Параметри — як у addDimension.",
                    "example":{"operation":"addResource","kind":"РегистрНакопления","name":"МійРегистр","item":"Количество","types":["Число"],"precision":15,"scale":3,"dryRun":true}},
                  "addItem":{"params":"kind, name, collection, item [, tabularSection, types, properties, synonym, lang, project, dryRun]",
                    "description":"Загальне додавання елемента в будь-яку containment-колекцію об'єкта (dimensions, resources, attributes, enumValues, columns, accountingFlags…). Невідома колекція — у помилці перелік доступних. Без types — елемент без типу (напр. значення перелічення)."},
                  "deleteItem":{"params":"kind, name, collection, item [, tabularSection, project, dryRun]",
                    "description":"Видаляє елемент колекції (вимір, ресурс, значення перелічення…). Посилання в коді/запитах не чистяться."},
                  "setItemProperty":{"params":"kind, name, collection, item, property, value [, tabularSection, project, dryRun]",
                    "description":"Властивість елемента колекції: у виміру master/mainFilter/denyIncompleteValues/indexing, у реквізиту fillChecking тощо. enum — ім'я літерала без урахування регістру; помилка перелічує допустимі.",
                    "example":{"operation":"setItemProperty","kind":"РегистрСведений","name":"МійРегистр","collection":"dimensions","item":"Товар","property":"denyIncompleteValues","value":"true","dryRun":true}},
                  "setItemType":{"params":"kind, name, collection, item, types [, length, precision, scale, dateFractions, nonNegative, tabularSection, project, dryRun]",
                    "description":"Замінює тип існуючого реквізита/виміру/ресурсу."},
                  "addSubsystemContent":{"params":"kind=Subsystem, name, objects [, project, dryRun]",
                    "description":"Додає об'єкти до складу підсистеми. name — підсистема (вкладена через крапку або слеш: Продажи.Отчеты, Продажи/Отчеты; FQN Продажи.Subsystem.Отчеты теж приймається; унікальне коротке ім'я вкладеної теж працює); objects — ['Справочник.Номенклатура','Document.Заказ'…]. Об'єкти, що вже є у складі, пропускаються.",
                    "example":{"operation":"addSubsystemContent","kind":"Подсистема","name":"Продажи","objects":["Справочник.Номенклатура","РегистрСведений.МійРегистр"],"dryRun":true}},
                  "removeSubsystemContent":{"params":"kind=Subsystem, name, objects [, project, dryRun]",
                    "description":"Прибирає об'єкти зі складу підсистеми."},
                  "addExchangePlanContent":{"params":"kind=ExchangePlan, name, objects [, autoRecord=Allow|Deny, project, dryRun]",
                    "description":"Додає об'єкти до складу плану обміну (з авторегістрацією змін autoRecord). Якщо об'єкт уже є — оновлюється лише autoRecord.",
                    "example":{"operation":"addExchangePlanContent","kind":"ПланОбмена","name":"МійПланОбмена","objects":["Справочник.Номенклатура"],"autoRecord":"Allow","dryRun":true}},
                  "removeExchangePlanContent":{"params":"kind=ExchangePlan, name, objects [, project, dryRun]",
                    "description":"Прибирає об'єкти зі складу плану обміну."},
                  "setRoleRights":{"params":"kind=Role, name, objects, rights [, grant=true, setForNewObjects, setForAttributesByDefault, project, dryRun]",
                    "description":"Права ролі на об'єкти: надає (grant:true) або забирає (grant:false) права rights для кожного з objects. Імена прав — англ. або рос. (Read/Чтение, Insert/Добавление, Update/Изменение, Delete/Удаление, View/Просмотр, InteractiveInsert…); недоступне право — помилка з переліком доступних для цього об'єкта. Без objects можна лише змінити прапорці ролі. RLS цією операцією не змінюються. Перевірка — get_role_rights.",
                    "example":{"operation":"setRoleRights","kind":"Роль","name":"МояРоль","objects":["Справочник.Номенклатура"],"rights":["Read","View"],"dryRun":true}},
                  "addRecorder":{"params":"kind=<вид регістра>, name, objects|recorders|document [, project, dryRun]",
                    "description":"Документи-регістратори регістра: додає регістр до списку 'Движения' кожного документа. Ідемпотентно.",
                    "example":{"operation":"addRecorder","kind":"РегистрНакопления","name":"МійРегистр","objects":["Документ.Заказ"],"dryRun":true}},
                  "removeRecorder":{"params":"kind=<вид регістра>, name, objects [, project, dryRun]",
                    "description":"Прибирає регістр зі списку 'Движения' документів."},
                  "addAccountExtDimensionType":{"params":"kind=ChartOfAccounts, name, account, extDimension [, turnover, flags, project, dryRun]",
                    "description":"Призначає предвизначеному рахунку (чи підрахунку) вид субконто з ПВХ, пов'язаного властивістю extDimensionTypes. Ліміт — maxExtDimensionCount. Ідемпотентно (повтор оновлює turnover/flags).",
                    "example":{"operation":"addAccountExtDimensionType","kind":"ПланСчетов","name":"Хозрасчетный","account":"ДенежныеСредстваВКассе","extDimension":"Кассы","turnover":true,"dryRun":true}},
                  "removeAccountExtDimensionType":{"params":"kind=ChartOfAccounts, name, account, extDimension [, project, dryRun]",
                    "description":"Прибирає вид субконто з рахунку."},
                  "setXdtoNamespace":{"params":"kind=XDTOPackage, name, namespace [, project, dryRun]",
                    "description":"Простір імен XDTO-пакета (URI)."},
                  "addXdtoObjectType":{"params":"kind=XDTOPackage, name, item [, xdtoProperties, open, abstract, baseType, project, dryRun]",
                    "description":"Тип об'єкта пакета; властивості можна задати одразу масивом xdtoProperties.",
                    "example":{"operation":"addXdtoObjectType","kind":"XDTOПакет","name":"МійПакет","item":"Замовлення","xdtoProperties":[{"property":"Номер","propertyType":"xs:string"},{"property":"Дата","propertyType":"xs:dateTime","form":"Attribute"}],"dryRun":true}},
                  "addXdtoValueType":{"params":"kind=XDTOPackage, name, item [, baseType=xs:string, length, minLength, maxLength, enumerations, project, dryRun]",
                    "description":"Тип значення на базі XSD (з обмеженнями довжини чи переліком значень)."},
                  "addXdtoProperty":{"params":"kind=XDTOPackage, name, xdtoType, property [, propertyType=xs:string, lowerBound, upperBound, nillable, form=Element|Attribute|Text, project, dryRun]",
                    "description":"Властивість об'єктного типу: тип xs:*, v8:UUID, {uri}ім'я або тип цього пакета; чужі простори імен додаються в imports."},
                  "removeXdtoType":{"params":"kind=XDTOPackage, name, item [, project, dryRun]","description":"Видаляє тип пакета."},
                  "removeXdtoProperty":{"params":"kind=XDTOPackage, name, xdtoType, property [, project, dryRun]","description":"Видаляє властивість об'єктного типу."},
                  "batch":{"params":"operations=[{operation, kind, name, ...}] [, project, dryRun]",
                    "description":"Список транзакційних операцій (setProperty/setSynonym/addAttribute/addTabularSection/create-deleteObject тощо) ОДНІЄЮ атомарною транзакцією: помилка кроку відкочує все. renameObject/adoptObject/форм-операції в batch не допускаються.",
                    "example":{"operation":"batch","dryRun":true,"operations":[{"operation":"createObject","kind":"Справочник","name":"МійДовідник"},{"operation":"addAttribute","kind":"Справочник","name":"МійДовідник","attribute":"Код1С","types":["Строка"],"length":10}]}}
                },
                "notes":["Розширення (CFE): ссылочные типи (СправочникСсылка.X…) автоматично заимствують потрібні об'єкти базової конфігурації (поле extensionTypes у відповіді; у dryRun — wouldAdopt). deleteAttribute/deleteTabularSection прибирають із форм об'єкта поля/таблиці з відповідним dataPath (cleanupForms:false — вимкнути).",
                  "Сумісність із контрактом RSV: addRegisterField/removeRegisterField (fieldKind=dimension|resource|attribute, field), addEnumValue (value), setValueType (item, types — колекція шукається сама), setDefinedTypeTypes (types), setRoleRight (role, object, right, grant); повторне додавання поля повертає alreadyExists:true замість помилки; createObject приймає масиви dimensions/resources/attributes; setRoleRights приймає вкладені цілі Вид.Ім'я.Attribute.X / .TabularSection.X[.Attribute.Y] / .Command.X / .Dimension.X / .Resource.X; addExchangePlanContent приймає items:[{object,autoRecord}].",
                  "Нові операції (addDimension/addResource/addItem/deleteItem/setItemProperty/setItemType, склад підсистем і планів обміну, setRoleRights) — транзакційні: працюють у batch з createObject/addAttribute.",
                  "dryRun виконує транзакцію і відкочує її (executeAndRollback) — безпечна перевірка параметрів.",
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
            String operation, JsonObject originalArguments) {
        JsonObject arguments = normalizeAliases(operation, originalArguments);
        operation = canonicalOperation(operation, arguments);
        String kind = required(arguments, "kind"); //$NON-NLS-1$
        String name = required(arguments, "name"); //$NON-NLS-1$
        if ("createObject".equals(operation)) { //$NON-NLS-1$
            JsonObject created = createTopObject(transaction, KindRegistry.canonical(kind), name, arguments);
            addInlineFields(transaction, project, KindRegistry.canonical(kind), name, arguments, created);
            if ("XDTOPackage".equals(KindRegistry.canonical(kind)) //$NON-NLS-1$
                    && transaction.getTopObjectByFqn("XDTOPackage." + name) instanceof com._1c.g5.v8.dt.metadata.mdclass.XDTOPackage newPackage) { //$NON-NLS-1$
                if (arguments.has("namespace")) { //$NON-NLS-1$
                    newPackage.setNamespace(arguments.get("namespace").getAsString()); //$NON-NLS-1$
                }
                XdtoOps.ensurePackage(transaction, newPackage);
            }
            if ("Role".equals(KindRegistry.canonical(kind)) //$NON-NLS-1$
                    && transaction.getTopObjectByFqn("Role." + name) instanceof com._1c.g5.v8.dt.metadata.mdclass.Role newRole) { //$NON-NLS-1$
                RoleRightsOps.ensureDescription(transaction, newRole);
            }
            return created;
        }
        if ("deleteObject".equals(operation)) { //$NON-NLS-1$
            return deleteTopObject(transaction, KindRegistry.canonical(kind), name);
        }
        String fqn = KindRegistry.canonical(kind) + "." + name; //$NON-NLS-1$
        EObject object = StructureOps.resolveObject(transaction, KindRegistry.canonical(kind), name);
        if (object == null) {
            throw new IllegalArgumentException("Об'єкт не знайдено за FQN: " + fqn); //$NON-NLS-1$
        }
        return switch (operation) {
        case "setProperty" -> setProperty(object, //$NON-NLS-1$
                required(arguments, "property"), textValue(arguments)); //$NON-NLS-1$
        case "unsetProperty" -> unsetProperty(object, required(arguments, "property")); //$NON-NLS-1$ //$NON-NLS-2$
        case "setSynonym" -> setSynonym(object, textValue(arguments),
                arguments.has("lang") ? arguments.get("lang").getAsString() : "ru"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        case "addAttribute" -> addChild(project, attributesOwner(object, arguments), //$NON-NLS-1$
                "attributes", required(arguments, "attribute"), arguments, true); //$NON-NLS-1$ //$NON-NLS-2$
        case "deleteAttribute" -> withFormCleanup(object, arguments, //$NON-NLS-1$
                deleteChild(attributesOwner(object, arguments), "attributes", //$NON-NLS-1$
                        required(arguments, "attribute")), //$NON-NLS-1$
                tailOf(arguments, required(arguments, "attribute"))); //$NON-NLS-1$
        case "addTabularSection" -> addChild(project, object, "tabularSections", //$NON-NLS-1$ //$NON-NLS-2$
                required(arguments, "tabularSection"), arguments, false); //$NON-NLS-1$
        case "deleteTabularSection" -> withFormCleanup(object, arguments, //$NON-NLS-1$
                deleteChild(object, "tabularSections", required(arguments, "tabularSection")), //$NON-NLS-1$ //$NON-NLS-2$
                List.of(required(arguments, "tabularSection"))); //$NON-NLS-1$
        case "addDimension" -> addChild(project, attributesOwner(object, arguments), "dimensions", //$NON-NLS-1$ //$NON-NLS-2$
                required(arguments, "item"), arguments, true); //$NON-NLS-1$
        case "addResource" -> addChild(project, attributesOwner(object, arguments), "resources", //$NON-NLS-1$ //$NON-NLS-2$
                required(arguments, "item"), arguments, true); //$NON-NLS-1$
        case "addItem" -> addChild(project, attributesOwner(object, arguments), //$NON-NLS-1$
                required(arguments, "collection"), required(arguments, "item"), arguments, //$NON-NLS-1$ //$NON-NLS-2$
                arguments.has("types")); //$NON-NLS-1$
        case "deleteItem" -> {
            JsonObject deleted = deleteChild(attributesOwner(object, arguments),
                    required(arguments, "collection"), required(arguments, "item")); //$NON-NLS-1$ //$NON-NLS-2$
            yield "attributes".equals(arguments.get("collection").getAsString()) //$NON-NLS-1$ //$NON-NLS-2$
                    ? withFormCleanup(object, arguments, deleted, tailOf(arguments, arguments.get("item").getAsString())) //$NON-NLS-1$
                    : deleted;
        }
        case "setItemProperty" -> setProperty(findChild(attributesOwner(object, arguments), //$NON-NLS-1$
                required(arguments, "collection"), required(arguments, "item")), //$NON-NLS-1$ //$NON-NLS-2$
                required(arguments, "property"), textValue(arguments)); //$NON-NLS-1$
        case "setItemType" -> setItemType(project, findItemAnywhere(attributesOwner(object, arguments), //$NON-NLS-1$
                required(arguments, "collection"), required(arguments, "item")), arguments); //$NON-NLS-1$ //$NON-NLS-2$
        case "setDefinedTypeTypes" -> setItemType(project, object, arguments); //$NON-NLS-1$
        case "addSubsystemContent", "removeSubsystemContent" -> StructureOps.subsystemContent( //$NON-NLS-1$ //$NON-NLS-2$
                transaction, object, arguments, "addSubsystemContent".equals(operation)); //$NON-NLS-1$
        case "addExchangePlanContent", "removeExchangePlanContent" -> StructureOps.exchangePlanContent( //$NON-NLS-1$ //$NON-NLS-2$
                transaction, object, arguments, "addExchangePlanContent".equals(operation)); //$NON-NLS-1$
        case "setRoleRights" -> RoleRightsOps.setRoleRights(transaction, object, arguments); //$NON-NLS-1$
        case "addAccountExtDimensionType", "removeAccountExtDimensionType" -> StructureOps.accountExtDimension( //$NON-NLS-1$ //$NON-NLS-2$
                transaction, object, arguments, "addAccountExtDimensionType".equals(operation)); //$NON-NLS-1$
        case "setXdtoNamespace", "addXdtoObjectType", "addXdtoValueType", "addXdtoProperty", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "removeXdtoType", "removeXdtoProperty", "xdtoDiagnostics" -> XdtoOps.apply(transaction, object, operation, arguments); //$NON-NLS-1$ //$NON-NLS-2$
        case "addRecorder", "removeRecorder" -> StructureOps.recorders( //$NON-NLS-1$ //$NON-NLS-2$
                transaction, object, arguments, "addRecorder".equals(operation)); //$NON-NLS-1$
        default -> throw new IllegalArgumentException("Операція недоступна (чи не підтримується в batch): " //$NON-NLS-1$
                + operation + ". Викличте operation=help."); //$NON-NLS-1$
        };
    }

    /** Імена операцій у контракті RSV → наші базові операції. */
    private static String canonicalOperation(String operation, JsonObject arguments) {
        return switch (operation) {
        case "addRegisterField" -> "addItem"; //$NON-NLS-1$ //$NON-NLS-2$
        case "removeRegisterField" -> "deleteItem"; //$NON-NLS-1$ //$NON-NLS-2$
        case "addEnumValue" -> "addItem"; //$NON-NLS-1$ //$NON-NLS-2$
        case "setValueType" -> "setItemType"; //$NON-NLS-1$ //$NON-NLS-2$
        case "setRoleRight" -> "setRoleRights"; //$NON-NLS-1$ //$NON-NLS-2$
        default -> operation;
        };
    }

    /**
     * Сумісність із контрактом RSV: addRegisterField/removeRegisterField (fieldKind + field),
     * addEnumValue (value), setValueType (колекція визначається за іменем), setRoleRight
     * (role + object + right), setDefinedTypeTypes. Повертає копію аргументів із нашими іменами.
     */
    private static JsonObject normalizeAliases(String operation, JsonObject source) {
        JsonObject args = source.deepCopy();
        switch (operation) {
        case "addRegisterField", "removeRegisterField" -> { //$NON-NLS-1$ //$NON-NLS-2$
            copyIfAbsent(args, "item", "field"); //$NON-NLS-1$ //$NON-NLS-2$
            String fieldKind = args.has("fieldKind") ? args.get("fieldKind").getAsString() : "attribute"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            String collection = switch (fieldKind.toLowerCase(java.util.Locale.ROOT).replace(" ", "")) { //$NON-NLS-1$ //$NON-NLS-2$
            case "dimension", "dimensions", "измерение", "вимір" -> "dimensions"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            case "resource", "resources", "ресурс" -> "resources"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "attribute", "attributes", "реквизит", "реквізит" -> "attributes"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            default -> throw new IllegalArgumentException("fieldKind: dimension | resource | attribute, отримано " //$NON-NLS-1$
                    + fieldKind);
            };
            args.addProperty("collection", collection); //$NON-NLS-1$
        }
        case "addEnumValue" -> { //$NON-NLS-1$
            copyIfAbsent(args, "item", "value"); //$NON-NLS-1$ //$NON-NLS-2$
            copyIfAbsent(args, "item", "enumValue"); //$NON-NLS-1$ //$NON-NLS-2$
            args.addProperty("collection", "enumValues"); //$NON-NLS-1$ //$NON-NLS-2$
            args.remove("types"); //$NON-NLS-1$
        }
        case "setValueType" -> { //$NON-NLS-1$
            copyIfAbsent(args, "item", "field"); //$NON-NLS-1$ //$NON-NLS-2$
            copyIfAbsent(args, "item", "attribute"); //$NON-NLS-1$ //$NON-NLS-2$
            if (!args.has("collection")) { //$NON-NLS-1$
                args.addProperty("collection", "*"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        case "setRoleRight" -> { //$NON-NLS-1$
            if (!args.has("kind")) { //$NON-NLS-1$
                args.addProperty("kind", "Role"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            copyIfAbsent(args, "name", "role"); //$NON-NLS-1$ //$NON-NLS-2$
            copyIfAbsent(args, "objects", "object"); //$NON-NLS-1$ //$NON-NLS-2$
            copyIfAbsent(args, "rights", "right"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        case "addExchangePlanContent", "removeExchangePlanContent" -> copyIfAbsent(args, "objects", "content"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        case "addRecorder", "removeRecorder" -> { //$NON-NLS-1$ //$NON-NLS-2$
            copyIfAbsent(args, "objects", "recorders"); //$NON-NLS-1$ //$NON-NLS-2$
            copyIfAbsent(args, "objects", "document"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        case "addSubsystemContent", "removeSubsystemContent" -> copyIfAbsent(args, "objects", "content"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        case "setDefinedTypeTypes" -> { //$NON-NLS-1$
            copyIfAbsent(args, "types", "typeNames"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        default -> {
        }
        }
        return args;
    }

    private static void copyIfAbsent(JsonObject args, String target, String source) {
        if (!args.has(target) && args.has(source)) {
            args.add(target, args.get(source));
        }
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
                Types.setTransaction(transaction);
                try {
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
                } finally {
                    Types.setTransaction(null);
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

    /** createObject: масиви dimensions / resources / attributes створюють поля одразу з об'єктом. */
    private static void addInlineFields(IBmTransaction transaction, IProject project, String canonicalKind,
            String name, JsonObject arguments, JsonObject change) {
        EObject owner = null;
        com.google.gson.JsonArray added = new com.google.gson.JsonArray();
        for (String collection : new String[] {"dimensions", "resources", "attributes"}) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (!arguments.has(collection) || !arguments.get(collection).isJsonArray()) {
                continue;
            }
            if (owner == null) {
                owner = transaction.getTopObjectByFqn(canonicalKind + "." + name); //$NON-NLS-1$
            }
            for (JsonElement element : arguments.getAsJsonArray(collection)) {
                JsonObject field = element.getAsJsonObject().deepCopy();
                String fieldName = field.has("name") ? field.get("name").getAsString() : required(field, "item"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                if (!field.has("types") && field.has("type")) { //$NON-NLS-1$ //$NON-NLS-2$
                    com.google.gson.JsonArray single = new com.google.gson.JsonArray();
                    single.add(field.get("type").getAsString()); //$NON-NLS-1$
                    field.add("types", single); //$NON-NLS-1$
                }
                if (!field.has("types")) { //$NON-NLS-1$
                    com.google.gson.JsonArray single = new com.google.gson.JsonArray();
                    single.add("Строка"); //$NON-NLS-1$
                    field.add("types", single); //$NON-NLS-1$
                    if (!field.has("length")) { //$NON-NLS-1$
                        field.addProperty("length", 10); //$NON-NLS-1$
                    }
                }
                addChild(project, owner, collection, fieldName, field, true);
                added.add(collection + ":" + fieldName); //$NON-NLS-1$
            }
        }
        if (added.size() > 0) {
            change.add("fields", added); //$NON-NLS-1$
        }
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
        // окремі top-об'єкти, що належать об'єкту (Package.xdto) — інакше лишаються в BM сиротами
        if (bmObject instanceof com._1c.g5.v8.dt.metadata.mdclass.XDTOPackage xdtoPackage
                && xdtoPackage.getPackage() instanceof IBmObject bmPackage && bmPackage.bmIsTop()) {
            xdtoPackage.setPackage(null);
            transaction.detachTopObject(bmPackage);
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
            StringBuilder available = new StringBuilder();
            for (EReference candidate : owner.eClass().getEAllContainments()) {
                if (candidate.isMany()) {
                    available.append(available.length() == 0 ? "" : ", ").append(candidate.getName()); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            throw new IllegalArgumentException("Колекція " + refName + " недоступна для " //$NON-NLS-1$ //$NON-NLS-2$
                    + owner.eClass().getName() + ". Доступні: " + available); //$NON-NLS-1$
        }
        List<EObject> collection = (List<EObject>) owner.eGet(reference);
        for (EObject existing : collection) {
            if (childName.equalsIgnoreCase(Emf.name(existing))) {
                JsonObject duplicate = new JsonObject();
                duplicate.addProperty("alreadyExists", true); //$NON-NLS-1$
                duplicate.addProperty("added", childName); //$NON-NLS-1$
                duplicate.addProperty("collection", refName); //$NON-NLS-1$
                duplicate.addProperty("owner", Emf.name(owner)); //$NON-NLS-1$
                return duplicate;
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
            String dateFractions = arguments.has("dateFractions") //$NON-NLS-1$
                    ? arguments.get("dateFractions").getAsString() : null; //$NON-NLS-1$
            Boolean nonNegative = arguments.has("nonNegative") //$NON-NLS-1$
                    ? Boolean.valueOf(arguments.get("nonNegative").getAsBoolean()) : null; //$NON-NLS-1$
            child.eSet(typeFeature, Types.build(project, typeNames, length, precision, scale,
                    dateFractions, nonNegative));
        }
        JsonObject appliedProperties = new JsonObject();
        if (arguments.has("properties") && arguments.get("properties").isJsonObject()) { //$NON-NLS-1$ //$NON-NLS-2$
            for (var entry : arguments.get("properties").getAsJsonObject().entrySet()) { //$NON-NLS-1$
                appliedProperties.add(entry.getKey(),
                        setProperty(child, entry.getKey(), entry.getValue().getAsString()));
            }
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
        if (appliedProperties.size() > 0) {
            change.add("properties", appliedProperties); //$NON-NLS-1$
        }
        return change;
    }

    private static JsonObject withAdoption(JsonObject result, JsonObject adoption) {
        if (adoption != null) {
            result.add("extensionTypes", adoption); //$NON-NLS-1$
        }
        return result;
    }

    private static List<String> tailOf(JsonObject arguments, String attribute) {
        if (arguments.has("tabularSection") && !arguments.get("tabularSection").getAsString().isBlank()) { //$NON-NLS-1$ //$NON-NLS-2$
            return List.of(arguments.get("tabularSection").getAsString(), attribute); //$NON-NLS-1$
        }
        return List.of(attribute);
    }

    /** Після видалення реквізита/ТЧ прибирає з форм об'єкта елементи, що на нього посилались (cleanupForms:false — вимкнути). */
    private static JsonObject withFormCleanup(EObject owner, JsonObject arguments, JsonObject change,
            List<String> pathTail) {
        boolean cleanup = !arguments.has("cleanupForms") || arguments.get("cleanupForms").getAsBoolean(); //$NON-NLS-1$ //$NON-NLS-2$
        if (cleanup) {
            com.google.gson.JsonArray report = FormOps.cleanupAfterDelete(owner, pathTail);
            if (report.size() > 0) {
                change.add("formsCleaned", report); //$NON-NLS-1$
            }
            change.addProperty("warning", //$NON-NLS-1$
                    "Посилання в коді/запитах НЕ чистяться; з форм об'єкта прибрано поля/таблиці з цим dataPath."); //$NON-NLS-1$
        }
        return change;
    }

    /** collection="*" — шукає елемент за іменем в attributes, dimensions, resources. */
    private static EObject findItemAnywhere(EObject owner, String collection, String itemName) {
        if (!"*".equals(collection)) { //$NON-NLS-1$
            return findChild(owner, collection, itemName);
        }
        for (String candidate : new String[] {"attributes", "dimensions", "resources"}) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (owner.eClass().getEStructuralFeature(candidate) != null) {
                try {
                    return findChild(owner, candidate, itemName);
                } catch (IllegalArgumentException notHere) {
                    // шукаємо в наступній колекції
                }
            }
        }
        throw new IllegalArgumentException("Елемент не знайдено: " + itemName //$NON-NLS-1$
                + " (шукали в attributes, dimensions, resources)"); //$NON-NLS-1$
    }

    /** Знаходить елемент containment-колекції за іменем; помилка перелічує доступні колекції. */
    @SuppressWarnings("unchecked")
    static EObject findChild(EObject owner, String collection, String itemName) {
        EStructuralFeature feature = owner.eClass().getEStructuralFeature(collection);
        if (!(feature instanceof EReference reference) || !reference.isContainment() || !reference.isMany()) {
            StringBuilder available = new StringBuilder();
            for (EReference candidate : owner.eClass().getEAllContainments()) {
                if (candidate.isMany()) {
                    available.append(available.length() == 0 ? "" : ", ").append(candidate.getName()); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            throw new IllegalArgumentException("Колекція " + collection + " недоступна для " //$NON-NLS-1$ //$NON-NLS-2$
                    + owner.eClass().getName() + ". Доступні: " + available); //$NON-NLS-1$
        }
        for (EObject existing : (List<EObject>) owner.eGet(reference)) {
            if (itemName.equalsIgnoreCase(Emf.name(existing))) {
                return existing;
            }
        }
        throw new IllegalArgumentException("Елемент не знайдено: " + itemName + " у " + collection); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Змінює тип існуючого реквізита/виміру/ресурсу. */
    private static JsonObject setItemType(IProject project, EObject item, JsonObject arguments) {
        EStructuralFeature typeFeature = item.eClass().getEStructuralFeature("type"); //$NON-NLS-1$
        if (typeFeature == null) {
            throw new IllegalArgumentException("Елемент " + item.eClass().getName() + " не має типу"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!arguments.has("types") || arguments.get("types").getAsJsonArray().isEmpty()) { //$NON-NLS-1$ //$NON-NLS-2$
            throw new IllegalArgumentException("Обов'язковий параметр types (масив імен типів)"); //$NON-NLS-1$
        }
        List<String> typeNames = new ArrayList<>();
        arguments.get("types").getAsJsonArray().forEach(t -> typeNames.add(t.getAsString())); //$NON-NLS-1$
        JsonObject change = new JsonObject();
        change.addProperty("item", Emf.name(item)); //$NON-NLS-1$
        change.add("old", Emf.typeNames(item)); //$NON-NLS-1$
        item.eSet(typeFeature, Types.build(project, typeNames, intOrNull(arguments, "length"), //$NON-NLS-1$
                intOrNull(arguments, "precision"), intOrNull(arguments, "scale"), //$NON-NLS-1$ //$NON-NLS-2$
                arguments.has("dateFractions") ? arguments.get("dateFractions").getAsString() : null, //$NON-NLS-1$ //$NON-NLS-2$
                arguments.has("nonNegative") //$NON-NLS-1$
                        ? Boolean.valueOf(arguments.get("nonNegative").getAsBoolean()) : null)); //$NON-NLS-1$
        change.addProperty("new", String.join(", ", typeNames)); //$NON-NLS-1$ //$NON-NLS-2$
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
