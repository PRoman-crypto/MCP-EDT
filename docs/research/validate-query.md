# validate_query — перевірка тексту запиту 1С через QL-інфраструктуру EDT

Дослідження для інструмента `validate_query` (EDT 2025.2.6, jar-и з
`C:\Program Files\1C\1CE\components\1c-edt-2025.2.6+4-x86_64\plugins`).
Усі сигнатури нижче зняті через `javap` — не з пам'яті.

## Ключова знахідка

Мова запитів 1С у EDT — повноцінна Xtext-мова з ім'ям `com._1c.g5.v8.dt.ql.Ql`
і **розширенням файлу `ql`** (підтверджено в `plugin.xml` бандла
`com._1c.g5.v8.dt.ql.ui`):

```xml
<extension point="org.eclipse.xtext.extension_resourceServiceProvider">
    <resourceServiceProvider
        class="com._1c.g5.v8.dt.ql.ui.QlExecutableExtensionFactory:org.eclipse.xtext.ui.resource.IResourceUIServiceProvider"
        uriExtension="ql"/>
</extension>
<extension point="org.eclipse.emf.ecore.extension_parser">
    <parser class="...:org.eclipse.xtext.resource.IResourceFactory" type="ql"/>
</extension>
```

Завдяки цьому **компільної залежності від бандлів `com._1c.g5.v8.dt.ql*` немає
взагалі**: усі потрібні сервіси дістаються з реєстру Xtext за розширенням, а в
коді фігурують лише generic-інтерфейси `org.eclipse.xtext.*`:

```java
IResourceServiceProvider provider = IResourceServiceProvider.Registry.INSTANCE
        .getResourceServiceProvider(URI.createURI("__mcp_validate_query__.ql"));
IParser parser = provider.get(IParser.class);           // інжектований QlParser
IResourceValidator validator = provider.getResourceValidator();
```

