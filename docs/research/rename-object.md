# renameObject — перейменування об'єкта метаданих штатним refactoring-сервісом EDT

Дослідження для операції `renameObject` інструмента `edit_metadata`.
**Досягнутий рівень: A** — перейменування З ОНОВЛЕННЯМ ПОСИЛАНЬ (BSL-код, форми, права,
похідні поля запитів), повністю headless, тим самим механізмом, що й UI-команда
Rename/Refactor в EDT. Реалізація: `src/com/polischuk/edt/prl/tools/write/RenameOps.java`.

EDT 2025.2.6 (`C:\Program Files\1C\1CE\components\1c-edt-2025.2.6+4-x86_64`).
Всі сигнатури зняті через `javap`/`javap -c` (clean-room, без декомпіляції вихідників).

## 1. Архітектура штатного rename-рефакторингу

### 1.1. Сервіси

**`com._1c.g5.v8.dt.md.refactoring.core.IMdRefactoringService`** (бандл
`com._1c.g5.v8.dt.md.refactoring`, jar `com._1c.g5.v8.dt.md.refactoring_1.0.700.v202605050943.jar`):

```java
Collection<IRefactoring> createMdObjectRenameRefactoring(MdObject object, String newName);
IRefactoring createMdObjectDeleteRefactoring(Collection<? extends MdObject> objects);
IRefactoring createSubsystemMoveRefactoring(Subsystem subsystem, MdObject target);
IRefactoring createPredefinedItemDeleteRefactoring(Collection<? extends PredefinedItem>);
IRefactoring createPredefinedItemRenameRefactoring(PredefinedItem item, String newName);
IRefactoring createPredefinedItemMoveRefactoring(PredefinedItem item, EObject target);
```

**Реєстрація в OSGi підтверджена** дизасемблюванням активатора `MdRefactoringPlugin`
(лямбда `ServiceInitialization.schedule`):
`registrator.service(IMdRefactoringService.class).registerInjected()` через
`com._1c.g5.wiring.InjectorAwareServiceRegistrator` → сервіс доступний через
`EdtServices.require(IMdRefactoringService.class)`.

**`com._1c.g5.v8.dt.refactoring.core.IRefactoringService`** (бандл
`com._1c.g5.v8.dt.refactoring.core`, jar `com._1c.g5.v8.dt.refactoring.core_4.3.200.v202605050943.jar`) —
нижній рівень, викликається зсередини `MdRefactoringService`:

```java
IRefactoring initiateRename(RefactoringTask task, IRefactoringOperation mainOperation,
        String newName, RefactoringSettings settings);
IRefactoring initiateDelete(Collection<RefactoringTask> tasks, RefactoringSettings settings);
```

Допоміжні типи (усі в експортованому пакеті `com._1c.g5.v8.dt.refactoring.core`):

```java
RefactoringTask(IBmObject bmObject, String operationName)
RefactoringSettings()   // ignoreSupportSettings, ignoreUnknownCheck, batchSessionHandle...
interface IRefactoring { String getTitle(); RefactoringStatus getStatus();
                         Collection<IRefactoringItem> getItems(); void perform(); }
interface IRefactoringItem { String getName(); boolean isOptional();
                             void setChecked(boolean); boolean isChecked(); }
interface IRefactoringProblem { EObject getObject(); }
// підкласи проблем: EditingForbiddenProblem, DeletionForbiddenProblem, CleanReferenceProblem
```

### 1.2. Що робить createMdObjectRenameRefactoring (javap -c)

1. `doCreateMdObjectRenameRefactoring(object, newName)` — будує головний рефакторинг:
   - `MdObjectRenameOperation` (внутрішній клас, `IBmRefactoringOperation`): у транзакції
     сервісу робить `mdObject.setName(newName)`, оновлює синонім мови редагування
     (якщо синонім дорівнював старому імені) і для top-об'єктів —
     `transaction.updateTopObjectFqn(bmObject, newFqn)` (новий FQN через
     `ITopObjectFqnGenerator`; спецгілки для Table/Cube/DimensionTable/Subsystem).
   - `RefactoringTask(bmObject, назва операції)` → `coreService.initiateRename(...)`.
