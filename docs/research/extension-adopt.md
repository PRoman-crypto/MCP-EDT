# Розширення конфігурації (CFE): заимствование об'єктів — розвідка й дизайн

Дата: 2026-08-26. EDT 2025.2.6 (`C:\Program Files\1C\1CE\components\1c-edt-2025.2.6+4-x86_64`).
Метод: тільки `javap` (Zulu 17) і `jar -tf` по jar-ах EDT — сигнатури нижче зняті з байткоду, не вигадані.

## 1. Головна знахідка: готовий сервіс `IModelObjectAdopter`

Бандл **`com._1c.g5.v8.dt.md.extension_4.2.0.v202605050943.jar`** експортує публічний пакет
`com._1c.g5.v8.dt.md.extension.adopt` (Export-Package version **2.1.0**, `x-provider-type:=IModelObjectAdopter`).

```
public interface com._1c.g5.v8.dt.md.extension.adopt.IModelObjectAdopter {
  <T extends EObject> T adopt(T, com._1c.g5.v8.dt.platform.version.Version, IProgressMonitor);
  <T extends EObject> T adoptAndAttach(T, IExtensionProject, IProgressMonitor) throws CoreException;
  List<EObject> adoptAndAttach(List<EObject>, IExtensionProject, IProgressMonitor) throws CoreException;
  <T extends EObject> T getAdopted(T, IExtensionProject);   // уже заимствованный аналог або null
  <T extends EObject> T getSource(T);                        // зворотний зв'язок: adopted → базовий
  boolean isAdopted(EObject, IExtensionProject);
  boolean isUpdatable(EObject, IExtensionProject);
  <T extends EObject> T updateAdopted(T, IExtensionProject, IProgressMonitor) throws CoreException; // "Обновить заимствованный"
  boolean isAdoptable(EObject);
}
```

**Реєстрація як OSGi-сервіс підтверджена** дизасемблюванням активатора
`com._1c.g5.v8.dt.internal.md.extension.MdExtensionPlugin.start()`: через
`InjectorAwareServiceRegistrator` реєструються `IModelObjectAdopter`, `IMdPropertyTypeProvider`,
`IMdAdoptedPropertyAccess`, `IMdAdoptedPropertyNotifier`, `ITypeDescriptionAdoptSupport`.
Отже наш штатний `EdtServices.require(IModelObjectAdopter.class)` працює (бандл lazy —
активується під час завантаження класу інтерфейсу, як і решта сервісів EDT, що ми вживаємо).

Імплементація: `com._1c.g5.v8.dt.internal.md.extension.adopt.ModelObjectAdopter` +
~70 участників `...internal.md.extension.adopt.participants.*AdopterParticipant`
(Catalog, Document, BslModule, BasicForm, Field, Column, PredefinedItem, TypeDescriptionAdoptSupport тощо) —
тобто сервіс сам знає, які властивості/дочірні елементи переносити для кожного виду метаданих.
Fallback — `MdObjectAdopterParticipant<MdObject>`.

### 1.1 КРИТИЧНО: `adoptAndAttach` сам керує транзакцією

Дизасемблювання `ModelObjectAdopter.adoptAndAttach(EObject, IExtensionProject, IProgressMonitor)`:

1. `Preconditions.checkNotNull`, `isAdoptable` (інакше `IllegalArgumentException "Source object %s cannot be adopted"`);
2. `IBmModel model = extensionProject.getAdapter(IBmModel.class)` → `model.getGlobalContext()`;
3. створює власний `AbstractBmTask` (`ModelObjectAdopter$2`), усередині якого викликає
   приватний `adoptAndAttach(..., IBmTransaction, CachingAdopterContext)`;
4. `IDerivedDataManagerProvider.get(model).disableImplicitWaiting()` →
   `ResourcesPlugin.getWorkspace().run(runnable, root, ...)` з `globalContext.execute(task)` усередині →
   `enableImplicitWaiting()` у finally.

Висновок: **виклик має йти ПОЗА нашою BM-транзакцією** (не всередині `AbstractBmTask`
із `EditMetadataTool`), і його не можна пропустити через `model.executeAndRollback` для dryRun.
Тому dryRun для adopt — це повна валідація входів без застосування (див. §4). Приватний
`getParent`/`addChild` у ModelObjectAdopter показує, що заимствование дочірнього елемента
(реквізит, ТЧ) автоматично заимствует ланцюжок батьків.

`adopt(T, Version, monitor)` — «чиста» побудова копії без attach (потребує `Version`
платформи; конвертер CompatibilityMode→Version у публічному API не знайдено, тому в dryRun не задіяний).

## 2. Модель розширення

### 2.1 `IExtensionProject` (бандл `com._1c.g5.v8.dt.core_26.0.1`)

