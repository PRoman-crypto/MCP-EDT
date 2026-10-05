# create_mxl / MxlBuilder — генерація макетів табличних документів

Дата: 2026-08-28. Статус: реалізовано, перевірено офлайн (генерація → власний парсер
`GetMxlTool.parseMxlx` → структурна звірка з еталонами).

Пара до [get-mxl.md](get-mxl.md): там читання `Template.mxlx`, тут — запис.

## Що зроблено

`<repo>\src\com\polischuk\edt\prl\tools\write\MxlBuilder.java`

`public static String buildMxlx(JsonObject spec)` — компактний JSON-опис друкованої
форми → повний валідний `Template.mxlx` (схема `http://v8.1c.ru/8.2/data/spreadsheet`).
Жодних залежностей від `com._1c.g5.v8.dt.moxel*`, EMF чи Eclipse — чистий рядковий
серіалізатор на gson + StringBuilder. MANIFEST.MF/build.ps1/plugin.xml змін не потребують.

Викликається з уже наявного `TemplateOps` (операції `createTemplate`/`setTemplateContent`
інструмента `edit_metadata`) — див. розділ «Інтеграція».

## Формат spec

```jsonc
{
  "lang": "ru",                    // мова текстів комірок (типово ru)
  "columns": 8,                    // ширина документа в колонках; типово — за вмістом
  "columnWidths": [40,220,60,90],  // ширина колонок (одиниці EDT; типова колонка = 72; 0 = типова)
  "areas": [                       // області укладаються ЗГОРИ ВНИЗ у порядку оголошення
    {
      "name": "Шапка",             // ідентифікатор 1С (не з цифри); імена унікальні
      "type": "Rows",              // Rows (типово) | Rectangle | Columns
      "beginColumn": 0,            // лише Rectangle/Columns; типово — за вмістом
      "endColumn": 4,
      "rows": [
        { "height": 30,            // висота рядка (необов'язково)
          "cells": [ … ] }
      ]
    }
  ]
}
```

Комірка (`cells[]`) — об'єкт або просто рядок (скорочення для `{"text": …}`):

