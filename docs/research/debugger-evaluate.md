# evaluate — обчислення BSL-виразу на зупиненому фреймі

Довершення етапу 2 з `docs/research/debugger.md`. Всі сигнатури зняті javap
(Zulu 17) з jar-ів EDT 2025.2.6: `com._1c.g5.v8.dt.debug.core_18.0.0.v202605050943.jar`,
`com._1c.g5.v8.dt.debug.model_3.9.100.v202605050943.jar`. Реалізація —
`src/com/polischuk/edt/prl/tools/debug/DebugEvaluator.java` (компілюється, див. нижче).

## TL;DR

- Механізм точно такий, як у watch-виразів EDT (`BslWatchExpressionDelegate`,
  дизасембльовано): `EvaluationRequest.builder(new BslValuePath(expr))
  .setStackFrame(frame).setExpressionUuid(UUID.randomUUID())
  .setInterface(ViewInterface.NONE).setEvaluationListener(l).build()` →
  `target.getEvaluationEngine().evaluateExpression(request)`.
- **byte[]-поля результату — звичайний UTF-8.** Підтверджено дизасемблюванням
  `com._1c.g5.v8.dt.internal.debug.core.model.RuntimePresentationConverter`:
  `presentation(byte[]) = bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8)`.
  Жодної серіалізації платформи — просто текст. Клас internal (не експортується),
  тому в `DebugEvaluator` ці два рядки відтворені локально.
- Виклик асинхронний: двигун реєструє слухач і шле HTTP-запит у dbgs з
  waitTime=0; результат приходить пізніше подією `RDBGEvalExprCompleted` →
  `RuntimeEvaluationEngine.notifyEvaluated` → наш `IEvaluationListener`.
  `DebugEvaluator` чекає через `CountDownLatch` (дефолт 10 с).

## Точні сигнатури (javap)

### Запит

```java
// com._1c.g5.v8.dt.debug.core.model.evaluation
public final class EvaluationRequest implements IEvaluationRequest {
    public static EvaluationRequestBuilder builder(BslValuePath);
}
public final class EvaluationRequest$EvaluationRequestBuilder {
    EvaluationRequestBuilder setStackFrame(IBslStackFrame);
    EvaluationRequestBuilder setInterface(ViewInterface);          // NONE|CONTEXT|ENUM|COLLECTION
    EvaluationRequestBuilder setInterfaces(List<ViewInterface>);
    EvaluationRequestBuilder setMaxTestSize(int);                  // [sic]; дефолт 100, 0 = без ліміту
    EvaluationRequestBuilder setMultiLine(boolean);
    EvaluationRequestBuilder setEvaluationPage(int offset, int length);
    EvaluationRequestBuilder setExpressionUuid(UUID);
    EvaluationRequestBuilder setEvaluationListener(IEvaluationListener);
    IEvaluationRequest build();
}
// com._1c.g5.v8.dt.debug.core.model.values
public class BslValuePath {
    public BslValuePath(String expression);
}
```

### Двигун і слухач

```java
public interface IEvaluationEngine {
    void evaluateExpression(IEvaluationRequest) throws DebugException;
    // + evaluateExpressions(chain, targetId), evaluateVariables(frame, listener),
    //   modifyExpression(...), dispose()
}
public interface IEvaluationListener {
    void evaluationComplete(IEvaluationResult) throws DebugException;
}
public interface IEvaluationResult {
    CalculationResultBaseData getResult();   // може бути null
    boolean isSuccess();
    String getErrorMessage();
}
```

Двигун — з `IRuntimeDebugClientTarget.getEvaluationEngine()`. Важливо:
`IBslStackFrame.getDebugTarget()` ковариантно повертає
`IRuntimeDebugClientTarget` (видно в байткоді `shouldEvaluate`) — target можна
брати прямо з фрейму.

### Результат (EMF, бандл debug.model — експортує всі пакети)

