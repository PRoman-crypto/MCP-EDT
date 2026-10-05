# launch_debugger — керування відладчиком 1С з MCP

Дослідження API EDT 2025.2.6 (`C:\Program Files\1C\1CE\components\1c-edt-2025.2.6+4-x86_64\plugins`).
Метод: javap + jar -tf, сигнатури нижче зняті з байткоду — не вигадані.

## Висновок (TL;DR)

Все потрібне API існує і **експортоване** (не internal): EDT будує відладку 1С поверх
стандартної моделі Eclipse Debug (`org.eclipse.debug.core`), а BSL-специфіка живе в
експортованих пакетах бандла `com._1c.g5.v8.dt.debug.core` (18.0.0). Debug-target EDT
реєструється у стандартному `ILaunchManager`, тому:

- **listTargets, getState (стек), stepOver/stepInto/stepReturn, resume, pause, terminate** —
  чистий публічний Eclipse API, працює універсально;
- **setBreakpoint/removeBreakpoint/listBreakpoints** — через експортований
  `IBslBreakpointFactory` + стандартний `IBreakpointManager`;
- **getVariables** — `IBslStackFrame.getVariables()` (ліниві значення: спершу
  `IBslVariable.evaluate()` або `IEvaluationEngine.evaluateVariables(...)`);
- **evaluate** — `IRuntimeDebugClientTarget.getEvaluationEngine()` +
  `EvaluationRequest.builder(new BslValuePath(expr))` + `EvaluationJob`; асинхронний,
  результат приходить у `IEvaluationListener`;
- **launch** (запуск 1С:Підприємства з відладкою) — стандартний
  `ILaunchConfigurationType.newInstance(...)` з type id
  `com._1c.g5.v8.dt.debug.core.RemoteRuntime` і атрибутами з `IDebugConfigurationAttributes`,
  або простіше — переиспользування збереженої конфігурації користувача
  (`ILaunchManager.getLaunchConfigurations(type)` → `config.launch("debug", monitor)`).

## Бандли та ідентифікатори

| Що | Значення |
|---|---|
| Debug model id (BSL) | `com._1c.g5.v8.dt.debug` (`IDebugConstants.ID_BSL_DEBUG_MODEL`) |
| Marker type базовий | `com._1c.g5.v8.dt.debug.core.bslBreakpointMarker` (`IBslBreakpoint.MARKER_TYPE`) |
| Marker type line-breakpoint | `com._1c.g5.v8.dt.debug.core.bslLineBreakpointMarker` (persistent, super: базовий + `org.eclipse.debug.core.lineBreakpointMarker`) |
| Breakpoint class (internal) | `com._1c.g5.v8.dt.internal.debug.core.model.breakpoints.BslLineBreakpoint` |
| Launch config type (основний) | `com._1c.g5.v8.dt.debug.core.RemoteRuntime` (`IDebugConfigurationTypes.REMOTE_RUNTIME`), modes="debug" |
| Launch config type (локальний, public=false) | `com._1c.g5.v8.dt.debug.core.LocalRuntime` |
| Бандл ядра відладки | `com._1c.g5.v8.dt.debug.core_18.0.0` |
| EMF-модель протоколу dbgs | `com._1c.g5.v8.dt.debug.model_3.9.100` (пакети `...debug.model.base.data`, `...debug.model.calculations`) |
| Застосунки | `com.e1c.g5.dt.applications_6.0.0`, `com.e1c.g5.dt.applications.infobases_2.0.500` |
| Сумісність зі старими платформами | `com._1c.g5.v8.dt.debug.core_v8.3.10`, `_v8.3.15` (окремі бандли, нас не стосуються) |

Export-Package `com._1c.g5.v8.dt.debug.core` (усі публічні): `...debug.core`, `...debug.core.commands`,
`...debug.core.launchconfigurations`, `...debug.core.model`, `...debug.core.model.breakpoints`,
`...debug.core.model.evaluation`, `...debug.core.model.values`, `...debug.core.runtime.version`,
`...debug.util`.

