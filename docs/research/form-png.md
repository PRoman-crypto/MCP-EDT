# PNG-рендер керованої форми (get_form_image, format=image)

Дослідження 2026-08-26, EDT 2025.2.6+4. Метод: `jar -tf` + `javap` (Zulu 17) по jar-ах
`C:\Program Files\1C\1CE\components\1c-edt-2025.2.6+4-x86_64\plugins`, без розпакування.

## Підсумок

- **Реалізовано рівень B («sketch»)** — `FormImageRenderer.render(...)`: власний спрощений
  рендер із DOM-дерева Form.form на offscreen SWT Image в UI-треді → PNG base64.
  Перевірено автономним смоук-тестом на `Справочник.Номенклатура / ФормаЭлемента`
  (752×2572, ~55 КБ PNG): групи, поля, кнопки, попапи, вкладки, таблиця з колонками —
  все на місці й читабельне.
- **Рівень A (справжній WYSIWYG)** — механізм у EDT знайдено і описано нижче; він
  реальний, але вимагає (1) нових Import-Package у MANIFEST.MF і (2) доступу до
  внутрішнього Guice-інжектора form.ui. Відкладено до окремої ітерації.

## A. Як EDT малює попередній перегляд форми (знахідки)

Ланцюжок (усе з'ясовано через javap):

1. **`com._1c.g5.v8.dt.form.ui`** → `com._1c.g5.v8.dt.form.ui.editor.FormEditorPage`
   тримає поле `wysiwygViewer : com._1c.g5.v8.dt.form.internal.ui.editor.FormPresentationViewer`.
2. `FormPresentationViewer extends `**`com._1c.g5.v8.dt.form.presentation.wysiwyg.FormWysiwygViewer`**
   (бандл `com._1c.g5.v8.dt.form.presentation` — клас ПУБЛІЧНИЙ, це офіційна точка входу).
   Це `org.eclipse.jface.viewers.Viewer`: `getControl()` повертає SWT `Control`,
   `setInput(Object)` приймає **`com._1c.g5.v8.dt.form.model.Form`** (EMF-модель форми,
   FormEditorPage передає `getModel()`).
3. Конструктор `FormWysiwygViewer` (сигнатура з javap):
   `(Composite parent, MappingController, IBmModel, TransformatorServiceProvider,
   RenderServiceProvider, ChartImageServiceProvider, IChartProvider,
   IRuntimeVersionSupport, CompatibilityMode, ClientInterfaceVariant,
   ClientApplicationTheme, List<IDropDelegate>)`.
   - `TransformatorServiceProvider` і `RenderServiceProvider`
     (`com._1c.g5.v8.dt.form.layout.service`) — **звичайні no-arg конструктори**;
     `RenderServiceProvider.get(Version)` віддає `NativeRenderService` — розкладку
     рахує нативна бібліотека платформи (бандл `com._1c.g5.v8.dt.form.native`),
     тому вигляд збігається з 1С.
   - `MappingController` (`com._1c.g5.v8.dt.form.mapping.core`, бандл
     `com._1c.g5.v8.dt.form`) — no-arg конструктор; FormEditorPage реєструє в ньому
     4 мапінги: `FormItemsMapping`, `FormCommandInterfaceMapping`,
     `FormParameterizedCommandMapping`, `FormIndependentCommandMapping` —
     кожен `new Xxx(Supplier<Form>, IBmModelManager)` + **обов'язковий
     `FormPlugin.getDefault().getInjector().injectMembers(mapping)`**
     (`com._1c.g5.v8.dt.form.internal.ui.FormPlugin` — internal!).
   - Мапінги наповнюються асинхронно: `MappingController.getMappingRootAsync(FormMapping.class, handler)`,
     оновлення жене `FormEditorMappingUpdater(IBmModel, controller)`.
   - `ClientInterfaceVariant` виводиться з `FormUtil.getInterfaceType(form, v8project)`,
     тема — `Configuration.getClientApplicationTheme()`, список drop-делегатів у
     read-only режимі — `Collections.emptyList()`.
4. Малювання: retained-модель `com._1c.g5.v8.dt.form.presentation.model.Presentation*`
   ("hippo"-layout, `internal.presentation.controls.desktop.PaintHelper`, `NeoDrawUtil`) —
   тобто це НЕ купа справжніх SWT-віджетів, а власний канвас. Знімок:
   `Control control = viewer.getControl()` на невидимому `Shell` →
   `control.print(GC)` або `GC.copyArea` → `ImageLoader(SWT.IMAGE_PNG)`.

### Чому рівень A не в цій ітерації

- **MANIFEST.MF заморожений** (заборона правок у цій задачі), а для статичного
  лінкування потрібні нові Import-Package: `com._1c.g5.v8.dt.form.model`,
  `com._1c.g5.v8.dt.form.mapping.core`, `com._1c.g5.v8.dt.form.mapping.item|cmi|parameterized|independent`,
  `com._1c.g5.v8.dt.form.presentation.wysiwyg`, `com._1c.g5.v8.dt.form.layout.service`,
  `com._1c.g5.v8.dt.form.layout.model.description`, `com._1c.g5.v8.dt.chart.common`,
  `com._1c.g5.v8.dt.form.service.dnd`, `com._1c.g5.v8.dt.metadata.common`.
  (Альтернатива без маніфесту — рефлексія через `Platform.getBundle(...).loadClass(...)`,
  але це ~10 класів і Guice-інжект, дуже крихко.)
- `FormPlugin.getInjector()` — internal API form.ui; без `injectMembers` мапінги
  не працюють.
- Пайплайн асинхронний (mapping root + hippo-розкладка добудовуються подіями):
  потрібне очікування готовності перед знімком, інакше порожня картинка.
- Ще потрібна EMF-модель `Form` із BM (наш інструмент сьогодні читає Form.form як XML;
  модель можна дістати через `IBmModel`/derived data за FQN форми — окрема робота).

### План рівня A (наступна ітерація)

1. Додати Import-Package (список вище) у MANIFEST.MF, jar-и — у `$cp` build.ps1
   (`com._1c.g5.v8.dt.form`, `.form.presentation`, `.form.layout`, `.form.model`, `.chart`).
2. Дістати `Form` (EMF) з BM-моделі проєкту за шляхом Form.form.
3. У `Display.syncExec`: невидимий `Shell` → `MappingController` + 4 мапінги
   (injectMembers через рефлексію до internal FormPlugin) → `new FormWysiwygViewer(...)`
   → `setLanguageCode` → `setInput(form)` → дочекатися mapping root
   (`getMappingRootAsync` + прокачування event loop із таймаутом) → `shell.layout()`,
   `control.setSize(...)` → `control.print(GC)` → PNG → усе dispose.
4. Фолбек на sketch, якщо щось із цього впало.

## B. Реалізований рівень: sketch-рендер

`<repo>\src\com\polischuk\edt\prl\tools\metadata\FormImageRenderer.java`

- Вхід: `render(Element formRoot)` / `render(Element formRoot, String titleFallback)` /
  `render(String formXml)` (сам парсить DOM так само, як GetFormImageTool).
- Вихід: `JsonObject {imageBase64 (PNG), width, height, level:"sketch"}`.
- UI-тред: `PlatformUI.getWorkbench().getDisplay().syncExec(...)` (захищено через
  `workbenchDisplay()` з фолбеком `Display.getDefault()` для автономних тестів);
  два проходи — вимір висоти по 1×1 Image, потім малювання; всі SWT-ресурси
  (Image, GC, Color, Font) — dispose у finally.
- Що малює: заголовок вікна; командні панелі (ряд кнопок із переносом);
  UsualGroup (рамка + синій заголовок, вертикально або колонками при
  `Horizontal*`); Pages (смуга вкладок + вміст кожної сторінки стеком із
  підписом `[Назва]`); поля InputField (підпис + бокс із «…»), CheckBoxField,
  LabelField, RadioButtonField, PictureField (бокс із хрестом); Button/Popup
  (кнопка, для попапа «▾»); Decoration (сірий текст); Table (заголовок колонок
  із сіткою + 3 порожні рядки; ColumnGroup сплощується). `visible=false` —
  сірим; `*Addition`/`Added` — пропускаються. Тексти обрізаються «…» по пікселях.
- Обмеження sketch: не викликає нативний layout платформи → ширини/пропорції
  приблизні; не показує картинки, стилі, кольори з extInfo, розтягування;
  одна (перша) вкладка «активна», решта сторінок — стеком нижче; висота
  обмежена 6000 px.

### Case-вставка для GetFormImageTool (файл у цій задачі не чіпався)

1. У `inputSchema()`: `"format":{"type":"string","enum":["structure","image"],"default":"structure"}`,
   опис — прибрати «PNG поки не підтримано».
2. В `execute(...)` одразу після `Element root = builder.parse(...).getDocumentElement();`:

```java
String format = arguments.has("format") ? arguments.get("format").getAsString() : "structure"; //$NON-NLS-1$
if ("image".equals(format)) { //$NON-NLS-1$
    return FormImageRenderer.render(root, path); // або людяніший заголовок kind.name: form
}
```

MANIFEST.MF, build.ps1, plugin.xml міняти НЕ потрібно: SWT/JFace/PlatformUI уже
видимі через `Require-Bundle: org.eclipse.ui` (re-export), jar-и SWT/JFace уже в `$cp`.

MCP-клієнт декодує `imageBase64` сам (наш McpTool повертає JsonElement; бінарних
блоків у протоколі сервера поки немає).

## План тесту

1. Зібрати та задеплоїти плагін (поза цією задачею), перезапустити EDT.
2. `get_form_image {kind:"Catalog", name:"Номенклатура", form:"ФормаЭлемента", format:"image"}`
   → очікувано `level:"sketch"`, width≈752, height≈2500–2600; декодувати base64 → PNG:
   зверху кнопки «ФормаЗаписатьИЗакрыть», «ФормаЗаписать», «Создать на основании ▾»,
   група «Шапка» з Description/Code, вкладки «Учетная информация … Аннотация»,
   таблиця «СерийныеНомера» з 3 колонками.
3. Регрес: `format:"structure"` (і без format) — відповідь як раніше.
4. Помилкові: неіснуюча форма → зрозуміла помилка; форма без items → порожнє вікно
   із заголовком.

## Автономний смоук (уже проганявся)

`scratchpad\smoke.ps1` компілює рендерер поза OSGi і рендерить будь-який Form.form:
результат `OK 752x2572 level=sketch bytes=56382` на ФормаЭлемента Номенклатури.