| Поле | Опис |
|---|---|
| `text` | текст комірки; якщо містить `[Параметр]` — автоматично стає шаблонним заповненням (`fillType=Template`) |
| `template` | явно шаблонний текст із `[Параметр]` |
| `parameter` | ім'я параметра (`Область.Параметры.Ім'я = …`), `fillType=Parameter` |
| `detailParameter` | параметр розшифровки |
| `col` | явний індекс колонки (0-базований); типово — наступна вільна |
| `span` | скільки колонок займає комірка (1 = без об'єднання) |
| `rowSpan` | скільки рядків займає комірка |
| `bold`, `italic` | накреслення (типовий шрифт стилю з ознакою) |
| `align` | `Auto\|Left\|Center\|Right\|Justify` |
| `valign` | `Top\|Center\|Bottom` |
| `wrap` | перенос по словах (`textPlacement=Wrap`) |
| `border` | суцільна рамка товщиною 1 з усіх боків |
| `format` | форматний рядок 1С, напр. `"ЧЦ=15; ЧДЦ=2"` |

`parameter` і `text`/`template` в одній комірці — помилка: формат mxl такого не має
(перевірено на всіх 294 реальних макетах — жодна комірка не містить одночасно `<tl>` і
`<parameter>`).

Дозволено також `spec.rows` на верхньому рівні без `areas` — рядки без іменованих
областей. Порожня spec (`{}`) дає валідний чистий бланк 10×3.

## Мінімально необхідний XML і чому саме такий

Еталон мінімуму — найдрібніші реальні макети проєкту (`DataProcessors/
ПечатьЭтикетокИЦенников/Templates/Эталон`, 1879 Б; `DataProcessors/ЗагрузкаДанныхИзФайла/
Templates/ПростойШаблон`, 2897 Б). Порядок елементів усередині `<document>` (той самий,
що пише EDT):

1. `<languageSettings>` — присутній у **294/294** файлів; `currentLanguage`,
   `defaultLanguage`, `languageInfo{id,code,description}`.
2. `<columns><size>N</size>[<columnsItem><index>i</index><column><formatIndex>F</formatIndex></column></columnsItem>…]</columns>`
   — `columnsItem` розріджений: пишемо лише для колонок із нетиповою шириною.
3. `<rowsItem><index>R</index><row>[<formatIndex>]…</row></rowsItem>` — розріджено,
   лише непорожні рядки. `<row>` без комірок, але з висотою → `<empty>true</empty>`.
4. `<drawing>` — не генеруємо.
5. `<templateMode>true</templateMode>` — у **294/294** файлів.
6. `<defaultFormatIndex>N</defaultFormatIndex>`.
7. `<height>N</height>` і `<vgRows>N</vgRows>` — у **294/294** збігаються.
8. `<merge><r><c>[<w>][<h>]</merge>`.
9. `<namedItem xsi:type="NamedItemCells"><name>…<area><type><beginRow><endRow><beginColumn><endColumn></area></namedItem>`.
10. `<line>` → `<font>` → `<format>` → `<picture>` (таблиці, на які посилаються індекси).

Ключові з'ясовані інваріанти (усі перевірені скриптом на 294 файлах):

- **`formatIndex` 1-базований.** Для кожного з 294 файлів `max(<f>|<formatIndex>|
  <defaultFormatIndex>) == кількість топ-рівневих <format>`, а `min == 1`. Індекс 0
  означає «формату немає» (так у `<drawing><formatIndex>0`). Тому таблиця форматів
  іде першим елементом-«словником», а перший зареєстрований формат
  (`<width>72</width>`) слугує `defaultFormatIndex`.
- **`<font>N</font>` 0-базований** — для кожного файла `max(<font>) == кількість
  топ-рівневих <font> − 1`, `min == 0`, дангліючих шрифтів немає.
- **`<border>`/`<leftBorder>`… 0-базовані** індекси в таблицю `<line>`.
- **`<merge><w>`/`<h>` — кількість ДОДАТКОВИХ колонок/рядків, не загальна.**
  Перевірено формально: для 2690 merge без `<h>` у 120 файлах колонки
  `c+1 … c+w` завжди порожні (0 винятків), тобто анкер + `w` сусідів. Наочно:
  у `ПФ_MXL_БланкВозврата` рядок 12 має комірку `Номенклатура` в колонці 2 і далі
  явний `<i>4</i>`, а merge для цього рядка — `r=12 c=2 w=1` (колонки 2–3).
  Тому `span:2 → <w>1</w>`, `rowSpan:1 → <h>` не пишеться.
- **Комірка ніколи не має одночасно `<tl>` і `<parameter>`**; `</tl>` завжди йде перед
  `<detailParameter>` (857 випадків, зворотних 0). Порядок дітей комірки:
  `<f>`, `<parameter>`, `<tl>`, `<detailParameter>`.
- **Форматний рядок 1С** живе у ВКЛАДЕНОМУ `<format>` усередині `<format>` як
  локалізований рядок (`<v8:item><v8:lang>/<v8:content>`), а `<mask>` — це окремий
  елемент (маска вводу), не формат.
- Порядок дітей `<format>` (виведено з усіх спостережених комбінацій):
  `font` → `border`/`leftBorder`/`topBorder`/`rightBorder`/`bottomBorder` → `height` →
  `width` → `horizontalAlignment` → `verticalAlignment` → `backColor` →
  `textPlacement` → `fillType` → … → `format` (форматний рядок).
- `-1` у межах області = «уся розмірність»: `Rows` → `beginColumn/endColumn = -1`,
  `Columns` → `beginRow/endRow = -1`.

Порожній макет, який генерує `createTemplate` без spec (783 Б):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<document xmlns="http://v8.1c.ru/8.2/data/spreadsheet" … >
	<languageSettings>…</languageSettings>
	<columns><size>10</size></columns>
	<templateMode>true</templateMode>
	<defaultFormatIndex>1</defaultFormatIndex>
	<height>3</height>
	<vgRows>3</vgRows>
	<format><width>72</width></format>
</document>
```

## Самоперевірка (офлайн-харнес)

Харнес: `scratchpad/mxlbuild/harness/…/MxlBuildHarness.java` (пакет
`com.polischuk.edt.prl.tools.metadata`, щоб дістатися до package-private
`GetMxlTool.parseMxlx`). Компіляція: `javac --release 17 -encoding UTF-8 -proc:none`
**усіх** `.java` проєкту + харнеса, класпас — копія блоку `$cp` із `build.ps1`.
Результат: **COMPILE OK**, без нових залежностей.