Бандл оголошений як `com._1c.g5.wiring.serviceProvider` і в активаторі має
`InjectorAwareServiceRegistrator` — тобто сервіси (як мінімум ті, що позначені
`x-provider-type` в Export-Package: `IBslStackFrame, IBslModuleLocator,
IRuntimeDebugTargetThread, IRuntimeDebugClientTarget, IBslLineBreakpoint`) створюються Guice.
**Які саме інтерфейси реально опубліковані в OSGi-реєстрі — перевіряється лише в runtime**
(через `EdtServices.describeAvailability`). Кандидати на публікацію:
`IBslBreakpointFactory`, `IRuntimeDebugClientTargetManager`, `IBslBreakpointListenerManager`.
`IApplicationManager` (bundle applications) — точно опублікований: вже використовується
робочим інструментом `list_applications`.

## Карта API (сигнатури з javap)

### Launch config атрибути — `com._1c.g5.v8.dt.debug.core.IDebugConfigurationAttributes`

```
PROJECT_NAME  = "com._1c.g5.v8.dt.debug.core.ATTR_PROJECT_NAME"
APPLICATION_ID= "com._1c.g5.v8.dt.debug.core.ATTR_APPLICATION_ID"
INFOBASE_UUID = "com._1c.g5.v8.dt.debug.core.ATTR_INFOBASE_UUID"
RUNTIME_INSTALLATION            = "...ATTR_RUNTIME_INSTALLATION"
RUNTIME_INSTALLATION_USE_AUTO   = "...ATTR_RUNTIME_INSTALLATION_USE_AUTO"
DEBUG_SERVER_URL / _PORT / _USERNAME / _PASSWORD / DEBUG_INFOBASE_ALIAS
DEBUG_SERVER_LOCATION_TYPE = "...ATTR_USE_LOCAL_DEBUG_SERVER"
DEBUG_SERVER_LAUNCH / DEBUG_KEEP_SERVER_LAUNCH
EXTERNAL_OBJECT_PROJECT_NAME / _TYPE / _NAME
```

Помічник: `LaunchApplicationProvider.getApplication(ILaunchConfiguration, boolean)` →
`Optional<IApplication>` — мапить конфігурацію на застосунок.

### Точки останову — пакет `...debug.core.model.breakpoints`

```java
interface IBslBreakpointFactory {
    IBslLineBreakpoint  createLineBreakpoint(IResource, int line) throws CoreException;
    IBslRunToLineBreakpoint createRunToLineBreakpoint(IResource, int) throws CoreException;
    IBslExceptionBreakpoint createExceptionBreakpoint() throws CoreException;      // всі винятки
    IBslExceptionBreakpoint createExceptionBreakpoint(String) throws CoreException;// за підрядком
}
interface IBslLineBreakpoint extends IBslBreakpoint, ILineBreakpoint {
    // + умова (getCondition/setCondition), hit count, expression-to-evaluate,
    // continueExecution (= "точка з продовженням", аналог logpoint) тощо
}
```

Фабрика створює breakpoint і маркер; реєстрація — стандартна:
`DebugPlugin.getDefault().getBreakpointManager().addBreakpoint(bp)`.
Перелік/видалення: `IBreakpointManager.getBreakpoints("com._1c.g5.v8.dt.debug")`,
`removeBreakpoint(bp, true)`. Номер рядка — 1-based (стандарт Eclipse `IMarker.LINE_NUMBER`).
Валідація позиції: `com._1c.g5.v8.dt.debug.util.BreakpointLocationValidator`.

Запасний шлях без фабрики (якщо сервіс не в реєстрі): створити маркер
`com._1c.g5.v8.dt.debug.core.bslLineBreakpointMarker` вручну і викликати
`IBreakpointManager.getBreakpoint(marker)` — але це крихко (внутрішні атрибути
`METHOD_SIGNATURE` тощо), краще фабрика.

### Debug target — `...debug.core.model.IRuntimeDebugClientTarget`

```java
interface IRuntimeDebugClientTarget extends IDebugTarget /* Eclipse! */ {
    IRuntimeDebugTargetThread[] getThreads();
    IThreadGroup[] getThreadGroups() throws DebugException; // групи = сеанси
    IEvaluationEngine getEvaluationEngine();
    IBslModuleLocator getModuleLocator();
    String getDebugServerUrl();
    Optional<IApplication> getApplication();
    ScriptVariant getScriptVariant();
    ...
}
```

