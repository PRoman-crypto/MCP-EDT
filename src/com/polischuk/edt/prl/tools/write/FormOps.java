/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.write;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.ecore.EObject;

import com.google.gson.JsonObject;
import com.polischuk.edt.prl.edt.EdtServices;
import com.polischuk.edt.prl.edt.Emf;
import com.polischuk.edt.prl.edt.KindRegistry;
import com.polischuk.edt.prl.edt.V8Access;
import com.polischuk.edt.prl.edt.WriteGate;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.form.model.AbstractDataPath;
import com._1c.g5.v8.dt.form.model.Button;
import com._1c.g5.v8.dt.form.model.CommandHandler;
import com._1c.g5.v8.dt.form.model.DataPath;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormAttribute;
import com._1c.g5.v8.dt.form.model.FormCommand;
import com._1c.g5.v8.dt.form.model.FormCommandHandlerContainer;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormField;
import com._1c.g5.v8.dt.form.model.FormGroup;
import com._1c.g5.v8.dt.form.model.FormItem;
import com._1c.g5.v8.dt.form.model.FormItemContainer;
import com._1c.g5.v8.dt.form.model.FormPackage;
import com._1c.g5.v8.dt.form.model.ManagedFormGroupType;
import com._1c.g5.v8.dt.form.service.command.FormCommandManagementService;
import com._1c.g5.v8.dt.form.service.item.FormNewItemDescriptor;
import com._1c.g5.v8.dt.form.service.item.IFormItemManagementService;
import com._1c.g5.v8.dt.metadata.mdclass.AdjustableBoolean;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.platform.version.IRuntimeVersionSupport;
import com._1c.g5.v8.dt.platform.version.Version;

/**
 * Операції редагування керованих форм для edit_metadata.
 *
 * Механіка — та сама, що в редакторі форм EDT: штатний OSGi-сервіс
 * {@link IFormItemManagementService} (він же виконує AddFieldTask/AddGroupTask/AddButtonTask)
 * сам створює елемент через IModelObjectFactory, призначає id (FormIdentifierService),
 * унікальне ім'я (IFormItemNamingService), тип поля за PropertyInfo, extendedTooltip
 * і contextMenu. Команди — через {@link FormCommandManagementService} (як AddFormCommandTask).
 *
 * FQN верхнього об'єкта форми: {@code <Kind>.<Ім'я>.Form.<Ім'яФорми>.Form}
 * (зовнішня властивість "form" md-об'єкта форми, FQN власника + capitalize(feature));
 * для загальної форми — {@code CommonForm.<Ім'я>.Form}. Додатково є fallback-резолюція
 * через навігацію md-власника (колекція forms → getForm()).
 *
 * dryRun — виконання в транзакції з відкотом (executeAndRollback), як в EditMetadataTool.
 */
public final class FormOps {

    private FormOps() {
    }

    // ------------------------------------------------------------------ addFormField

    /**
     * Додає поле форми за реквізитом. Параметри: kind, name, form (не для CommonForm),
     * dataPath ("Объект.Артикул") або attribute ("Артикул" — шлях будується від головного
     * реквізита форми), [item — ім'я елемента, title, lang, parent — ім'я групи, dryRun].
     */
    public static JsonObject addFormField(JsonObject arguments) {
        return run("addFormField", arguments, (transaction, form) -> { //$NON-NLS-1$
            AbstractDataPath path = buildDataPath(form, arguments);
            FormItemContainer container = container(form, arguments);
            FormNewItemDescriptor descriptor = descriptor(arguments, "item"); //$NON-NLS-1$
            IFormItemManagementService service = EdtServices.require(IFormItemManagementService.class);
            FormField field = service.addField(container, path, form, descriptor);
            JsonObject change = new JsonObject();
            change.addProperty("added", field.getName()); //$NON-NLS-1$
            change.addProperty("class", field.eClass().getName()); //$NON-NLS-1$
            change.addProperty("id", field.getId()); //$NON-NLS-1$
            change.addProperty("dataPath", pathText(field.getDataPath())); //$NON-NLS-1$
            change.addProperty("fieldType", field.getType() == null ? null : field.getType().getName()); //$NON-NLS-1$
            change.addProperty("container", containerName(container)); //$NON-NLS-1$
            return change;
        });
    }