Кожен згенерований файл перевірявся двічі: (1) `DocumentBuilder.parse` —
well-formed XML, (2) `GetMxlTool.parseMxlx(…, "all")`.

| spec | згенеровано | що розпізнав наш парсер |
|---|---|---|
| `simple` — 3 області Rows, текст + параметри | 3005 Б, 4 рядки × 8 колонок | 3 області: `Шапка` (0–1, params `НомерДокумента`, `ДатаДокумента`), `Рядок` (2, `Товар/Кількість/Сума`), `Підвал` (3, `Разом`); 9 комірок, кожна з правильним `area` |
| `rich` — 4 області, `columnWidths`, шаблонний текст, merge (`span`/`rowSpan`), `bold`/`align`/`wrap`/`border`, `format`, `detailParameter` | 7056 Б, 7 рядків × 8 колонок | `Заголовок` (0–1, params `Номер [tpl]`, `ДатаДок [tpl]`, `Постачальник [tpl]`, `ЄДРПОУ [tpl]`), `ШапкаТаблиці` (2), `Рядок` (3, 5 параметрів + `detailParameter: НоменклатураСсылка`), `Підсумок` (4–5, `ВсьогоСума`); 15 комірок, `mergeCount: 4`; 16 форматів, 1 шрифт, 1 лінія |
| `shapes` — `Rectangle` + `Columns` + скорочення «комірка = рядок» | 2956 Б, 4 рядки × 6 колонок | `Прямокутник` (Rectangle, рядки 0–1, колонки 2–3), `КолонкаЗначень` (Columns, колонка 0), `Коротко` (Rows, 3); 7 комірок |
| `empty` — `{}` | 783 Б | 10×3, областей 0, комірок 0 |

Структурна звірка з еталоном `CommonTemplates/ПФ_MXL_БланкВозврата` (268 КБ, 68×12,
10 областей, 27 merge): збігаються кореневий тег і namespace-декларації, порядок
топ-рівневих елементів, форма `columns/columnsItem`, `rowsItem/index/row/c/i/c/f`,
`tl/v8:item/v8:lang/v8:content`, `merge/r/c/w/h`,
`namedItem xsi:type="NamedItemCells"` з `area{type,beginRow,endRow,beginColumn,endColumn}`,
блоки `line`/`font`/`format`. Наш генератор не пише лише `drawing`/`picture` (їх ми не
підтримуємо) і не пише `<width>` у форматах комірок (ширина — властивість колонки).

Негативні перевірки (усі дають зрозумілу помилку українською, нічого не генерується):

```
area-without-rows   Область Шапка: потрібен непорожній масив rows
bad-area-name       Ім'я області має бути ідентифікатором 1С (…): 2Шапка
dup-area            Область з таким ім'ям уже є: a
text-and-parameter  Комірка не може мати одночасно parameter і text/template (…)
bad-parameter       parameter має бути ідентифікатором 1С (…): Не Ідентифікатор
bad-align           Поле align: допустимі значення Auto|Left|Center|Right|Justify, отримано Middle
cell-collision      Рядок 0: дві комірки в колонці 1
```

У конфігурацію користувача під час перевірки нічого не писалося — харнес працює з
власною темп-папкою і читає еталони read-only.

## Обмеження

- Один набір колонок: `columnsID`/додаткові `<columns>` не генеруються (потрібні лише
  для макетів на кшталт ИНВ-19 з різною розбивкою колонок у різних областях).
- Немає малюнків, картинок, вкладених таблиць (`NamedItemEmbeddedTable`), зведених
  таблиць, `NamedItemDrawing`, колонтитулів, налаштувань друку.