Отримання цілей: **універсально** `DebugPlugin.getDefault().getLaunchManager().getDebugTargets()`
і `instanceof IRuntimeDebugClientTarget`, або через сервіс
`IRuntimeDebugClientTargetManager.listDebugTargets()`.

### Потік (предмет відладки) — `IRuntimeDebugTargetThread extends IThread, IDisconnect`

```java
IBslStackFrame[] getStackFrames();      // ковариантно до IThread
IBslStackFrame getTopStackFrame();
DebugTargetId getRuntimeDebugTarget();  // seanceId, seanceNo, userName, infoBaseAlias...
DebugTargetType getType();              // CLIENT, MANAGED_CLIENT, SERVER, JOB, HTTP_SERVICE...
```

Кроки/продовження — успадковані від Eclipse `IStep`/`ISuspendResume`:
`canStepOver()/stepOver()`, `stepInto()`, `stepReturn()`, `canResume()/resume()`,
`suspend()`, `isSuspended()`.

### Стек-фрейм — `IBslStackFrame extends IStackFrame`

```java
String getSignature();          // підпис методу
int getLevel();
URI getSource();                // EMF URI модуля BSL
BslModuleReference getReference(); // parentUuid/propertyUuid/project
IBslVariable[] getVariables() throws DebugException;    // локальні
IBslVariable[] getModuleVariables() / getModuleProperties();
IEvaluationChain reevaluateVariables();
// зі стандартного IStackFrame: getLineNumber(), getName()
```

### Змінні і значення

```java
interface IBslVariable extends IVariable {
    IBslValue getValue();       // може бути "не обчислене"
    boolean isEvaluated();
    void evaluate() throws DebugException;  // СИНХРОННО дообчислює значення (мережевий виклик!)
    String toWatchExpression();
}
interface IBslValue extends IValue {
    String getValueString();    // з IValue
    String getDetailString() throws DebugException;
    String getValueTypeName();
    BslValueType getType();
    boolean hasVariables();     // розгортання колекцій/об'єктів
    IBslVariable[] getVariables() throws DebugException;
    boolean isPending() / isEvaluated() / isUnreadable();
}
```

### Evaluate BSL-виразу — пакет `...debug.core.model.evaluation`

```java
IEvaluationRequest req = EvaluationRequest.builder(new BslValuePath("МійВираз"))
    .setStackFrame(bslFrame)
    .setInterface(ViewInterface.CONTEXT)     // NONE|CONTEXT|ENUM|COLLECTION
    .setMaxTestSize(1000)                     // [sic] так називається сеттер
    .setMultiLine(true)
    .setEvaluationListener(result -> { ... }) // IEvaluationListener.evaluationComplete
    .build();
// варіант A: синхронно в потоці виклику
target.getEvaluationEngine().evaluateExpression(req);      // DebugException при збої
// варіант B: через Eclipse Job
new EvaluationJob(req).schedule();
```

Результат — `IEvaluationResult`:

```java
CalculationResultBaseData getResult();  // EMF-об'єкт з debug.model
boolean isSuccess();
String getErrorMessage();
```

`CalculationResultBaseData` (бандл `com._1c.g5.v8.dt.debug.model`):
`getResultValueInfo()` → `BaseValueInfoData`: `getTypeName()`, `getPres()` (**byte[]** —
презентація; UTF-8), `getValueString()` (byte[]), `getValueDecimal()/getValueBoolean()/getValueDateTime()`,
`getIsExpandable()`, `getCollectionSize()`; `getErrorOccurred()`, `getExceptionStr()` (byte[]).
Розгортання результату-колекції — `getCalculationResult()` → `CalculationResultObjData`.

Примітка: точний формат byte[] (чисте UTF-8 чи серіалізація платформи) підтвердити в
runtime; в EDT ці байти рендеряться в UI як текст презентації.