    // ------------------------------------------------------------------ addFormCommand

    /**
     * Додає команду форми (з обробником) і, за замовчуванням, кнопку для неї.
     * Параметри: kind, name, form, command; [handler — ім'я методу модуля форми
     * (за замовчуванням = command), title, lang, button:false — без кнопки,
     * parent — контейнер кнопки, dryRun]. Сам метод-обробник у модулі форми
     * НЕ створюється — додайте його через write_module_source.
     */
    public static JsonObject addFormCommand(JsonObject arguments) {
        return run("addFormCommand", arguments, (transaction, form) -> { //$NON-NLS-1$
            String commandName = required(arguments, "command"); //$NON-NLS-1$
            String handlerName = optional(arguments, "handler") != null //$NON-NLS-1$
                    ? arguments.get("handler").getAsString() : commandName; //$NON-NLS-1$
            String lang = lang(arguments);

            FormCommand command = createFormCommand(form);
            command.setName(commandName);
            String title = optional(arguments, "title"); //$NON-NLS-1$
            if (title != null) {
                command.getTitle().put(lang, title);
            }
            CommandHandler handler = FormFactory.eINSTANCE.createCommandHandler();
            handler.setName(handlerName);
            FormCommandHandlerContainer action = FormFactory.eINSTANCE.createFormCommandHandlerContainer();
            action.setHandler(handler);
            command.setAction(action);
            if (command.getUse() == null) {
                AdjustableBoolean use = MdClassFactory.eINSTANCE.createAdjustableBoolean();
                use.setCommon(true);
                command.setUse(use);
            }
            // штатний сервіс: призначає id (FormIdentifierService), унікальне ім'я,
            // додає в form.getFormCommands() — як AddFormCommandTask редактора форм
            new FormCommandManagementService().addCommand(form, command);

            JsonObject change = new JsonObject();
            change.addProperty("addedCommand", command.getName()); //$NON-NLS-1$
            change.addProperty("commandId", command.getId()); //$NON-NLS-1$
            change.addProperty("handler", handlerName); //$NON-NLS-1$

            boolean withButton = !arguments.has("button") || arguments.get("button").getAsBoolean(); //$NON-NLS-1$ //$NON-NLS-2$
            if (withButton) {
                FormItemContainer container = container(form, arguments);
                IFormItemManagementService service = EdtServices.require(IFormItemManagementService.class);
                Button button = service.addButton(container, command, null, form, null);
                change.addProperty("addedButton", button.getName()); //$NON-NLS-1$
                change.addProperty("buttonId", button.getId()); //$NON-NLS-1$
                change.addProperty("buttonContainer", containerName(container)); //$NON-NLS-1$
            }
            change.addProperty("note", //$NON-NLS-1$
                    "Метод-обробник '" + handlerName //$NON-NLS-1$
                    + "' у модулі форми не створено — додайте процедуру з директивою &НаКлиенте " //$NON-NLS-1$
                    + "через write_module_source."); //$NON-NLS-1$
            return change;
        });
    }

    // ------------------------------------------------------------------ addFormGroup

    /**
     * Додає групу елементів. Параметри: kind, name, form, group (ім'я);
     * [groupType: UsualGroup (за замовчуванням), Pages, Page, ButtonGroup, ColumnGroup,
     * CommandBar, Popup; title, lang, parent, dryRun].
     */
    public static JsonObject addFormGroup(JsonObject arguments) {
        return run("addFormGroup", arguments, (transaction, form) -> { //$NON-NLS-1$
            String groupName = required(arguments, "group"); //$NON-NLS-1$
            ManagedFormGroupType type = groupType(optional(arguments, "groupType")); //$NON-NLS-1$
            FormItemContainer container = container(form, arguments);
            Map<String, String> titles = null;
            String title = optional(arguments, "title"); //$NON-NLS-1$
            if (title != null) {
                titles = new LinkedHashMap<>();
                titles.put(lang(arguments), title);
            }
            FormNewItemDescriptor descriptor = new FormNewItemDescriptor(groupName, titles, false);
            IFormItemManagementService service = EdtServices.require(IFormItemManagementService.class);
            FormGroup group = service.addGroup(container, type, form, descriptor);
            JsonObject change = new JsonObject();
            change.addProperty("added", group.getName()); //$NON-NLS-1$
            change.addProperty("groupType", group.getType() == null ? null : group.getType().getName()); //$NON-NLS-1$
            change.addProperty("id", group.getId()); //$NON-NLS-1$
            change.addProperty("container", containerName(container)); //$NON-NLS-1$
            return change;
        });
    }