2. Далі для кожного проєкту-розширення шукає **заимствованный двійник**
   (`getAdoptedCounterpart`: читає об'єкт за URI через `executeReadonlyTask`) і додає
   окремий рефакторинг для нього → тому метод повертає `Collection<IRefactoring>`.

### 1.3. Що робить initiateRename (javap -c, RefactoringService)

1. `waitWorkspace`: зберігає dirty локальні BM-контексти (`IBmLocalEditingContext.save()`),
   `workspace.build(INCREMENTAL_BUILD)`, чекає derived data (`waitComputation(15000, ...)`).
   Жодного SWT/Display — **повністю headless**.
2. Створює `BmObjectRenameRefactoring`, викликає контрибутори (двічі: pre і post,
   `fillRefactoringWithParticipantsChanges`) і **`BmObjectVisitor`** з
   `BmObjectRenameVisitorCallback` — обхід зворотних посилань BM-індексу по всіх
   залежних моделях (`collectDependentModels`); для кожного посилання додається
   `CrossReferenceUpdateOperation` (оновлення посилання зводиться до повторної
   серіалізації референсів — FQN/ім'я в .mdo, формах тощо), а для об'єктів, які
   заборонено редагувати, — `EditingForbiddenProblem` у `getStatus()`.
3. `IRefactoring.perform()` (AbstractBmObjectRefactoring): batch session BM
   (`beginBatchSession`), блокування async derived-data pipeline, виконання всіх операцій
   BM-тасками (`BmTopObjectRefactoringTask`), завершення сесії. Знову без UI.

### 1.4. Контрибутори (оновлення посилань поза .mdo)

Extension point **`com._1c.g5.v8.dt.refactoring.core.refactoringContributors`**
(елементи `renameChangeContributor`/`deleteChangeContributor`). Читається через
`RefactoringContributorsRegistry` з Platform extension registry — працює з будь-якого
потоку, поки бандли встановлені (наш випадок: повний EDT). Зареєстровані рефакторинг-
контрибутори rename:

| Бандл | Контрибутор | Що оновлює |
|---|---|---|
| com._1c.g5.v8.dt.bsl.bm.ui | `ModuleRenameRefactoringContributor` | перенос/перейменування файлів модулів BSL |
| com._1c.g5.v8.dt.bsl.bm.ui | `BslConfigurationObjectRenameContributor` | **посилання в BSL-коді** (Справочники.X, ОбщегоМодуля.Метод тощо) |
| com._1c.g5.v8.dt.form | `FormDataPathRenameRefactoringContributor` | data path елементів форм |
| com._1c.g5.v8.dt.form.ui.dynamiclist | `DynamicListRenameRefactoringContributor` | запити динамічних списків |
| com._1c.g5.v8.dt.md.refactoring | `MdRenameRefactoringContributor` | загальна md-логіка |
| com._1c.g5.v8.dt.md.refactoring | `CommonModuleReferenceUpdater` | звернення до загальних модулів |
| com._1c.g5.v8.dt.md.refactoring | `DerivedFieldReferenceUpdater` | похідні поля (dbview/запити) |
| com._1c.g5.v8.dt.md.refactoring | `CharacteristicsDescriptionRenameRefactoringContributor` | характеристики |
| com._1c.g5.v8.dt.md.refactoring | `ExternalPropertyRenameRefactoringContributor` | зовнішні властивості (FQN-залежні) |
| com._1c.g5.v8.dt.right.ql.ui | `RightQlRenameRefactoringContributor` | RLS-запити прав |
| com._1c.g5.v8.dt.md.refactoring | `StyleItemRenameRefactoringContributor`, `WSDefinitionsRenameRefactoringContributor` | стилі, WS-визначення |

Примітка: «ui» в іменах бандлів не означає потребу в Display — контрибутори виконуються
в потоці виклику; MCP-сервер працює всередині живого workbench EDT, тож і залежності
цих бандлів доступні.

## 2. Реалізація в RenameOps.java

`renameObject(JsonObject arguments)`:

1. Параметри: `project?`, `kind`, `name`, `newName`, `tabularSection?`, `attribute?`,
   `dryRun?`, `force?`. Легка валідація `newName` (ідентифікатор).
2. Ціль читається `model.executeReadonlyTask(...)`: `getTopObjectByFqn(canonicalKind.name)`,
   далі опційно навігація в `tabularSections`/`attributes` за іменем. BM-хендл валідний
   після завершення readonly-таска — цей самий патерн використовує сам EDT
   (`MdRefactoringService.getAdoptedCounterpart`). Перевірка колізій: новий FQN
   не зайнятий (top) / немає сусіда з таким іменем (дочірній).
3. `EdtServices.require(IMdRefactoringService.class).createMdObjectRenameRefactoring(target, newName)`
   → план (`getItems()`) і проблеми (`getStatus().getProblems()`) у відповідь.
4. `dryRun:true` — план побудований, `perform()` не викликається.
   Проблеми без `force:true` — відмова з переліком (аналог діалогу в UI).
   Інакше — `perform()` для кожного рефакторингу (основний проєкт + розширення).

**ВАЖЛИВО**: сервіс сам відкриває BM-транзакції (batch session), тому виклик іде ДО
створення `AbstractBmTask` у `EditMetadataTool` — так само, як `adoptObject`.

### 2.1. Case-вставка в EditMetadataTool.execute (НЕ застосована — вставити при інтеграції)

Після блоку `adoptObject` (перед загальним `WriteGate.check();`):

```java
if ("renameObject".equals(operation)) { //$NON-NLS-1$
    // rename керує власними BM-транзакціями (штатний refactoring-сервіс EDT) —
    // викликається до створення нашої AbstractBmTask, як adoptObject
    WriteGate.check();
    return RenameOps.renameObject(arguments);
}
```

### 2.2. Зміни схеми (inputSchema)

- enum operation: додати `"renameObject"`;
- нові властивості:

```json
"newName":{"type":"string","description":"renameObject: нове ім'я об'єкта або дочірнього елемента"},
"force":{"type":"boolean","default":false,"description":"renameObject: виконати попри знайдені проблеми (об'єкти «на замку» тощо)"}
```

(`attribute`, `tabularSection`, `dryRun`, `project`, `kind`, `name` вже є в схемі.)

### 2.3. Вставка в help()

```json
"renameObject":{"params":"kind, name, newName [, tabularSection, attribute, project, dryRun, force]",
  "description":"Перейменовує об'єкт метаданих (або реквізит/ТЧ: attribute/tabularSection) штатним refactoring-сервісом EDT З ОНОВЛЕННЯМ ПОСИЛАНЬ: BSL-код, форми, права, похідні поля запитів. Заимствованные двійники в розширеннях перейменовуються автоматично. dryRun повертає план змін (plan) і проблеми (problems) без застосування.",
  "example":{"operation":"renameObject","kind":"Справочник","name":"Номенклатура","newName":"Товары","dryRun":true}}
```

### 2.4. MANIFEST.MF — Import-Package (додати при інтеграції)

```
com._1c.g5.v8.dt.refactoring.core;resolution:=optional,
com._1c.g5.v8.dt.md.refactoring.core;resolution:=optional
```

Обидва пакети експортуються своїми бандлами
(`Export-Package: com._1c.g5.v8.dt.refactoring.core;version="4.3.200"` і
`com._1c.g5.v8.dt.md.refactoring.core;version="1.0.700"`).
`com._1c.g5.v8.dt.metadata.mdclass` (MdObject) уже імпортується.

### 2.5. build.ps1 — classpath (додати при інтеграції)

```powershell
(Resolve-Jar "com._1c.g5.v8.dt.refactoring.core"),
(Resolve-Jar "com._1c.g5.v8.dt.md.refactoring")
```

Точні jar-и в EDT 2025.2.6:
- `com._1c.g5.v8.dt.refactoring.core_4.3.200.v202605050943.jar`
- `com._1c.g5.v8.dt.md.refactoring_1.0.700.v202605050943.jar`

Компіляція перевірена: `javac --release 17` усіх 58 .java проєкту + RenameOps.java
з еталонним classpath build.ps1 + два jar-и вище — OK (скрипт
`compile-check.ps1` у scratchpad сесії).

## 3. Обмеження

- **Рядкові літерали не оновлюються** (як і в UI-rename EDT): тексти запитів у
  BSL-коді (`Новый Запрос("ВЫБРАТЬ ... ИЗ Справочник.СтареІм'я")`), програмні
  звернення за іменем (`Метаданные.Справочники["..."]`), зовнішні звіти/обробки.
  Після rename — `code_search` за старим іменем + `get_validation_errors`.
- Об'єкти «на замку» постачання → `EditingForbiddenProblem` у `problems`;
  без `force:true` операція не виконується.
- `createMdObjectRenameRefactoring` приймає будь-який `MdObject` — включно з
  реквізитами і ТЧ (не-top гілка в `MdObjectRenameOperation` пропускає
  `updateTopObjectFqn`, лишаючи `setName` + контрибутори). Наша схема поки відкриває
  тільки `attribute`/`tabularSection`; виміри/ресурси регістрів можна додати
  аналогічною навігацією по колекціях `dimensions`/`resources`.
- Перейменування предвизначених елементів — окремий метод
  `createPredefinedItemRenameRefactoring` (не реалізовано, задокументовано).
- `initiateRename` перед побудовою плану робить `workspace.build()` і чекає derived
  data до 15 с — на великій конфігурації перший виклик може бути повільним.

## 4. План живого тесту (після інтеграції і build.ps1 + деплою)

1. `edit_metadata {"operation":"createObject","kind":"ОбщийМодуль","name":"МСР_ТестПеренос","properties":{"server":"true"}}`
2. `write_module_source` — записати в модуль процедуру, що викликає сам себе через
   інший тестовий модуль (щоб було посилання для оновлення), або простіше:
   створити другий модуль `МСР_ТестКлієнт` з кодом `МСР_ТестПеренос.Тест();`.
3. `edit_metadata {"operation":"renameObject","kind":"ОбщийМодуль","name":"МСР_ТестПеренос","newName":"МСР_ТестПеренос2","dryRun":true}` —
   у plan мають бути items і про сам rename, і про оновлення модуля-клієнта.
4. Повторити без dryRun → перевірити: `read_module_source` `МСР_ТестКлієнт`
   (виклик має стати `МСР_ТестПеренос2.Тест()`), тека модуля перейменована,
   `get_validation_errors` чистий.
5. Перевірка реквізита: `addAttribute` до тестового довідника → `renameObject`
   з `attribute` → перевірити data path у формі (якщо є) і .mdo.
6. Прибрати: `deleteObject` обох модулів.

## 5. Альтернатива (план Б — не знадобився)

Якби сервіс був UI-only: у BM-транзакції `mdObject.setName(newName)` +
`transaction.updateTopObjectFqn(bmObject, newFqn)` + оновлення синоніма — це рівно те,
що робить `MdObjectRenameOperation`, але БЕЗ обходу посилань і контрибуторів →
посилання лишаються старими. Рівень A працює headless, тому план Б відхилено.