### Застосунки — `com.e1c.g5.dt.applications.IApplicationManager` (сервіс уже використовується проєктом)

```java
List<IApplication> getApplications(IProject);
Optional<IApplication> getDefaultApplication(IProject);
LifecycleState getLifecycleState(ILifecycleAware);  // UNKNOWN..STARTED..ERROR
Optional<Process> start(IApplication, ExecutionContext, IProgressMonitor);
IStatus check(IApplication, ApplicationCheckUnknownStateTreatment, ExecutionContext, IProgressMonitor);
```

`ExecutionContext` має ключі `ACTIVE_LAUNCH`, `DEBUG_URL`, `DEBUG_TARGET`, `LAUNCH_URL` —
його заповнює launch delegate. `IInfobaseApplication extends IApplication`:
`getInfobase()` → `InfobaseReference`; `ATTRIBUTE_DEBUG_PORT = "port"`.

## Ланцюжки операцій

### listTargets
1. `DebugPlugin.getDefault().getLaunchManager().getLaunches()` / `.getDebugTargets()`.
2. Фільтр `instanceof IRuntimeDebugClientTarget`; для кожного — `getDebugServerUrl()`,
   `getApplication()`, `getThreads()` → `DebugTargetId` (seanceNo, userName, type),
   `isSuspended()` кожного потоку.
3. Додатково список застосунків: `IApplicationManager.getApplications(project)` + `getLifecycleState`.

### setBreakpoint(module, line)
1. Знайти `IFile` модуля: `project.getFile(path)` (шлях типу `src/CommonModules/X/Module.bsl`).
2. `IBslBreakpointFactory.createLineBreakpoint(file, line)`.
3. `DebugPlugin.getDefault().getBreakpointManager().addBreakpoint(bp)`.
4. (Опційно) `bp.setCondition(...)`. Уже запущений target підхопить зміну через
   `IBreakpointListener` — EDT сам транслює її в dbgs.

### launch (запуск 1С з відладкою)
Варіант 1 (рекомендований): взяти існуючу конфігурацію користувача —
`launchManager.getLaunchConfigurationType("com._1c.g5.v8.dt.debug.core.RemoteRuntime")`,
`getLaunchConfigurations(type)`, вибрати за project/application,
`config.launch(ILaunchManager.DEBUG_MODE, monitor)` → повертає `ILaunch` (фактично `DtLaunch`).
Варіант 2: `type.newInstance(null, name)` + атрибути `PROJECT_NAME`, `APPLICATION_ID`,
`RUNTIME_INSTALLATION_USE_AUTO=true` (див. як це робить
`com._1c.g5.v8.dt.debug.ui...AbstractLaunchShortcut.createLaunchConfiguration/setDefaults` —
але ці методи protected, у нас лише той самий публічний ланцюг).

### wait suspend → getState
1. За targetId знайти `IRuntimeDebugClientTarget`.
2. `getThreads()`; для кожного `isSuspended()`. Подієво: `DebugPlugin.addDebugEventListener`
   (DebugEvent.SUSPEND з деталлю BREAKPOINT) — для операції waitSuspend з таймаутом.
3. Для suspended-потоку: `getStackFrames()` → рівень, `getSignature()`, `getLineNumber()`,
   `getSource()`/`getReference()` → мапінг на модуль через `IBslModuleLocator`.

### getVariables(frame)
1. `IBslStackFrame.getVariables()` (+ `getModuleVariables()` за потреби).
2. Для кожної `IBslVariable`: якщо `!isEvaluated()` → `evaluate()` (синхронний виклик до dbgs).
3. `getValue()` → `getValueString()` / `getValueTypeName()` / `hasVariables()`;
   дочірні — `value.getVariables()` (обмежити глибину/кількість!).

### evaluate(expr, frame)
1. Frame має бути suspended.
2. `EvaluationRequest.builder(new BslValuePath(expr)).setStackFrame(frame)
   .setEvaluationListener(l).build()` → `engine.evaluateExpression(req)`.
3. Дочекатися `evaluationComplete` (CountDownLatch з таймаутом ~10-30 с) →
   розібрати `CalculationResultBaseData`.