- Шрифт — лише типовий шрифт стилю (`style:NormalTextFont`) з ознаками жирний/курсив.
  Довільні гарнітура/кегль не підтримуються (їх легко додати новим форматом, але це
  прив'язка до конкретного шрифту, а не до стилю конфігурації).
- Рамка — лише «всі боки, суцільна, товщина 1» (`border: true`); окремі боки та
  товщини не задаються.
- Кольори (`backColor`, `textColor`), відступи, `containsValue`/`valueType`,
  `controlType`, `bySelectedColumns` — не підтримуються.
- Мова одна (`lang`): багатомовні тексти комірок не генеруються.
- Ліміти: 10000 рядків, 1000 колонок, 10000 символів тексту комірки.
- Область типу `Columns` усе одно споживає рядки документа під свої комірки — це
  свідоме спрощення розкладки «згори вниз».
- Після генерації макет варто відкрити в EDT: редактор нормалізує таблиці форматів
  (додасть `<width>` у формати комірок, відсортує `namedItem` за іменем) — це очікувана
  зміна, а не втрата даних.

## Інтеграція

**Уже в коді** (перевірено при написанні): `TemplateOps` і виклики з `EditMetadataTool`
існують, бракувало тільки `MxlBuilder`. Після додавання `MxlBuilder.java` проєкт
компілюється повністю. Нижче — точні місця, щоб їх можна було відтворити/зіставити.

### 1. `EditMetadataTool.inputSchema()` — перелік операцій і два параметри

```java
"operation":{"type":"string","enum":[…,"createTemplate","setTemplateContent","batch"]},
…
"template":{"type":"string","description":"createTemplate/setTemplateContent: ім'я макета"},
"spec":{"type":"object","description":"createTemplate/setTemplateContent: опис макета друкованої форми {columns, areas:[{name,type,rows:[{cells:[{text|parameter|format}]}]}]}"},
```

### 2. `EditMetadataTool.execute()` — диспетчеризація ДО `WriteGate.check()`/`AbstractBmTask`

Обидві операції відкривають власну BM-транзакцію і додатково пишуть файл, тому їх не
можна класти в загальну транзакцію інструмента:

```java
if ("createTemplate".equals(operation)) { //$NON-NLS-1$
    return TemplateOps.createTemplate(arguments);
}
if ("setTemplateContent".equals(operation)) { //$NON-NLS-1$
    return TemplateOps.setTemplateContent(arguments);
}
```

(вставлено між `deleteFormItem` і `renameObject`; `WriteGate.check()` викликається
всередині `TemplateOps`).

### 3. `EditMetadataTool.help()` — приклад

```java
"createTemplate":{"params":"kind, name, template [, spec, synonym, lang, project, dryRun]",
  "example":{"operation":"createTemplate","kind":"Обработка","name":"МояОбробка","template":"ПечатнаяФорма","spec":{…},"dryRun":true}},
```

### 4. Реєстрація макета в об'єкті через BM (`TemplateOps.createTemplate`)

Схема така сама, як `addChild` у `EditMetadataTool`, тільки колекція — `templates`:

```java
IBmModel model = EdtServices.require(IBmModelManager.class).getModel(project);
String fqn = KindRegistry.canonical(kind) + "." + name;
model.getGlobalContext().execute(new AbstractBmTask<JsonObject>("MCP:PRL createTemplate") {
    public JsonObject execute(IBmTransaction transaction, IProgressMonitor monitor) {
        IBmObject bmObject = transaction.getTopObjectByFqn(fqn);
        EStructuralFeature feature = bmObject.eClass().getEStructuralFeature("templates");
        List<EObject> templates = (List<EObject>) bmObject.eGet(feature);
        // перевірка дубля за Emf.name(existing)
        EObject child = EcoreUtil.create(((EReference) feature).getEReferenceType());
        child.eSet(child.eClass().getEStructuralFeature("name"), template);
        child.eSet(child.eClass().getEStructuralFeature("uuid"), UUID.randomUUID());
        // templateType = SpreadsheetDocument (літерал шукається як "SpreadsheetDocument",
        // потім як "SPREADSHEET_DOCUMENT")
        templates.add(child);
        …
    }
});
```

Далі — файл: папка `<objectFolder>/Templates/<template>` (для `CommonTemplate` —
сам `objectFolder`), файл `Template.mxlx`, вміст `MxlBuilder.buildMxlx(spec)` у UTF-8,
`IFile.create` / `IFile.setContents(…, IResource.KEEP_HISTORY, null)`.

### 5. Що НЕ потрібно міняти

`MANIFEST.MF`, `build.ps1`, `plugin.xml`, `ToolRegistry` — без змін: `MxlBuilder` не
тягне нових пакетів, а `edit_metadata` вже зареєстровано.

## Приклади викликів для живого тесту

Спочатку — завжди `dryRun`, щоб побачити `xmlPreview` без запису:

```json
{"name":"edit_metadata","arguments":{
  "operation":"createTemplate","kind":"Обработка","name":"МояОбробка",
  "template":"ПечатнаяФорма","dryRun":true,
  "spec":{"columns":6,"areas":[
    {"name":"Шапка","type":"Rows","rows":[
      {"cells":[{"text":"Рахунок №","bold":true},{"parameter":"НомерДокумента"}]},
      {"cells":[{"text":"від"},{"parameter":"ДатаДокумента"}]}]},
    {"name":"Рядок","type":"Rows","rows":[
      {"cells":[{"parameter":"Товар"},{"parameter":"Кількість","format":"ЧЦ=15; ЧДЦ=3"}]}]},
    {"name":"Підвал","type":"Rows","rows":[
      {"cells":[{"text":"Разом:"},{"parameter":"Сума","format":"ЧЦ=15; ЧДЦ=2"}]}]}]}}}
```

Реальне створення (без `dryRun`) і перезапис уже наявного макета:

```json
{"name":"edit_metadata","arguments":{"operation":"createTemplate","kind":"Обработка","name":"МояОбробка","template":"ПечатнаяФорма","synonym":"Печатная форма","spec":{"columns":6,"areas":[…]}}}
{"name":"edit_metadata","arguments":{"operation":"setTemplateContent","kind":"Обработка","name":"МояОбробка","template":"ПечатнаяФорма","spec":{…}}}
```

Перевірка результату — тим самим сервером, read-only:

```json
{"name":"get_template","arguments":{"kind":"Обработка","name":"МояОбробка"}}
{"name":"get_mxl","arguments":{"kind":"Обработка","name":"МояОбробка","template":"ПечатнаяФорма"}}
{"name":"get_validation_errors","arguments":{}}
```

Складніший приклад (табличний бланк із рамками, об'єднаннями і форматами):

```json
{"operation":"createTemplate","kind":"Обработка","name":"МояОбробка","template":"Накладна",
 "spec":{"columns":5,"columnWidths":[40,220,60,90,90],"areas":[
   {"name":"Заголовок","type":"Rows","rows":[
     {"height":30,"cells":[{"text":"НАКЛАДНА № [Номер] від [ДатаДок]","bold":true,"align":"Center","span":5}]},
     {"cells":[{"template":"Постачальник: [Постачальник]","span":5,"wrap":true}]}]},
   {"name":"ШапкаТаблиці","type":"Rows","rows":[
     {"cells":[{"text":"№","bold":true,"border":true,"align":"Center"},
               {"text":"Номенклатура","bold":true,"border":true,"align":"Center"},
               {"text":"Кіл-ть","bold":true,"border":true,"align":"Center"},
               {"text":"Ціна","bold":true,"border":true,"align":"Center"},
               {"text":"Сума","bold":true,"border":true,"align":"Center"}]}]},
   {"name":"Рядок","type":"Rows","rows":[
     {"cells":[{"parameter":"НомерРядка","border":true,"align":"Center"},
               {"parameter":"Номенклатура","border":true,"detailParameter":"НоменклатураСсылка"},
               {"parameter":"Кількість","border":true,"align":"Right","format":"ЧЦ=15; ЧДЦ=3"},
               {"parameter":"Ціна","border":true,"align":"Right","format":"ЧЦ=15; ЧДЦ=2"},
               {"parameter":"Сума","border":true,"align":"Right","format":"ЧЦ=15; ЧДЦ=2"}]}]},
   {"name":"Підсумок","type":"Rows","rows":[
     {"cells":[{"text":"Разом:","bold":true,"align":"Right","span":4},
               {"parameter":"ВсьогоСума","bold":true,"align":"Right","format":"ЧЦ=15; ЧДЦ=2"}]}]}]}}
```

Відповідний код друку в 1С:

```bsl
Макет = Обработки.МояОбробка.ПолучитьМакет("Накладна");
ТабДок = Новый ТабличныйДокумент;
ТабДок.Вывести(Макет.ПолучитьОбласть("Заголовок"));
ТабДок.Вывести(Макет.ПолучитьОбласть("ШапкаТаблиці"));
ОбластьРядка = Макет.ПолучитьОбласть("Рядок");
Для Каждого Строка Из Товары Цикл
    ЗаполнитьЗначенияСвойств(ОбластьРядка.Параметры, Строка);
    ТабДок.Вывести(ОбластьРядка);
КонецЦикла;
ТабДок.Вывести(Макет.ПолучитьОбласть("Підсумок"));
```