```java
// com._1c.g5.v8.dt.debug.model.calculations
public interface CalculationResultBaseData extends EObject {
    CalculationResultState getEvalResultState();
    BaseValueInfoData getResultValueInfo();
    CalculationResultObjData getCalculationResult(); // розгортання колекцій
    Boolean getErrorOccurred();
    byte[] getExceptionStr();                        // UTF-8 текст винятку 1С
}
public interface BaseValueInfoData extends EObject {
    String getTypeName();            boolean isSetTypeName();
    byte[] getPres();                // UTF-8 презентація значення
    byte[] getValueString();         // UTF-8 (для рядкових значень)
    BigDecimal getValueDecimal();    Boolean getValueBoolean();
    XMLGregorianCalendar getValueDateTime();
    Boolean getIsExpandable();
    BigDecimal getCollectionSize();  boolean isSetCollectionSize();
}
```

## Як EDT сам декодує результат (дизасемблювання)

`BslWatchExpressionDelegate.createWatchExpressionResult(...)`:

1. `data.getErrorOccurred().booleanValue()` == true →
   помилка = `RuntimePresentationConverter.presentation(data.getExceptionStr())` (UTF-8);
2. інакше значення будується з `data.getResultValueInfo()` через
   `target.getValuesFactory().createValue(frame, path, uuid, info, listener)`;
   презентацію UI бере з `info.getPres()` (UTF-8).
3. `result.getResult() == null` → «Empty evaluation result».

`RuntimeEvaluationEngine.evaluateExpression(request)` (важливі деталі поведінки):

- `shouldEvaluate(frame)` = `frame.isEnabled() && frame.getDebugTarget().isSuspended()`;
  якщо false — **мовчки return, слухач не буде викликаний ніколи** (тому
  `DebugEvaluator` перевіряє це заздалегідь і кидає зрозумілий виняток);
- слухач реєструється в Map за випадковим evaluationUuid;
  `runtimeClient.evaluateExpression(targetId, waitTime, req)` — HTTP до dbgs,
  `waitTime` за замовчуванням 0 (`IEvaluationConstants.EVALUATION_WAITING_TIME`);
- якщо dbgs повернув результат одразу (non-null) — слухач викликається
  **синхронно в потоці виклику** (`SuccessEvaluationResult`, isSuccess=true);
- інакше результат прийде пізніше через `notifyEvaluated(uuid, result)`
  з диспетчера подій dbgs (інший потік);
- `notifySuspended()` (нова зупинка) **чистить незавершені слухачі без
  виклику** — ще одна причина, чому потрібен таймаут на latch;
- мережевий збій → `DebugException` синхронно з `evaluateExpression`.

`CountDownLatch` коректно накриває обидва шляхи (синхронний і асинхронний).

## DebugEvaluator (зданий файл)

`<repo>\src\com\polischuk\edt\prl\tools\debug\DebugEvaluator.java`

```java
public static JsonObject evaluate(IRuntimeDebugClientTarget target, IBslStackFrame frame,
        String expression, long timeoutMs) throws Exception
```

- таймаут `<= 0` → 10 000 мс (`DEFAULT_TIMEOUT_MS`);
- `setMaxTestSize(10_000)` — дефолт EDT 100 символів замалий для MCP;
- результат: `{expression, value, type?, expandable?, collectionSize?}`;
  помилки обчислення (виняток 1С, таймаут, порожній результат) — у полі
  `error` без викидання винятку; помилки передумов (не suspended, фрейм
  вимкнено, немає engine) — `IllegalStateException`.

## Case-вставка для LaunchDebuggerTool.java (не редагував — файл у роботі)

1. У `switch` замість заглушки:

```java
case "evaluate" -> evaluate(arguments); //$NON-NLS-1$
```

2. Приватний метод (resolveFrame вже є в класі; target береться з фрейму —
   гарантовано той самий, без розсинхрону індексів):