Реєстр наповнюється декларативно (extension point), тобто провайдер доступний
одразу в UI-runtime EDT, без ручної активації бандлів QL і без
`QlStandaloneSetup` (той призначений для standalone-JVM, у OSGi його чіпати не
можна — зіб'є registration).

## Точні сигнатури (javap)

`org.eclipse.xtext.resource.IResourceServiceProvider` (jar `org.eclipse.xtext_2.33.0`):

```
IResourceValidator getResourceValidator();
<T> T get(Class<T>);
// Registry (inner):
static final IResourceServiceProvider$Registry INSTANCE;
IResourceServiceProvider getResourceServiceProvider(org.eclipse.emf.common.util.URI);
```

`org.eclipse.xtext.parser.IParser` / `IParseResult`:

```
IParseResult parse(java.io.Reader);
EObject getRootASTElement();
Iterable<org.eclipse.xtext.nodemodel.INode> getSyntaxErrors();
boolean hasSyntaxErrors();
```

`org.eclipse.xtext.nodemodel.INode` (позиція помилки):

```
int getOffset();          // зсув у тексті
int getStartLine();       // 1-based рядок
SyntaxErrorMessage getSyntaxErrorMessage();  // .getMessage(), .getIssueCode()
```

Колонки в `INode` немає — рахуємо з `getOffset()` по останньому `\n` у тексті.

`org.eclipse.xtext.validation.IResourceValidator` / `Issue` / `CheckMode`:

```
List<Issue> validate(Resource, CheckMode, CancelIndicator);
// Issue:
Severity getSeverity();           // ERROR | WARNING | INFO | IGNORE
String getMessage();
Integer getLineNumber();          // 1-based
Integer getColumn();              // 1-based
boolean isSyntaxError();
// CheckMode: FAST_ONLY | NORMAL_ONLY | EXPENSIVE_ONLY | NORMAL_AND_FAST | ALL
// CancelIndicator.NullImpl — константа "не скасовано"
```

`org.eclipse.xtext.ui.resource.IResourceSetProvider` (jar `org.eclipse.xtext.ui_2.33.0`):

```
ResourceSet get(org.eclipse.core.resources.IProject);
```

`org.eclipse.emf.ecore.resource.Resource$Diagnostic` (для resource.getErrors()):

```
String getMessage(); int getLine(); int getColumn();
```

Корисне з `QlRuntimeModule` (бандл `com._1c.g5.v8.dt.ql`, javap):
інжектор QL біндить, серед іншого, `IParser`, `IResourceValidator`
(через `configureResourceValidator`), `IQlCachedScopeProvider`,
`IExpressionTypeChecker`, `IDynamicDbViewFieldComputer`,
`com._1c.g5.v8.dt.lcore.parser.helper.IParseHelper`
(`<T> T parse(String, URI, ResourceSet, Map, List<Resource.Diagnostic>)`) —
останній є альтернативним шляхом парсингу, якщо колись знадобиться AST.

## Реалізовані рівні

### Рівень 1 — синтаксис (завжди)

`provider.get(IParser.class).parse(new StringReader(query))` →
`IParseResult.getSyntaxErrors()`; для кожного вузла: `getStartLine()`,
колонка з `getOffset()`, `getSyntaxErrorMessage().getMessage()`.
Повідомлення локалізовані (у бандлі QL є `IMessage_ru.properties`).
Жодного проєкту/файлу не потрібно, потокобезпечно.

### Рівень 2 — семантика (якщо передано `project`)

1. `IProject` через наявний `V8Access.resolveEclipseProject(name)`.
2. `ResourceSet` проєкту: `provider.get(IResourceSetProvider.class).get(project)` —
   так скоупінг QL (`IQlCachedScopeProvider` → `IPlatformScopeProvider`) бачить
   метадані конфігурації цього проєкту.
3. Ресурс з platform-URI **всередині** проєкту:
   `URI.createPlatformResourceURI(project.getName() + "/__mcp_validate_query__.ql", true)`;
   `resourceSet.createResource(uri)` спрацьовує, бо фабрика для `ql`
   зареєстрована глобально (extension_parser).
4. `resource.load(byteStream, Map.of(XtextResource.OPTION_ENCODING, "UTF-8"))`.
5. `validator.validate(resource, CheckMode.ALL, CancelIndicator.NullImpl)` →
   Issue.ERROR у `errors`, Issue.WARNING у `warnings`.
6. `finally`: `resource.unload()` + видалення з ResourceSet (фізичний файл не
   створюється — ресурс живе лише в пам'яті).

Якщо семантичний конвеєр падає (будь-який Exception/LinkageError) — інструмент
деградує до рівня 1 і додає в результат `note` з причиною.

## Формат результату

```json
{"valid": false, "level": "syntax|semantic",
 "errors":   [{"line": 2, "column": 8, "message": "…"}],
 "warnings": [{"line": 1, "column": 1, "message": "…"}],   // лише semantic, якщо є
 "project": "MyConf",                                       // лише semantic
 "note": "…"}                                               // лише при деградації
```

`valid` залежить тільки від `errors` (warnings не впливають).

## Інтеграція (що додати при підключенні — існуючі файли в цій задачі не чіпалися)

1. **ToolRegistry.createDefault()**: `registry.register(new ValidateQueryTool());`
   (+ import `com.polischuk.edt.prl.tools.validation.ValidateQueryTool`).
2. **META-INF/MANIFEST.MF** — доповнити Import-Package (усі `resolution:=optional`,
   версії 2.33.0 у EDT 2025.2.6):
   ```
   org.eclipse.xtext.diagnostics;resolution:=optional,
   org.eclipse.xtext.nodemodel;resolution:=optional,
   org.eclipse.xtext.parser;resolution:=optional,
   org.eclipse.xtext.resource;resolution:=optional,
   org.eclipse.xtext.ui.resource;resolution:=optional,
   org.eclipse.xtext.util;resolution:=optional,
   org.eclipse.xtext.validation;resolution:=optional,
   org.eclipse.emf.ecore.resource
   ```
   (`org.eclipse.emf.ecore.resource` експортується `org.eclipse.emf.ecore`, який
   уже в Import-Package сусіднім пакетом; QL-пакети імпортувати не треба.)
   `NoClassDefFoundError` при відсутності Xtext перехоплюється в execute() і
   перетворюється на зрозумілий `IllegalStateException`.
3. **build.ps1** — додати в `$cp` три jar-и:
   `org.eclipse.xtext`, `org.eclipse.xtext.util`, `org.eclipse.xtext.ui`
   (через `Resolve-Jar`). Увага: маска `org.eclipse.xtext_*.jar` не зачепить
   `org.eclipse.xtext.ui_*.jar` — `Resolve-Jar` шукає `prefix + "_*.jar"`, тож
   конфліктів немає.

## Compile classpath (перевірено: javac --release 17 → exit 0)

Базовий набір з build.ps1 плюс:

- `org.eclipse.xtext_2.33.0.v20231121-0955.jar`
- `org.eclipse.xtext.util_2.33.0.v20231121-0955.jar`
- `org.eclipse.xtext.ui_2.33.0.v20231121-0955.jar`

## Обмеження і ризики

- **Семантика потребує живої перевірки.** Прив'язка dummy-ресурсу до проєкту
  через `IResourceSetProvider.get(project)` + platform-URI — стандартний
  Xtext-механізм, але скоупінг EDT (IPlatformScopeProvider) може додатково
  залежати від того, що файл реально лежить у проєктній структурі. Якщо на
  живому EDT валідний запит поверне помилки лінкінгу на кожну таблицю — значить
  скоуп не резолвиться, і семантичний шлях треба або доробити (створення
  тимчасового IFile у проєкті), або тимчасово рекомендувати виклик без project.
  Синтаксичний рівень від цього не залежить.
- `CheckMode.ALL` включає expensive-перевірки — на великих запитах може бути
  повільніше; за потреби знизити до `NORMAL_AND_FAST`.
- Запит парситься цілком (пакетні запити `;` включно — грамати QL це
  підтримує: `IQlFile` у `ql.core` описує список запитів).
- Локаль повідомлень — за локаллю EDT (є російська локалізація).
- `QlStandaloneSetup.doSetup()` НЕ використовувати всередині EDT — він
  перереєструє сервіси і може поламати робочий інжектор.

## План тестів (після інтеграції, на живому EDT)

1. Валідний простий: `ВЫБРАТЬ 1` → `valid:true, errors:[]`.
2. Валідний з таблицею: `ВЫБРАТЬ Т.Ссылка ИЗ Справочник.Номенклатура КАК Т`
   — без project: `valid:true` (синтаксис); з project: `valid:true`, якщо
   довідник існує (перевірка семантичного шляху).
3. Синтаксична помилка: `ВЫБРАТЬ ИЗ` → `valid:false`, позиція на `ИЗ`.
4. Обірваний запит: `ВЫБРАТЬ Поле ГДЕ` → помилка з коректними line/column у
   багаторядковому тексті (перевірити обчислення колонки).
5. Семантична помилка (з project): `ВЫБРАТЬ Т.НеIснуючеПоле ИЗ
   Справочник.Номенклатура КАК Т` → `valid:false` на рівні semantic,
   на рівні syntax — `valid:true`.
6. Неіснуюча таблиця (з project): `ИЗ Справочник.НемаТакого` → семантична
   помилка лінкінгу.
7. Англійський синтаксис: `SELECT 1` → `valid:true` (граматика двомовна).
8. Проєкт не знайдено → `IllegalArgumentException` зі списком доступних.
