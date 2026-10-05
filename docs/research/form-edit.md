# form-edit — редагування керованих форм (addFormField / addFormCommand / addFormGroup / deleteFormItem)

Дослідження і реалізація операцій редагування форм для `edit_metadata`.
**Досягнутий рівень: A** — елементи форм додаються ТИМ САМИМ механізмом, що й редактор
форм EDT (штатні сервіси `IFormItemManagementService` / `FormCommandManagementService`,
які виконують `AddFieldTask` / `AddGroupTask` / `AddButtonTask` / `AddFormCommandTask`):
сервіс сам призначає `id`, унікальне ім'я, тип поля за PropertyInfo, створює
extendedTooltip і contextMenu — жодного «голого» `EcoreUtil.create` для елементів.
Реалізація: `src/com/polischuk/edt/prl/tools/write/FormOps.java` (готова, компілюється;
case-вставки в `EditMetadataTool` — нижче, сам файл не змінював за умовами задачі).

EDT 2025.2.6 (`C:\Program Files\1C\1CE\components\1c-edt-2025.2.6+4-x86_64`).
Всі сигнатури зняті через `javap` / `javap -c` (clean-room, без декомпіляції вихідників).
Формат `Form.form` перевірений по реальних формах конфігурації retail
(`%USERPROFILE%\git\int\src\cf_edt\src`, read-only) і по live `get_form_image`.

## 1. FQN форм у BM-моделі

Md-об'єкт форми (mdclass `Form`/`BasicForm`) **contained** у mdo власника
(`<forms uuid=…><name>ФормаЭлемента</name>…` у `Catalog.mdo`) і має FQN
`Catalog.<Ім'я>.Form.<Ім'яФорми>` (видно у посиланнях `defaultObjectForm`).

Модель самої форми (`com._1c.g5.v8.dt.form.model.Form`, файл `Form.form`) — це
**окремий top-об'єкт**, «зовнішня властивість» (external property) md-об'єкта форми
через посилання `BasicForm.getForm()`. FQN зовнішньої властивості за
`MdTopObjectFqnGeneratorDelegate.generateExternalPropertyFqnInternal` (javap -c):

```
FQN(власник) + "." + StringUtils.capitalize(ім'я EReference)   // feature "form" → "Form"
```

Тобто:

| Форма | FQN top-об'єкта Form (модель форми) |
|---|---|
| форма об'єкта | `Catalog.Товары.Form.ФормаЭлемента.Form` |
| загальна форма | `CommonForm.МояФорма.Form` |

Підтвердження механіки: `FormTopObjectFqnGeneratorDelegate.generateStandaloneObjectFqn`
кидає `AssertionFailedException("Form package does not have standalone top objects")` —
форми реєструються ТІЛЬКИ як зовнішні властивості md-власника.

`FormOps.resolveForm` спершу бере `transaction.getTopObjectByFqn(formFqn)`, а якщо
клас/реєстрація відрізняються — fallback: top-об'єкт власника (`Catalog.Товары`) →
колекція `forms` → md-форма за ім'ям → feature `form` (`BasicForm.getForm()`), каст до
`com._1c.g5.v8.dt.form.model.Form`. Зворотний зв'язок теж є: `Form.getMdForm()`.

## 2. Сервіси і сигнатури

### 2.1. IFormItemManagementService — додавання елементів

`com._1c.g5.v8.dt.form.service.item.IFormItemManagementService` (бандл
`com._1c.g5.v8.dt.form`, пакет експортований). Ключові методи (`LAST = -1`):

```java
FormField addField (FormItemContainer parent, AbstractDataPath path, Form form, FormNewItemDescriptor d); // default → index LAST
FormGroup addGroup (FormItemContainer parent, ManagedFormGroupType type, Form form, FormNewItemDescriptor d);
Button    addButton(FormItemContainer parent, mcore.Command cmd, AbstractDataPath path, Form form, FormNewItemDescriptor d);
Table     addTable (FormVisualEntity parent, AbstractDataPath path, boolean fields, Form form, FormNewItemDescriptor d);
FormField addFieldWithTable(...); List<FormField> addTableFieldsByDataPath(Table, path, form, d);
Decoration addDecoration(...); Addition addAddition(...);
```