```java
private static JsonObject evaluate(JsonObject arguments) throws Exception {
    if (!arguments.has("expression")) { //$NON-NLS-1$
        throw new IllegalArgumentException("Не вказано expression (BSL-вираз)."); //$NON-NLS-1$
    }
    IStackFrame frame = resolveFrame(arguments);
    if (!(frame instanceof IBslStackFrame bslFrame)) {
        throw new IllegalStateException(
                "Фрейм не є IBslStackFrame — ціль не є BSL-відладкою EDT."); //$NON-NLS-1$
    }
    long timeoutMs = arguments.has("timeoutMs") ? arguments.get("timeoutMs").getAsLong() : 0; //$NON-NLS-1$
    return DebugEvaluator.evaluate(bslFrame.getDebugTarget(), bslFrame,
            arguments.get("expression").getAsString(), timeoutMs); //$NON-NLS-1$
}
```

3. Додатковий import: `com._1c.g5.v8.dt.debug.core.model.IBslStackFrame`
   (IRuntimeDebugClientTarget вже імпортовано).

4. В `inputSchema()` (необов'язково): параметр
   `"timeoutMs":{"type":"integer","description":"evaluate: таймаут очікування, мс (за замовчуванням 10000)"}`;
   у `help()` замінити рядок evaluate на
   `"target + thread + frame + expression: обчислити BSL-вираз на зупиненому фреймі"`.

## MANIFEST.MF / build.ps1

`build.ps1` вже містить обидва debug-jar-и — класпат змін не потребує.
У `Import-Package` MANIFEST.MF додати (як завжди, optional):

```
 com._1c.g5.v8.dt.debug.core.model.evaluation;resolution:=optional,
 com._1c.g5.v8.dt.debug.core.model.values;resolution:=optional,
 com._1c.g5.v8.dt.debug.model.calculations;resolution:=optional,
```

Усі три пакети експортовані бандлами (`debug.core` — перевірено в
debugger.md; `debug.model` — Export-Package містить усі пакети моделі,
перевірено з MANIFEST jar-а).

## Компіляція (перевірено)

`javac --release 17` усіх 54 .java проєкту разом з `DebugEvaluator.java`
проти класпату з build.ps1 → без помилок (temp-папка, існуючі файли не
змінювались, build.ps1 не запускався, деплою не було).

## План живого тесту

1. Зібрати/задеплоїти плагін з інтегрованим case (окрема сесія), перезапустити EDT.
2. В EDT: конфігурація запуску «1С:Предприятие» → Debug (RemoteRuntime).
3. MCP: `setBreakpoint` у модуль, який точно виконається (наприклад,
   `src/CommonModules/.../Module.bsl` або модуль керованого застосунку),
   `listTargets` → target=0.
4. У 1С виконати дію, що приводить до точки → `getState` до
   `suspended=true`, зафіксувати thread/frame.
5. `evaluate expression="1+1"` → очікувано `{value:"2", type:"Число"}`
   (typeName локалізований — росіянською в ru-EDT).
6. `evaluate expression="<ім'я локальної змінної з getVariables>"` →
   значення збігається з getVariables; для колекції — `expandable:true`,
   `collectionSize`.
7. `evaluate expression="НеіснуючаЗмінна"` → поле `error` з текстом винятку 1С.
8. Негативні: evaluate без зупинки → IllegalStateException «не зупинена»;
   evaluate і одразу resume з іншого клієнта → таймаут-повідомлення.
9. Перевірити кирилицю у значеннях (UTF-8) і великий рядок (обрізання
   до maxTextSize=10000 — обрізає dbgs на своєму боці).

## Ризики / впевненість

- Ланцюг запиту й декодування — **висока впевненість**: 1:1 з робочим кодом
  watch-виразів EDT, знятим з байткоду; кодування UTF-8 підтверджено кодом
  конвертера, а не припущенням.
- Залишкова runtime-невідомість: чи повертає dbgs `Pres` для всіх типів
  (для примітивів можливий порожній Pres — тому fallback на `ValueString`;
  якщо і він порожній для Числа/Булево, у живому тесті додати fallback на
  `getValueDecimal()/getValueBoolean()/getValueDateTime()` — геттери вже є).
- `getErrorOccurred()` в EDT викликається без null-перевірки (EMF-дефолт
  false), у нас — `Boolean.TRUE.equals(...)`, безпечніше.
- Слухач після таймауту лишається зареєстрованим у двигуні до наступного
  suspend/notify — це no-op лямбда, витоку станів немає.
