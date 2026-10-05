# find_references / get_call_hierarchy — семантичний пошук по BSL через Xtext-індекс EDT

Дослідження для інструментів `find_references` і `get_call_hierarchy`
(EDT 2025.2.6, jar-и з `C:\Program Files\1C\1CE\components\1c-edt-2025.2.6+4-x86_64\plugins`).
Усі сигнатури зняті через `javap` (включно з `javap -c` для дизасемблювання
логіки EDT) — не з пам'яті.

## Ключові знахідки

### 1. Індекс = builder state, доступний через спільний інжектор Xtext

Xtext-індекс воркспейсу (`IBuilderState extends IResourceDescriptions`)
живе у **спільному (shared) інжекторі** бандла `org.eclipse.xtext.ui.shared`
і дістається статично, без UI і без editor:

```java
com.google.inject.Provider<IResourceDescriptions> p =
        org.eclipse.xtext.ui.shared.Access.getIResourceDescriptions();
IResourceDescriptions index = p.get();   // читається з будь-якого потоку
```

`javap org.eclipse.xtext.ui.shared.Access` (jar `org.eclipse.xtext.ui.shared_2.33.0`):

```
static Provider<IResourceDescriptions> getIResourceDescriptions();
static Provider<org.eclipse.xtext.builder.builderState.IBuilderState> getIBuilderState();
```

`IBuilderState` (jar `org.eclipse.xtext.builder_2.33.0`) — це той самий об'єкт,
розширений операціями збірки; для читання достатньо `IResourceDescriptions`:

```
interface IBuilderState extends IResourceDescriptions, IResourceDescription$Event$Source
interface IResourceDescriptions { Iterable<IResourceDescription> getAllResourceDescriptions();
                                  IResourceDescription getResourceDescription(URI); }
```

### 2. Пошук посилань — org.eclipse.xtext.findReferences (runtime, не UI)

Пакет `org.eclipse.xtext.findReferences` лежить у **runtime-jar
`org.eclipse.xtext_2.33.0`** (саме він був в Import-Package комерційного
аналога). Інтерфейси анотовані `@ImplementedBy`, тому інжектор мови BSL
віддає їх без явних біндінгів:

```
@ImplementedBy(ReferenceFinder)  interface IReferenceFinder {
  void findAllReferences(TargetURIs, IReferenceFinder$IResourceAccess,
                         IResourceDescriptions, IReferenceFinder$Acceptor, IProgressMonitor);
  // + findReferences(TargetURIs, IResourceDescription, ...) та інші перевантаження
}
interface IReferenceFinder$Acceptor {
  void accept(EObject source, URI sourceURI, EReference, int index, EObject target, URI targetURI); // локальні
  void accept(IReferenceDescription);                                                              // з індексу
}
interface IReferenceFinder$IResourceAccess {
  <R> R readOnly(URI targetURI, IUnitOfWork<R, ResourceSet> work);
}
@ImplementedBy(TargetURISet)  interface TargetURIs { void addURI(URI); ... }
class TargetURIConverter { TargetURIs fromIterable(Iterable<URI>); }
interface IReferenceDescription { URI getSourceEObjectUri(); URI getTargetEObjectUri();
                                  EReference getEReference(); URI getContainerEObjectURI(); }
```

Дизасемблювання `ReferenceFinder.findAllReferences` показало механіку:
ітерується **весь індекс** (`getAllResourceDescriptions()`), для кожного
ресурсу береться мовний фіндер (`getLanguageSpecificReferenceFinder`), збіги
з індексу приходять у `accept(IReferenceDescription)` (тільки URI, без
рядків), а для ресурсів, що містять самі цілі, через `IResourceAccess`
шукаються й локальні посилання (`accept(EObject, ...)`).

Сервіси мови BSL — з реєстру за розширенням, як у `ValidateQueryTool`:

```java
IResourceServiceProvider bsl = IResourceServiceProvider.Registry.INSTANCE
        .getResourceServiceProvider(URI.createURI("__mcp_refs__.bsl"));
IReferenceFinder finder = bsl.get(IReferenceFinder.class);
TargetURIs targets = bsl.get(TargetURIConverter.class).fromIterable(List.of(uri));
```

### 3. Що класти в TargetURIs (дизасемблювання BslReferenceQueryExecutor)

`javap -c com._1c.g5.v8.dt.bsl.ui.editor.findref.BslReferenceQueryExecutor`
(jar `com._1c.g5.v8.dt.bsl.ui_22.0.0`) — штатний Find References EDT:

- якщо вибрано **використання** (`DynamicFeatureAccess`, напр.
  `ОбщегоНазначения.Метод` під курсором):
  `DynamicFeatureAccessComputer.getLastObject(dfa, environmental.environments(), true)`
  → останній `FeatureEntry.getFeature()` → якщо це `SourceObjectLinkProvider`
  (похідний mcore-Method із ContextDef модуля), ціль = **`getSourceUri()`** —
  URI *вихідного* `bsl.model.Method`;
- якщо вибрано **оголошення** (`bsl.model.Method` з outline) — дефолтний
  шлях `super.getTargetURIs(EObject)` = `EcoreUtil.getURI(method)`.

Висновок: в індексі посилання на метод BSL зберігаються з target-URI
**вихідного методу** (`platform:/resource/<проєкт>/src/.../Module.bsl#<фрагмент>`),
тому достатньо `TargetURIs = { EcoreUtil.getURI(bslMethod) }` — повний
паритет зі штатним пошуком EDT без залежності від UI-класів.

### 4. Ієрархія викликів: штатна — тільки UI, робимо власну на тих самих примітивах

`com._1c.g5.v8.dt.bsl.ui.editor.callhierarchy.CallHierarchyExecutor/CallHierarchyQuery`
зав'язані на SWT/SearchUI/editor (`EditorResourceAccess`,
`NewSearchUI.runQueryInBackground`) — headless непридатні. Але примітиви ті самі:

- **incoming** = find references на метод, згруповані за методом-контейнером
  (`EcoreUtil2.getContainerOfType(sourceEObject, Method.class)`);