`javap -c FormItemManagementService.addField` показує повний конвеєр редактора:
`IModelObjectFactory.create(FORM_FIELD, version)` → `setId` через `FormIdentifierService`
(bm-властивості `FORM_ITEM_CURRENT_ID` на top-об'єкті форми) → id для extendedTooltip і
contextMenu → тип поля: `FormUtil.getTypeDescription(path, form, IDataSourceInfoAssociationService)`
→ `IFormItemTypeInformationService.getDefaultFieldType` → `IFormItemTypeManagementService.setType/setDefaultType`
→ `IDataPathConverter.needConvert/convert` → `setDataPath` → ім'я:
`setUniqueNameByDataPath` (якщо descriptor==null або без імені) → вставка в
`parent.getItems()`. Descriptor `null` — легальний (гілка `ifnonnull`).

`FormNewItemDescriptor(String itemName, Map<String,String> itemTitle /*lang→text*/, boolean formattedText)`.

**Реєстрація в OSGi підтверджена** дизасемблюванням активатора
`com._1c.g5.v8.dt.internal.form.FormPlugin` (лямбда `ServiceInitialization.schedule`):
`registrator.service(IFormItemManagementService.class).registerInjected()`. Там само
зареєстровані: `IFormItemTypeManagementService`, `IFormItemTypeInformationService`,
`IFormItemMovementService`, `IDataPathAssociationService`, `IDataPathConverter`,
`IDataSourceInfoAssociationService`, `IFormItemNamingService`, `ICommandNameService`,
`IFormExtensionService`, `IExtInfoManagementService`, `IFormGenerator`,
`IFormFieldGenerator` (form.generator — генерація типових форм цілком; для точкових
вставок не потрібен), а також `IModelObjectFactory` з property
`service.name=FormModelObjectFactory` (ключ property знятий з
`com._1c.g5.wiring.ServiceProperties.named` → `"service.name"`).
Отже, все дістається через `EdtServices.get/require` (реєстр OSGi), Guice не потрібен.

Штатні task-и (`com._1c.g5.v8.dt.form.service.item.task.AddFieldTask` тощо) — тонкі
обгортки `BmBasicTask2`, що (а) самоін'єктуються через `FormPlugin.getDefault().getInjector()`
і (б) делегують у `managementService.addField(...)`. Ми робимо те саме зсередини власного
`AbstractBmTask` — і отримуємо dryRun через `executeAndRollback`.

### 2.2. FormCommandManagementService — команди форми

`com._1c.g5.v8.dt.form.service.command.FormCommandManagementService` (клас, не інтерфейс;
пакет експортований; конструктор сам ін'єктує залежності через
`FormPlugin.getDefault().getInjector().injectMembers(this)` — `new` з нашого бандла легальний):

```java
void addCommand(Form form, FormCommand command);      // setId(FormIdentifierService.getNextCommandId), унікальне ім'я, form.getFormCommands().add
void deleteCommand(FormCommand command, boolean deleteRelatedButtons);
```

UI-флоу «Додати команду» (javap -c `FormCommandActionsGroup`):
`IModelObjectFactory.create(FormPackage.Literals.FORM_COMMAND, project.getVersion())` →
`new AddFormCommandTask(service, form, command)` → BM. Обробник призначається окремо:
`FormCommand.setAction(FormCommandHandlerContainer{ handler: CommandHandler{name} })` —
формат підтверджений XML реальних форм:

```xml
<action xsi:type="form:FormCommandHandlerContainer"><handler><name>МояКоманда</name></handler></action>
<use><common>true</common></use>
```

`FormOps.addFormCommand` створює FormCommand через названий OSGi-сервіс
`(service.name=FormModelObjectFactory)` + `IRuntimeVersionSupport.getRuntimeVersion(form)`
(fallback — `FormFactory.eINSTANCE.createFormCommand()` + явний `use.common=true`),
ставить title/action і викликає `addCommand`, далі опційно `addButton` (кнопка
прив'язується до команди параметром `mcore.Command` — `FormCommand extends mcore.Command`).

### 2.3. DeleteFormItemTask — видалення

`javap -c DeleteFormItemTask.execute`: `item.eContainer()` як `FormItemContainer` →
`getItems().remove(item)` + зачистка blob-ів картинок (`FormPicture` →
`transaction.getBlob(PictureFqnUtil.buildFormPictureBlobFqn(...))` → `removeBlob`).
`FormOps.deleteFormItem` відтворює головну гілку (remove з контейнера); зачистку
picture-blob-ів не робимо — це стосується лише елементів із кастомними картинками
(див. Обмеження).

### 2.4. dataPath

Модель: `AbstractDataPath.getSegments(): EList<String>` — сегменти **окремими
елементами** (`["Объект","Артикул"]`, `["Объект","Товары","Номенклатура"]`).
Доказ: `DataPathAssociationService.find(AbstractFormAttribute)` додає в список
`container.getName()` і `attribute.getName()` окремо; `AbstractDataPathImpl.toString`
джойнить сегменти. А от у XML `Form.form` серіалізується ОДНИМ елементом
`<segments>Объект.Артикул</segments>`: `FormXmlHelper` (getValue/setValue) робить
`Joiner.join` по `.` на запис і `split("\\.")` на читання — тільки для
`isDataPathSegments`. Тому на вході приймаємо рядок `"Объект.Артикул"` і ріжемо по
крапках. `DataPath` створюємо `FormFactory.eINSTANCE.createDataPath()` (impl успадковує
`BmObject`, версіє-залежних дефолтів у DataPath немає). `list`-поле реквізиту `objects`
(`DataPathReferredObject`) — derived, заповнюється конвеєром EDT.

Шлях за реквізитом об'єкта: `["<ГоловнийРеквізитФорми>", "<Ім'яРеквізита>"]`, де головний
реквізит — `FormAttribute.isMain()==true` (зазвичай `Объект`/`Список`; у formі списку —
динамічний список `Список`). Параметр `attribute` в addFormField так і працює;
`dataPath` — явна альтернатива (обов'язкова для довільних шляхів: колонки ТЧ
`"Объект.Товары.Номенклатура"`, реквізити форми).

## 3. Інтеграція в EditMetadataTool (case-вставки)

Операції самодостатні (як `RenameOps.renameObject`) — відкривають власну BM-транзакцію,
тож вставка ДО генеричного `AbstractBmTask`, поруч із `renameObject`:

```java
// у execute(), після блоку renameObject:
if ("addFormField".equals(operation)) {   //$NON-NLS-1$
    WriteGate.check();
    return FormOps.addFormField(arguments);
}
if ("addFormCommand".equals(operation)) { //$NON-NLS-1$
    WriteGate.check();
    return FormOps.addFormCommand(arguments);
}
if ("addFormGroup".equals(operation)) {   //$NON-NLS-1$
    WriteGate.check();
    return FormOps.addFormGroup(arguments);
}
if ("deleteFormItem".equals(operation)) { //$NON-NLS-1$
    WriteGate.check();
    return FormOps.deleteFormItem(arguments);
}
```

(`FormOps` додатково викликає `WriteGate.check()` сам — подвійний виклик нешкідливий.)

### 3.1. Зміни inputSchema

В enum `operation` додати: `"addFormField","addFormCommand","addFormGroup","deleteFormItem"`.
Нові властивості схеми:

```json
"form":{"type":"string","description":"Ім'я форми об'єкта (для CommonForm не потрібне)"},
"dataPath":{"type":"string","description":"addFormField: шлях даних, напр. Объект.Артикул чи Объект.Товары.Кол"},
"item":{"type":"string","description":"addFormField: ім'я елемента (авто за dataPath); deleteFormItem: елемент для видалення"},
"command":{"type":"string","description":"addFormCommand: ім'я команди"},
"handler":{"type":"string","description":"addFormCommand: метод модуля форми (за замовч. = command)"},
"button":{"type":"boolean","default":true,"description":"addFormCommand: створити кнопку"},
"group":{"type":"string","description":"addFormGroup: ім'я групи"},
"groupType":{"type":"string","description":"addFormGroup: UsualGroup|Pages|Page|ButtonGroup|ColumnGroup|CommandBar|Popup"},
"parent":{"type":"string","description":"Ім'я групи-контейнера (за замовч. корінь форми)"},
"title":{"type":"string","description":"Заголовок елемента/групи/команди мовою lang"}
```

(`attribute`, `lang`, `dryRun`, `project`, `kind`, `name` вже є в схемі; `attribute` в
addFormField — ім'я реквізита об'єкта, шлях будується від головного реквізита форми.)

### 3.2. Доповнення help()

```json
"addFormField":{"params":"kind, name, form, dataPath|attribute [, item, title, lang, parent, project, dryRun]",
  "description":"Додає поле у форму штатним сервісом EDT (id, ім'я, тип поля, підказка/меню — автоматично). dataPath: Объект.Реквизит, Объект.ТЧ.Колонка; attribute — коротко: реквізит від головного реквізита форми.",
  "example":{"operation":"addFormField","kind":"Справочник","name":"Номенклатура","form":"ФормаЭлемента","attribute":"Артикул","dryRun":true}},
"addFormCommand":{"params":"kind, name, form, command [, handler, title, button=true, parent, lang, project, dryRun]",
  "description":"Додає команду форми з обробником і (за замовчуванням) кнопку. Метод-обробник у модулі форми НЕ створюється — write_module_source.",
  "example":{"operation":"addFormCommand","kind":"Справочник","name":"Номенклатура","form":"ФормаЭлемента","command":"ЗаполнитьПоШаблону","title":"Заполнить по шаблону","dryRun":true}},
"addFormGroup":{"params":"kind, name, form, group [, groupType=UsualGroup, title, parent, lang, project, dryRun]",
  "description":"Додає групу елементів (UsualGroup, Pages/Page, ButtonGroup, ColumnGroup, CommandBar, Popup)."},
"deleteFormItem":{"params":"kind, name, form, item [, project, dryRun]",
  "description":"Видаляє елемент форми за ім'ям (поле/групу/кнопку/декорацію) з items контейнера. Реквізити форми, команди і обробники модуля НЕ чистяться."}
```

### 3.3. MANIFEST.MF — Import-Package (додати)

```
 com._1c.g5.v8.dt.core.model;resolution:=optional,
 com._1c.g5.v8.dt.form.model;resolution:=optional,
 com._1c.g5.v8.dt.form.service.command;resolution:=optional,
 com._1c.g5.v8.dt.form.service.item;resolution:=optional,
```

Усі чотири пакети експортуються бандлами EDT без обмежень (`Export-Package` без
x-internal/x-friends — перевірено в MANIFEST `com._1c.g5.v8.dt.form`). Комерційний
референс імпортував також `form.service.attribute` / `form.generator` — для наших
чотирьох операцій вони не потрібні (attribute-сервіси — для реквізитів ФОРМИ,
generator — для генерації форм цілком; кандидати на майбутні операції).

### 3.4. build.ps1 — блок $cp (додати 2 jar-и)

```powershell
    (Resolve-Jar "com._1c.g5.v8.dt.form.model"),
    ((Get-ChildItem $pluginsDir -Filter "com._1c.g5.v8.dt.form_3*.jar" | Sort-Object Name -Descending | Select-Object -First 1).FullName),
```

(Патерн `com._1c.g5.v8.dt.form_3*` — щоб не зачепити `com._1c.g5.v8.dt.form.model_…`,
`….form.ui_…` тощо; `Resolve-Jar "com._1c.g5.v8.dt.form"` за префіксом узяв би
неправильний jar. Перевірено: компіляція всіх 60 .java проєкту + FormOps.java проходить
`javac --release 17` з поточним $cp + ці два jar-и:
`com._1c.g5.v8.dt.form_31.1.4.v202605050943.jar`,
`com._1c.g5.v8.dt.form.model_14.0.0.v202605050943.jar`.)

## 4. Параметри реалізованих операцій (підсумок)

Спільні: `project?`, `kind` (алiаси KindRegistry), `name`, `form` (крім CommonForm), `dryRun`.

| Операція | Специфічні параметри | Що робить сервісно |
|---|---|---|
| addFormField | `dataPath` або `attribute`; `item?`, `title?`, `lang?`, `parent?` | id, унікальне ім'я, тип поля за PropertyInfo, tooltip+menu, вставка в контейнер |
| addFormCommand | `command`, `handler?`, `title?`, `button?=true`, `parent?`, `lang?` | id команди, унікальне ім'я, action→handler, use.common; кнопка через addButton |
| addFormGroup | `group`, `groupType?=UsualGroup`, `title?`, `parent?`, `lang?` | id, ім'я, тип групи + extInfo |
| deleteFormItem | `item` | remove з items контейнера (рекурсивний пошук за ім'ям, включно з autoCommandBar) |

dryRun усюди — `model.executeAndRollback(task)`; запис — `model.getGlobalContext().execute(task)`
(конвеєр derived data → серіалізація `Form.form`), як у решті операцій.

## 5. Обмеження

1. **Обробник команди в модулі форми не створюється** — тільки посилання
   (`action/handler/name`). Процедуру `&НаКлиенте` додавати через `write_module_source`;
   до того буде помилка валідації «обробник не знайдено» (очікувано, повідомляється в note).
2. **deleteFormItem не чистить**: реквізити форми, команди (і їх кнопки-двійники),
   обробники подій у модулі, blob-и кастомних картинок елемента (рідкісний випадок;
   штатний DeleteFormItemTask чистить — можна додати пізніше через PictureFqnUtil).
3. **addFormField не створює реквізит**: dataPath має вказувати на існуючі дані
   (реквізит об'єкта через головний реквізит форми, реквізит форми, колонку ТЧ).
   Якщо шлях не резолвиться в PropertyInfo — сервіс ставить дефолтний тип поля
   (InputField), як і редактор при ручному введенні шляху; поле буде, але з
   попередженням валідації, поки не з'явиться реквізит.
4. **Поля-колонки таблиць**: addFormField з `parent`=ім'я таблиці і
   `dataPath="Объект.Товары.Кол"` створює колонку; але СТВОРЕННЯ самої таблиці
   (`addTable` для ТЧ/динсписку) не реалізовано — окрема операція addFormTable
   (сервіс `addTable`/`addFieldWithTable` готовий, дослідження покриває).
5. **Розширення (CFE)**: операції не перевіряють adopted-стан форм розширень;
   для дороблених форм розширень поведінка не тестована (`IFormExtensionService` —
   окремий шар). Використовувати на власних формах конфігурації/розширення.
6. `FormCommandManagementService`/task-и тягнуть Guice-injector бандла форм ліниво —
   перший виклик після старту EDT може б у ти повільнішим (ініціалізація інжектора).
7. Групи типу Page коректні лише всередині Pages — сервіс не валідує вкладеність,
   як і редактор; перевіряти get_validation_errors.

## 6. План живого тесту (після інтеграції і build+deploy)

Все на проєкті retail (git — страховка відкоту). Або створити тестовий довідник, або
тестову форму в існуючому. Кроки:

1. **Підготовка** (git чистий): `edit_metadata createObject kind=Справочник name=МСП_Тест`,
   `addAttribute … attribute=Артикул types=["Строка"] length=25`.
   Форму створити в EDT UI (правий клік → Новая форма) АБО взяти існуючий об'єкт
   із формою (напр. `Catalog.INT_ВидСертификата`, `ФормаЭлемента` — 3 поля, без ТЧ).
2. **dryRun**: `addFormField kind=Справочник name=INT_ВидСертификата form=ФормаЭлемента
   attribute=Комментарий dryRun=true` → у відповіді change.added/dataPath, файл не змінився
   (`git status` порожній).
3. **Поле**: те саме без dryRun → перевірити:
   `get_form_image` — нове поле з dataPath `Объект.Комментарий`;
   `git diff src/Catalogs/.../Form.form` — `<items xsi:type="form:FormField">` з
   `<segments>Объект.Комментарий</segments>`, id-шники без колізій;
   `get_validation_errors` по об'єкту — чисто.
4. **Група**: `addFormGroup … group=МояГрупа title="Моя група"` →
   `<items xsi:type="form:FormGroup">` type=UsualGroup; потім
   `addFormField … attribute=… parent=МояГрупа` → поле всередині групи.
5. **Команда**: `addFormCommand … command=МояКоманда title="Моя команда"` →
   formCommands+кнопка в XML; `write_module_source` — додати
   `&НаКлиенте Процедура МояКоманда(Команда) … КонецПроцедуры`;
   `get_validation_errors` → чисто.
6. **Видалення**: `deleteFormItem … item=МояКоманда` (кнопка),
   `deleteFormItem … item=МояГрупа` → group зник разом із вкладеним полем (containment).
7. **Відкіт**: `git checkout -- src/Catalogs/INT_ВидСертификата` (+ видалити МСП_Тест
   через deleteObject, якщо створювали) → EDT сам перечитає файли.
8. **Регрес**: відкрити форму в редакторі EDT — переконатися, що редактор відображає
   елементи і не скаржиться (найважливіший критерій «як рідний»).

## 7. Довідка: знята механіка (для майбутніх операцій)

- `IFormItemMovementService` — переміщення елементів (moveUp/moveDown/reparent).
- `IFormAttributeAssociationService` + `FormAttributeManagementService.addAttribute(Form, FormAttribute)`
  (+`AddFormAttributeTask`) — РЕКВІЗИТИ форми (створення FormAttribute з valueType);
  зв'язка з `IDataPathAssociationService.find(attr)` дає dataPath для addField.
- `addTable`/`addFieldWithTable`/`addTableFieldsByDataPath` — таблиці з колонками одним викликом.
- `IFormGenerator`/`IFormFieldGenerator` (OSGi) — генерація типової форми
  (кандидат для createForm: створити md Form + згенерувати вміст).
- `FormItemDataPathService.setDataPath` / `ChangeDataPathTask` — зміна dataPath існуючого поля.
- `IDataPathConverter.convert` — конвертація шляхів при перенесенні між контейнерами.