### stepOver / stepInto / stepReturn / resume / pause
`IThread` відповідного потоку: `canStepOver() && stepOver()` тощо; `resume()`; `suspend()`.
Це асинхронні команди — повертати «команду надіслано», стан опитувати наступним getState.

## Import-Package / classpath для збірки

Додати в MANIFEST.MF (усі `resolution:=optional`, як прийнято в проєкті):

```
org.eclipse.debug.core, org.eclipse.debug.core.model,
com._1c.g5.v8.dt.debug.core, com._1c.g5.v8.dt.debug.core.model,
com._1c.g5.v8.dt.debug.core.model.breakpoints,
com._1c.g5.v8.dt.debug.core.model.evaluation,
com._1c.g5.v8.dt.debug.core.model.values,
com._1c.g5.v8.dt.debug.model.base.data, com._1c.g5.v8.dt.debug.model.calculations
```

У build.ps1 до classpath: `org.eclipse.debug.core`, `com._1c.g5.v8.dt.debug.core_18*`,
`com._1c.g5.v8.dt.debug.model`.

## Ризики та обмеження

1. **Публікація сервісів в OSGi не підтверджена статично** для `IBslBreakpointFactory` і
   `IRuntimeDebugClientTargetManager` (Guice-бандл з ServiceRegistrator; перелік бандлів
   видно лише в runtime). Перший крок реалізації — probe через
   `EdtServices.describeAvailability(...)`. Якщо фабрики немає в реєстрі — fallback:
   ручний маркер `bslLineBreakpointMarker` (крихко) або відображення на UI-механізми.
2. **Асинхронність**: evaluate — колбек; кроки/resume — команди без відповіді; стан
   приходить через DebugEvent. MCP-інструмент має скрізь працювати за схемою
   «команда → окремий запит стану», інакше зависання HTTP-запитів.
3. **Синхронні мережеві виклики** (`IBslVariable.evaluate()`, `engine.evaluateExpression`)
   ходять у dbgs по HTTP — обов'язкові таймаути і обмеження обсягу (maxTextSize,
   глибина розгортання).
4. **Потрібен запущений 1С:Підприємство під відладкою з EDT**: без активного `ILaunch` з
   `IRuntimeDebugClientTarget` доступні лише операції з breakpoint-ами (вони персистентні
   і підхоплюються при наступному запуску).
5. **Потоки UI**: Breakpoint-операції йдуть через workspace (ISchedulingRule) — виклик з
   HTTP-потоку MCP допустимий, але краще загорнути в WorkspaceJob. Кроки/resume — без UI.
6. **1-based рядки** в Eclipse-маркерах; узгодити з конвенцією інших інструментів проєкту.
7. **byte[] поля** презентацій у EMF-моделі — перевірити кодування в runtime.
8. Дві версії протоколу (`debug.core_v8.3.10/15`) — для старих платформ; ігноруємо,
   основний бандл сам обирає клієнта за версією dbgs.

## Поетапний план реалізації

1. **Етап 0 (skeleton, готово)**: `LaunchDebuggerTool` з операціями listTargets /
   setBreakpoint / removeBreakpoint / listBreakpoints / getState / getVariables /
   stepOver / stepInto / resume / pause; evaluate — заглушка з поясненням.
   Реєстрація в ToolRegistry + MANIFEST/build.ps1 (окремим кроком, свідомо).
2. **Етап 1**: runtime-probe сервісів (describeAvailability) у живому EDT; підтвердити
   `IBslBreakpointFactory`, `IRuntimeDebugClientTargetManager`; перевірити повний цикл
   set breakpoint → запуск з UI → зупинка → getState → step → resume.
3. **Етап 2**: getVariables з лінивим дообчисленням і лімітами; evaluate через
   EvaluationRequest + CountDownLatch; розбір CalculationResultBaseData (кодування Pres).
4. **Етап 3**: операція launch (запуск застосунку в debug-режимі за існуючою
   конфігурацією), waitSuspend з DebugEvent-листенером, умовні breakpoint-и,
   watch-вирази, модифікація змінних (`IEvaluationEngine.modifyExpression`).