```
public interface IExtensionProject extends IDependentProject, IConfigurationAware {
  Configuration getConfiguration();                       // власна Configuration розширення
  CompatibilityMode getConfigurationExtensionCompatibilityMode();
}
public interface IDependentProject extends IV8Project {
  IConfigurationProject getParent();                      // базова конфігурація
  IProject getParentProject();
}
```

Пошук усіх розширень: `IV8ProjectManager.getProjects(IExtensionProject.class)`
(сигнатура `<T extends IV8Project> Collection<T> getProjects(Class<T>)` — знята javap-ом).

### 2.2 Ознаки заимствованного об'єкта (`com._1c.g5.v8.dt.metadata_18.0.100`, пакет mdclass)

`MdObject` (кожен top-об'єкт і дочірній елемент):

```
ObjectBelonging getObjectBelonging();        // enum NATIVE("Native") | ADOPTED("Adopted")
UUID getExtendedConfigurationObject();       // uuid ВІДПОВІДНОГО об'єкта базової конфігурації
ObjectExtension getExtension();              // властивості розширення (стани властивостей)
```

`ObjectBelonging` — літерали з байткоду: `Native`, `Adopted` (порівнюємо строково, щоб не
тягнути жорстку залежність — у стилі проєкту "через EStructuralFeature").
Типізовані `*Extension`-класи (CatalogExtension, InformationRegisterExtension, …) і
`MdPropertyState`/`MdPropertyType` живуть у `com._1c.g5.v8.dt.metadata.extension_5.0.0.jar`
(пакети `mdclass.extension`, `mdclass.extension.type`) — для нашої реалізації напряму не потрібні:
їх заповнюють adopter-participants.

У .mdo розширення це видно як `objectBelonging="Adopted"` + `extendedConfigurationObject="<uuid>"` —
сервіс ставить їх сам, включно з uuid-ами стандартних реквізитів.

## 3. Що реалізовано: `ExtensionOps.java`

`<repo>\src\com\polischuk\edt\prl\tools\write\ExtensionOps.java` — статичні операції:

- **`adoptObject(JsonObject arguments)`** — параметри: `project` (проєкт РОЗШИРЕННЯ; можна
  опустити, якщо розширення одне), `kind`+`name` (об'єкт базової конфігурації, kind з
  псевдонімами KindRegistry через `MetadataIndex.findObject`), опційно `tabularSection`/`attribute`
  (заимствовать конкретний дочірній елемент — батьки заимствуются автоматично), `dryRun`.
  Потік: резолв розширення → `getParent().getConfiguration()` → пошук вихідного об'єкта →
  `getAdopted` (ідемпотентність: `alreadyAdopted:true`, не помилка) → `isAdoptable` →
  dryRun-відповідь або `adoptAndAttach(target, ext, new NullProgressMonitor())`.
- **`listAdoptedObjects(String projectName)`** — обхід containment-колекцій Configuration
  розширення, фільтр `objectBelonging == "Adopted"` (рефлексивно через `Emf.str`); повертає
  також власні (Native) об'єкти, `namePrefix`, `purpose`.
- **`resolveExtensionProject(String name)`** — чемні повідомлення для всіх випадків:
  розширень немає взагалі (підказка створити CFE-проєкт у EDT), кілька без параметра,
  ім'я вказує на не-розширення, ім'я не знайдено.

Обидві операції ловлять стани "конфігурація ще не завантажена". `CoreException` з
`adoptAndAttach` загортається в `IllegalStateException` з повідомленням.

## 4. Інтеграція в `EditMetadataTool` (точні вставки)

Операції adopt НЕ входять у спільний `AbstractBmTask` (див. §1.1), тому вставка — на початку
`execute`, ПЕРЕД резолвом проєкту/моделі. `listAdopted` — читання, його можна пустити до
`WriteGate.check()`; `adoptObject` — запис, після check. Точний код (вставити одразу після
блоку `if ("help".equals(operation)) {...}` і ПЕРЕД існуючим `WriteGate.check();`):

```java
if ("listAdopted".equals(operation)) { //$NON-NLS-1$
    return ExtensionOps.listAdoptedObjects(
            arguments.has("project") ? arguments.get("project").getAsString() : null); //$NON-NLS-1$ //$NON-NLS-2$
}
if ("adoptObject".equals(operation)) { //$NON-NLS-1$
    WriteGate.check();
    return ExtensionOps.adoptObject(arguments);
}
```

В `inputSchema()` до enum операцій додати `"adoptObject","listAdopted"`, а до properties:

```json
"tabularSection/attribute (adoptObject)": "необов'язково — заимствовать конкретний дочірній елемент",
"project (adoptObject/listAdopted)": "ім'я проєкту РОЗШИРЕННЯ (не базової конфігурації)"
```

(практично: досить розширити enum; `project`, `kind`, `name`, `attribute`, `tabularSection`,
`dryRun` уже описані в схемі — семантика `project` для цих двох операцій = проєкт розширення).

У `help()` додати:

```json
"adoptObject":{"params":"kind, name [, project=розширення, tabularSection, attribute, dryRun]",
  "description":"Заимствование об'єкта базової конфігурації в розширення (CFE): створює Adopted-копію з extendedConfigurationObject через штатний IModelObjectAdopter. Далі: addAttribute для нового реквізита, write_module_source для &Вместо/&После.",
  "example":{"operation":"adoptObject","kind":"Справочник","name":"Номенклатура","dryRun":true}},
"listAdopted":{"params":"[project=розширення]",
  "description":"Список заимствованных (Adopted) і власних (Native) top-об'єктів проєкту розширення."}
```

## 5. Збірка: classpath і MANIFEST

- **build.ps1**: додати в `$cp` рядок
  `((Get-ChildItem $pluginsDir -Filter "com._1c.g5.v8.dt.md.extension_4*.jar" | Select-Object -First 1).FullName)`.
  УВАГА: `Resolve-Jar "com._1c.g5.v8.dt.md.extension"` НЕ підходить — маска `_*.jar` зачепить
  версійні бандли `..._v8.3.27_...`/`..._v8.5.1_...`, і сортування за іменем вибере `v8.5.1` (там
  зовсім інші класи). Фільтр `_4*.jar` фіксує основний бандл (4.2.0).
- **META-INF/MANIFEST.MF**: до `Import-Package` додати
  `com._1c.g5.v8.dt.md.extension.adopt;resolution:=optional`
  (у стилі решти EDT-пакетів манифеста; версійний діапазон за бажанням: `version="[2.1.0,3.0.0)"`).
  `com._1c.g5.v8.dt.core.platform` (IExtensionProject, IConfigurationProject) уже імпортовано.
- Компіляція всього src + ExtensionOps перевірена: `javac --release 17` → exit 0
  (у темп; жоден існуючий файл не змінено). Примітка: у поточному src з'явився
  `tools/info/ListApplicationsTool.java` (паралельна сесія), якому потрібен бандл
  `com.e1c.g5.dt.applications` — без нього javac падає ще ДО ExtensionOps; у перевірковій
  компіляції він доданий у classpath. build.ps1 на диску його поки не містить.

## 6. Обмеження

1. **dryRun не «прокатує» саму транзакцію**: adoptAndAttach керує транзакцією сам (§1.1),
   тож dryRun обмежений перевірками (проєкт розширення, існування вихідного об'єкта,
   isAdoptable, чи вже заимствован). Це слабша гарантія, ніж executeAndRollback у решті операцій.
2. `adoptAndAttach` виконує `IWorkspace.run` з root-правилом — під час активної фонової збірки
   workspace виклик чекатиме; це нормально, але може виглядати як «зависання» довгого запиту.
3. Заимствование дочірніх елементів підтримано для `attributes`/`tabularSections`; форми,
   команди, макети як окремі цілі не реалізовані (adopt top-об'єкта їх не тягне — так само
   поводиться і UI EDT: заимствование мінімальне).
4. `updateAdopted` («Обновить заимствованный об'єкт») і масовий `adoptAndAttach(List, ...)`
   сервіс підтримує — у ExtensionOps поки не виведені (легко додати окремими операціями).
5. Перехоплення методів (&Вместо/&После/&Перед) — рівень BSL: після adoptObject модуль
   заимствованного об'єкта редагується наявним write_module_source; окремий код не потрібен.
6. У workspace зараз лише базова конфігурація retail — реальний adopt не проганявся;
   поведінка підтверджена лише статично (байткод + сигнатури). Помилка «немає проєкту
   розширення» повертається чемно з інструкцією.

## 7. План тестів (коли з'явиться CFE-проєкт)

1. У EDT: File → New → Project → «Розширення конфігурації» (базова — retail), напр. `retail_ext`.
2. `edit_metadata {"operation":"listAdopted"}` → порожні adopted/own, namePrefix розширення.
3. `edit_metadata {"operation":"adoptObject","kind":"Справочник","name":"Номенклатура","dryRun":true}`
   → `adoptable:true, applied:false`.
4. Те саме без dryRun → `applied:true`, у відповіді uuid + extendedConfigurationObject.
5. Перевірити файл `retail_ext/src/Catalogs/Номенклатура/Номенклатура.mdo`:
   `objectBelonging="Adopted"`, `extendedConfigurationObject` = uuid об'єкта в retail
   (звірити з `get_object_details`), стандартні реквізити з uuid-ами базових.
6. Повторний adoptObject → `alreadyAdopted:true` (ідемпотентність, без помилки).
7. `adoptObject` з `attribute:"Артикул"` → заимствован реквізит + (авто) батько.
8. `listAdopted` → об'єкт у списку adopted.
9. Негативні: неіснуючий kind/name; `project` = retail (не розширення); adopt без
   write-flag (`WriteGate`); workspace без розширень.
10. Далі E2E: `addAttribute` до заимствованного (реквізит розширення), `write_module_source`
    з `&После("...")` — і збірка/завантаження CFE в базу для перевірки платформою.