- **outgoing** = обхід AST методу: `EcoreUtil2.getAllContentsOfType(method,
  Invocation.class)`; ціль виклику:
  - `StaticFeatureAccess` → метод свого модуля (`Module.allMethods()` за
    ім'ям) або глобальна функція платформи;
  - `DynamicFeatureAccess` → `DynamicFeatureAccessComputer.getLastObject(...)`
    → `FeatureEntry.getFeature()`:
    `SourceObjectLinkProvider.getSourceUri()` = метод іншого модуля,
    `mcore.Method/Property` без source-URI = метод платформи.

Сигнатури BSL-моделі (jar `com._1c.g5.v8.dt.bsl.model_12.0.0`):

```
interface Module extends Block { EList<Method> allMethods(); EObject getOwner();
                                 ModuleType getModuleType(); }
interface Method extends PragmaTarget, NamedElement, Block { boolean isExport();
                                 EList<Block> getCallers(); EList<Method> getCallees(); }
interface Invocation extends Expression { FeatureAccess getMethodAccess();
                                 EList<Expression> getParams(); }
interface StaticFeatureAccess extends FeatureAccess { EList<FeatureEntry> getFeatureEntries(); }
interface DynamicFeatureAccess extends FeatureAccess { Expression getSource();
                                 EList<FeatureEntry> getFeatureEntries(); }
interface FeatureEntry { EObject getFeature(); Environments getEnvironments(); }
interface SourceObjectLinkProvider { URI getSourceUri(); }
```

`DynamicFeatureAccessComputer` (jar `com._1c.g5.v8.dt.bsl_28.0.1`, пакет
`com._1c.g5.v8.dt.bsl.resource` — експортований):

```
List<FeatureEntry> getLastObject(DynamicFeatureAccess, Environments, boolean);
```

`Method.getCallers()/getCallees()` виглядають спокусливо, але це похідний
стан typesystem конкретного завантаженого ресурсу, а не персистентний
індекс — на них не покладаємось.

### 5. Позиції в коді

`IReferenceDescription` рядків не містить. Рядок обчислюється з вузлової
моделі завантаженого ресурсу:

```java
EObject src = resource.getEObject(sourceUri.fragment());
ICompositeNode node = NodeModelUtils.findActualNodeFor(src);  // org.eclipse.xtext.nodemodel.util
int line = node.getStartLine();                               // 1-based
```

## Реалізація

- `src\com\polischuk\edt\prl\tools\code\BslReferences.java` — спільна
  інфраструктура: провайдер BSL, індекс (Access → fallback біндінг мови),
  `ResourceAccess` (реалізація `IReferenceFinder$IResourceAccess`:
  ResourceSet на проєкт через `IResourceSetProvider`, як у ValidateQueryTool),
  завантаження модуля, пошук джерел посилань, розв'язання цілі виклику,
  позиції/сніпети.
- `src\com\polischuk\edt\prl\tools\code\FindReferencesTool.java` —
  `find_references`: `path`+`method` (метод модуля) або `kind`+`name`
  (об'єкт метаданих, експериментально). Результат:
  `{target, totalReferences, returned, references:[{project, path, line, method, snippet}]}`.
  Внутрішньомодульні виклики **не зберігаються в індексі**, тому додатково
  модуль-власник сканується по AST (`Invocation` + `StaticFeatureAccess`
  за ім'ям), з дедуплікацією за (path, line).
- `src\com\polischuk\edt\prl\tools\code\GetCallHierarchyTool.java` —
  `get_call_hierarchy`: `direction=incoming|outgoing`, `depth` 1..3,
  `maxNodes` (ліміт вузлів, `truncated:true` при зрізанні), захист від
  циклів (`recursion:true`). incoming групує місця викликів за
  методом-викликачем; outgoing класифікує цілі:
  `local` / `module` (+path) / `platform` / `global` / `unresolved`.

Окремий go-to-definition не робив: для методу він тривіально покривається
`read_method_source`; перспективний напрямок для «дефініції під курсором» —
`com._1c.g5.v8.dt.bsl.resource.BslEObjectAtOffsetHelper` (є в експортованому
пакеті) + той самий `SourceObjectLinkProvider.getSourceUri()`.

## Jar-и для компіляції (додатково до списку build.ps1)

```
org.eclipse.xtext.ui.shared_2.33.0.v20231121-0955.jar     (Access)
com._1c.g5.v8.dt.bsl_28.0.1.v202605050943.jar             (DynamicFeatureAccessComputer)
com._1c.g5.v8.dt.bsl.model_12.0.0.v202605050943.jar       (Module, Method, Invocation, ...)
com.google.guava_*.jar                                    (TargetURIs extends Predicate)
com.google.inject_*.jar                                   (Provider з Access)
jakarta.inject.jakarta.inject-api_2.0.1.jar               (лише compile: супертип Guice Provider)
```

Увага: чинний build.ps1 застарів відносно поточних вихідників — для
LaunchDebuggerTool/ExtensionOps при повній збірці також потрібні
`org.eclipse.debug.core`, `com._1c.g5.v8.dt.debug.core_18.*`,
`com._1c.g5.v8.dt.md`, `com._1c.g5.v8.dt.md.extension_4.*` (перевірено
компіляцією всіх 48 файлів у темп-папку).

## Import-Package для MANIFEST.MF (усі resolution:=optional, у стилі проєкту)

```
org.eclipse.xtext;resolution:=optional,                    (EcoreUtil2)
org.eclipse.xtext.findReferences;resolution:=optional,
org.eclipse.xtext.nodemodel.util;resolution:=optional,     (NodeModelUtils)
org.eclipse.xtext.util.concurrent;resolution:=optional,    (IUnitOfWork)
org.eclipse.xtext.ui.shared;resolution:=optional,          (Access)
com._1c.g5.v8.dt.bsl.model;resolution:=optional,
com._1c.g5.v8.dt.bsl.resource;resolution:=optional,
com._1c.g5.v8.dt.mcore.util;resolution:=optional,          (Environments у сигнатурах)
com.google.inject;resolution:=optional
```

Вже наявні й потрібні: `org.eclipse.xtext.resource`, `org.eclipse.xtext.nodemodel`,
`org.eclipse.xtext.ui.resource`, `org.eclipse.xtext.util`, `com._1c.g5.v8.dt.mcore`,
`org.eclipse.emf.*`. Реєстрацію в ToolRegistry і MANIFEST не чіпав — за
умовами задачі це крок інтеграції.

## Обмеження

1. **Покриття = Xtext-індекс BSL.** Посилання з форм (.form), текстів
   запитів у СКД/макетах, прав, підписок на події тощо не знаходяться —
   штатний UI-пошук EDT добирає їх окремими учасниками (extension point
   `referenceFinderParticipant` у bsl.ui, UI-контракти). Динамічні виклики
   рядком (`Выполнить`, `ОбщегоНазначения.ВыполнитьМетодНаСервере("М.М")`)
   не знаходяться принципово.
2. **kind+name — експериментально:** ціль = `EcoreUtil.getURI` об'єкта
   конфігурації (BM-URI); знайдеться те, що індекс BSL зберігає з таким
   target-URI. Якщо посилання в індексі записані на похідні об'єкти з
   іншими URI — буде 0 збігів (перевірити на живому EDT, див. тести).
3. **Продуктивність:** `findAllReferences` ітерує весь індекс воркспейсу;
   на конфігурації масштабу retail це секунди (так само працює штатний
   пошук EDT). Розв'язання рядків завантажує модулі-джерела — перший виклик
   повільніший, ResourceSet-и кешуються лише в межах одного виклику.
4. **outgoing + DynamicFeatureAccess** потребує обчислення типів
   (TypesComputer) — на складних виразах можливий `unresolved`; помилки
   обчислення гасяться до `unresolved`, інструмент не падає.
5. **Потоки:** індекс читається з будь-якого потоку; завантаження ресурсів —
   у власних ResourceSet-ах проєкту (той самий патерн, що ValidateQueryTool,
   перевірений на HTTP-потоці сервера). Write-операцій немає.

## План тестів (після інтеграції і деплою)

1. `find_references {path: "src/CommonModules/ОбщегоНазначенияКлиентСервер/Module.bsl",
   method: "СообщитьПользователю"}` — очікування: сотні збігів по всій
   конфігурації retail; звірити 2-3 позиції з `code_search
   "СообщитьПользователю("` (семантика має не включати збіги в коментарях
   і рядках, але включати всі реальні виклики).
2. Метод, що викликається у своєму ж модулі — локальні збіги присутні
   (AST-скан), без дублікатів з індексними.
3. Неекспортний метод — тільки локальні збіги.
4. `get_call_hierarchy {..., direction: "incoming", depth: 2}` — групування
   за викликачами, рекурсія рівня 2, `recursion:true` на взаємній рекурсії;
   ліміт `maxNodes` → `truncated:true`.
5. `get_call_hierarchy {..., direction: "outgoing"}` на методі з локальними,
   міжмодульними і платформенними викликами — перевірити kind кожного вузла
   і path у `module`-вузлів.
6. `find_references {kind: "Catalog", name: "..."}` — оцінити практичну
   цінність експериментального режиму; якщо 0 збігів при явних згадках у
   BSL — дослідити реальні target-URI через дамп IReferenceDescription
   (тимчасовий діагностичний вивід).
7. Продуктивність: заміряти перший/повторний виклик на retail (очікування:
   до ~10 с перший, швидше повторний у межах процесу EDT).