    // ------------------------------------------------------------------ deleteFormItem

    /**
     * Видаляє елемент форми (поле, групу, кнопку, декорацію…) за ім'ям.
     * Параметри: kind, name, form, item; [dryRun]. Логіка — як DeleteFormItemTask:
     * елемент вилучається з items свого контейнера. Реквізити форми та команди,
     * на які посилався елемент, НЕ видаляються.
     */
    public static JsonObject deleteFormItem(JsonObject arguments) {
        return run("deleteFormItem", arguments, (transaction, form) -> { //$NON-NLS-1$
            String itemName = required(arguments, "item"); //$NON-NLS-1$
            FormItem item = findItem(form, itemName);
            if (item == null) {
                throw new IllegalArgumentException("Елемент форми не знайдено: " + itemName //$NON-NLS-1$
                        + ". Список елементів — get_form_image format=structure."); //$NON-NLS-1$
            }
            if (!(item.eContainer() instanceof FormItemContainer container)) {
                throw new IllegalStateException("Елемент " + itemName //$NON-NLS-1$
                        + " не можна видалити: контейнер " //$NON-NLS-1$
                        + (item.eContainer() == null ? "відсутній" : item.eContainer().eClass().getName())); //$NON-NLS-1$
            }
            String className = item.eClass().getName();
            container.getItems().remove(item);
            JsonObject change = new JsonObject();
            change.addProperty("deleted", itemName); //$NON-NLS-1$
            change.addProperty("class", className); //$NON-NLS-1$
            change.addProperty("container", containerName(container)); //$NON-NLS-1$
            change.addProperty("warning", //$NON-NLS-1$
                    "Реквізити форми і команди, на які посилався елемент, не видалені; " //$NON-NLS-1$
                    + "обробники в модулі форми теж — перевірте get_validation_errors."); //$NON-NLS-1$
            return change;
        });
    }

    // ------------------------------------------------------------------ очищення форм

    /**
     * Прибирає з усіх форм об'єкта елементи, що посилаються на видалений реквізит чи ТЧ:
     * поля/таблиці з dataPath, що починається з [головнийРеквізит, pathTail...]. Викликається
     * з тієї ж BM-транзакції, що й видалення (форми — окремі top-об'єкти тієї ж моделі).
     * pathTail: [реквізит] | [ТЧ] | [ТЧ, колонка].
     */
    static com.google.gson.JsonArray cleanupAfterDelete(EObject owner, List<String> pathTail) {
        com.google.gson.JsonArray report = new com.google.gson.JsonArray();
        if (!(Emf.get(owner, "forms") instanceof List<?> forms)) { //$NON-NLS-1$
            return report;
        }
        for (Object formMd : forms) {
            if (!(formMd instanceof EObject md) || !(Emf.get(md, "form") instanceof Form form)) { //$NON-NLS-1$
                continue;
            }
            List<FormItem> victims = new ArrayList<>();
            Deque<FormItem> queue = new ArrayDeque<>(form.getItems());
            while (!queue.isEmpty()) {
                FormItem item = queue.poll();
                if (Emf.get(item, "dataPath") instanceof AbstractDataPath path && startsWith(path, pathTail)) { //$NON-NLS-1$
                    victims.add(item); // вкладені елементи (колонки таблиці) зникнуть разом із контейнером
                    continue;
                }
                if (item instanceof FormItemContainer container) {
                    queue.addAll(container.getItems());
                }
            }
            if (victims.isEmpty()) {
                continue;
            }
            com.google.gson.JsonArray removed = new com.google.gson.JsonArray();
            for (FormItem item : victims) {
                if (item.eContainer() instanceof FormItemContainer container) {
                    container.getItems().remove(item);
                    removed.add(item.getName());
                }
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("form", Emf.name(md)); //$NON-NLS-1$
            entry.add("removedItems", removed); //$NON-NLS-1$
            report.add(entry);
        }
        return report;
    }

    /** dataPath = [головний реквізит форми, tail...]: збіг по префіксу, без урахування регістру. */
    private static boolean startsWith(AbstractDataPath path, List<String> tail) {
        List<String> segments = path.getSegments();
        if (segments.size() < tail.size() + 1) {
            return false;
        }
        for (int i = 0; i < tail.size(); i++) {
            if (!segments.get(i + 1).equalsIgnoreCase(tail.get(i))) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ каркас транзакції

    @FunctionalInterface
    private interface FormWork {
        JsonObject apply(IBmTransaction transaction, Form form);
    }

    private static JsonObject run(String operation, JsonObject arguments, FormWork work) {
        WriteGate.check();
        String projectName = optional(arguments, "project"); //$NON-NLS-1$
        String kind = KindRegistry.canonical(required(arguments, "kind")); //$NON-NLS-1$
        String name = required(arguments, "name"); //$NON-NLS-1$
        boolean commonForm = "CommonForm".equals(kind); //$NON-NLS-1$
        String formName = commonForm ? null : required(arguments, "form"); //$NON-NLS-1$
        boolean dryRun = arguments.has("dryRun") && arguments.get("dryRun").getAsBoolean(); //$NON-NLS-1$ //$NON-NLS-2$

        IProject project = V8Access.resolveEclipseProject(projectName);
        IBmModel model = EdtServices.require(IBmModelManager.class).getModel(project);
        String formFqn = commonForm
                ? kind + "." + name + ".Form" //$NON-NLS-1$ //$NON-NLS-2$
                : kind + "." + name + ".Form." + formName + ".Form"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        AbstractBmTask<JsonObject> task = new AbstractBmTask<>("MCP:PRL edit_metadata " + operation) { //$NON-NLS-1$
            @Override
            public JsonObject execute(IBmTransaction transaction, IProgressMonitor monitor) {
                Form form = resolveForm(transaction, kind, name, formName, formFqn);
                return work.apply(transaction, form);
            }
        };
        JsonObject change = dryRun ? model.executeAndRollback(task) : model.getGlobalContext().execute(task);

        JsonObject result = new JsonObject();
        result.addProperty("operation", operation); //$NON-NLS-1$
        result.addProperty("form", formFqn); //$NON-NLS-1$
        result.addProperty("dryRun", dryRun); //$NON-NLS-1$
        result.addProperty("applied", !dryRun); //$NON-NLS-1$
        result.add("change", change); //$NON-NLS-1$
        if (!dryRun) {
            result.addProperty("note", //$NON-NLS-1$
                    "Зміна застосована в модель EDT; Form.form серіалізується автоматично."); //$NON-NLS-1$
        }
        return result;
    }

    /** Форма: спершу за FQN top-об'єкта, потім через навігацію md-власника (forms → form). */
    private static Form resolveForm(IBmTransaction transaction, String kind, String name,
            String formName, String formFqn) {
        IBmObject top = transaction.getTopObjectByFqn(formFqn);
        if (top instanceof Form form) {
            return form;
        }
        String ownerFqn = kind + "." + name; //$NON-NLS-1$
        IBmObject owner = transaction.getTopObjectByFqn(ownerFqn);
        if (owner == null) {
            throw new IllegalArgumentException("Об'єкт не знайдено за FQN: " + ownerFqn); //$NON-NLS-1$
        }
        EObject formMd = owner;
        if (formName != null) {
            formMd = null;
            if (Emf.get(owner, "forms") instanceof List<?> forms) { //$NON-NLS-1$
                for (Object candidate : forms) {
                    if (candidate instanceof EObject form
                            && formName.equalsIgnoreCase(Emf.name(form))) {
                        formMd = form;
                        break;
                    }
                }
            }
            if (formMd == null) {
                throw new IllegalArgumentException("Форма не знайдена: " + formName //$NON-NLS-1$
                        + " у " + ownerFqn + ". Список форм — get_object_details."); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        Object formModel = Emf.get(formMd, "form"); //$NON-NLS-1$
        if (formModel instanceof Form form) {
            return form;
        }
        throw new IllegalStateException("Не вдалося отримати модель форми (" + formFqn //$NON-NLS-1$
                + "): властивість form=" + (formModel == null ? "null" : formModel.getClass().getSimpleName()) //$NON-NLS-1$ //$NON-NLS-2$
                + ". Можливо, форма ще не завантажена в BM-модель."); //$NON-NLS-1$
    }

    // ------------------------------------------------------------------ dataPath

    /**
     * DataPath з параметрів: dataPath ("Объект.Артикул" → сегменти за крапками)
     * або attribute (сегменти [головнийРеквізит, attribute]).
     */
    private static AbstractDataPath buildDataPath(Form form, JsonObject arguments) {
        List<String> segments = new ArrayList<>();
        String dataPath = optional(arguments, "dataPath"); //$NON-NLS-1$
        if (dataPath != null) {
            for (String segment : dataPath.split("\\.")) { //$NON-NLS-1$
                if (!segment.isBlank()) {
                    segments.add(segment.strip());
                }
            }
        } else {
            String attribute = optional(arguments, "attribute"); //$NON-NLS-1$
            if (attribute == null) {
                throw new IllegalArgumentException(
                        "Вкажіть dataPath (напр. \"Объект.Артикул\") або attribute (ім'я реквізита об'єкта)"); //$NON-NLS-1$
            }
            String main = mainAttributeName(form);
            if (main == null) {
                throw new IllegalArgumentException("У форми немає головного реквізита — " //$NON-NLS-1$
                        + "вкажіть повний dataPath (напр. \"Объект." + attribute + "\")"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            segments.add(main);
            segments.add(attribute);
        }
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("Порожній dataPath"); //$NON-NLS-1$
        }
        DataPath path = FormFactory.eINSTANCE.createDataPath();
        path.getSegments().addAll(segments);
        return path;
    }

    /** Ім'я головного реквізита форми (main=true), зазвичай "Объект"/"Список". */
    private static String mainAttributeName(Form form) {
        if (Emf.get(form, "attributes") instanceof List<?> attributes) { //$NON-NLS-1$
            for (Object attribute : attributes) {
                if (attribute instanceof FormAttribute formAttribute && formAttribute.isMain()) {
                    return formAttribute.getName();
                }
            }
        }
        return null;
    }

    private static String pathText(AbstractDataPath path) {
        return path == null ? null : String.join(".", path.getSegments()); //$NON-NLS-1$
    }

    // ------------------------------------------------------------------ контейнери й пошук

    /** Контейнер для нового елемента: форма або група/елемент-контейнер за параметром parent. */
    private static FormItemContainer container(Form form, JsonObject arguments) {
        String parent = optional(arguments, "parent"); //$NON-NLS-1$
        if (parent == null) {
            return form;
        }
        FormItem item = findItem(form, parent);
        if (item instanceof FormItemContainer container) {
            return container;
        }
        throw new IllegalArgumentException(item == null
                ? "Контейнер не знайдено: " + parent //$NON-NLS-1$
                : "Елемент " + parent + " (" + item.eClass().getName() + ") не є контейнером"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** Пошук елемента за ім'ям у дереві items форми (включно з autoCommandBar). */
    private static FormItem findItem(Form form, String name) {
        Deque<FormItem> queue = new ArrayDeque<>();
        queue.addAll(form.getItems());
        if (Emf.get(form, "autoCommandBar") instanceof FormItem commandBar) { //$NON-NLS-1$
            queue.add(commandBar);
        }
        while (!queue.isEmpty()) {
            FormItem item = queue.poll();
            if (name.equalsIgnoreCase(item.getName())) {
                return item;
            }
            if (item instanceof FormItemContainer container) {
                queue.addAll(container.getItems());
            }
        }
        return null;
    }

    private static String containerName(FormItemContainer container) {
        return container instanceof Form ? "Form" //$NON-NLS-1$
                : container instanceof FormItem item ? item.getName() : container.eClass().getName();
    }

    // ------------------------------------------------------------------ фабрики й утиліти

    /**
     * FormCommand через IModelObjectFactory форм (service.name=FormModelObjectFactory,
     * версійно-залежні дефолти — як робить редактор форм); fallback — FormFactory.
     */
    private static FormCommand createFormCommand(Form form) {
        try {
            org.osgi.framework.Bundle bundle = org.osgi.framework.FrameworkUtil.getBundle(FormOps.class);
            org.osgi.framework.BundleContext context = bundle == null ? null : bundle.getBundleContext();
            if (context != null) {
                var references = context.getServiceReferences(IModelObjectFactory.class,
                        "(service.name=FormModelObjectFactory)"); //$NON-NLS-1$
                if (!references.isEmpty()) {
                    IModelObjectFactory factory = context.getService(references.iterator().next());
                    IRuntimeVersionSupport versionSupport = EdtServices.get(IRuntimeVersionSupport.class);
                    Version version = versionSupport == null ? null : versionSupport.getRuntimeVersion(form);
                    if (factory != null && version != null) {
                        EObject created = factory.create(FormPackage.Literals.FORM_COMMAND, version);
                        if (created instanceof FormCommand command) {
                            return command;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // fallback нижче
        }
        return FormFactory.eINSTANCE.createFormCommand();
    }

    /** Тип групи за літералом EMF-enum (UsualGroup, Pages, Page…); за замовчуванням UsualGroup. */
    private static ManagedFormGroupType groupType(String literal) {
        if (literal == null || literal.isBlank()) {
            return ManagedFormGroupType.USUAL_GROUP;
        }
        ManagedFormGroupType byLiteral = ManagedFormGroupType.get(literal);
        if (byLiteral != null) {
            return byLiteral;
        }
        String normalized = literal.replace("_", "").toLowerCase(Locale.ROOT); //$NON-NLS-1$ //$NON-NLS-2$
        for (ManagedFormGroupType candidate : ManagedFormGroupType.values()) {
            if (candidate.getName().toLowerCase(Locale.ROOT).equals(normalized)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Невідомий groupType: " + literal //$NON-NLS-1$
                + ". Допустимі: UsualGroup, Pages, Page, ButtonGroup, ColumnGroup, CommandBar, Popup…"); //$NON-NLS-1$
    }

    /** Дескриптор нового елемента з параметрів nameParam/title, або null (авто-ім'я за dataPath). */
    private static FormNewItemDescriptor descriptor(JsonObject arguments, String nameParam) {
        String itemName = optional(arguments, nameParam);
        String title = optional(arguments, "title"); //$NON-NLS-1$
        if (itemName == null && title == null) {
            return null;
        }
        Map<String, String> titles = null;
        if (title != null) {
            titles = new LinkedHashMap<>();
            titles.put(lang(arguments), title);
        }
        return new FormNewItemDescriptor(itemName, titles, false);
    }

    private static String lang(JsonObject arguments) {
        String lang = optional(arguments, "lang"); //$NON-NLS-1$
        return lang == null ? "ru" : lang; //$NON-NLS-1$
    }

    private static String optional(JsonObject arguments, String name) {
        return arguments.has(name) && !arguments.get(name).isJsonNull()
                && !arguments.get(name).getAsString().isBlank()
                ? arguments.get(name).getAsString() : null;
    }

    private static String required(JsonObject arguments, String name) {
        String value = optional(arguments, name);
        if (value == null) {
            throw new IllegalArgumentException("Обов'язковий параметр відсутній: " + name); //$NON-NLS-1$
        }
        return value;
    }
}
