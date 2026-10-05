# get_mxl — читання макетів табличних документів (MXL)

Дата: 2026-08-26. Статус: реалізовано, перевірено офлайн на 294 реальних макетах.

## Ключовий висновок дослідження

**EDT не зберігає макети табличних документів у бінарному .mxl.** Починаючи щонайменше
з поточних версій, у вихідниках проєкту EDT табличний документ лежить як
`Template.mxlx` — **XML** у стандартній схемі 1С
`http://v8.1c.ru/8.2/data/spreadsheet` (та сама схема, що й у Template.xml
конфігураторної XML-вивантаження). Перевірено на робочому проєкті
`%USERPROFILE%\git\int\src\cf_edt` (retail, EDT 2025.2): **294 файли `*.mxlx`,
0 файлів `*.mxl`**.

Тому інструмент реалізовано як **потоковий StAX-парсер XML без жодної залежності
від бандлів `com._1c.g5.v8.dt.moxel*`** — ні BM, ні UI, ні нових Import-Package
у MANIFEST.MF не потрібно (`javax.xml.stream` доступний через boot delegation,
як `javax.xml.parsers` у GetSkdTool).

## Дослідження moxel API (для довідки / майбутнього)

Бандли в EDT 2025.2.6 (`C:\Program Files\1C\1CE\components\1c-edt-2025.2.6+4-x86_64\plugins`):

| Jar | Вміст |
|---|---|
| `com._1c.g5.v8.dt.moxel_17.0.0.v202605050943.jar` | EMF-модель (`SpreadsheetDocument`, `Cell`, `Row`, `NamedItem*`, `Merge`), пакети `moxel`, `moxel.content`, `moxel.sheet`, `moxel.api`, `moxel.util`, серіалізатори |
| `com._1c.g5.v8.dt.moxel.ui_17.0.0.v202605050943.jar` | редактор (UI, не потрібен) |
| `com._1c.g5.v8.dt.moxel.ui.extension_2.0.500.v202605050943.jar` | розширення редактора (не потрібен) |

Точки входу читання (javap):

- `com._1c.g5.v8.dt.moxel.MoxelResourceMxlx extends AbstractXmlResource` —
  EMF-ресурс для `.mxlx` (XML): `doLoad(InputStream, Map)`.
- `com._1c.g5.v8.dt.moxel.MoxelResourceMxl extends ResourceImpl` — ресурс для
  легасі-бінарного `.mxl`: файл починається сигнатурою `"MOXCEL" 0x00 0x08 0x00
  0x01 0x00 0x08 0x00`, далі `com._1c.g5.v8.dt.internal.moxel.serializer.MxlSerializer
  .deserializeMxl(IInPersistenceStorage)` (конструктор бере `SpreadsheetDocument` +
  `IDtProject`).
- `com._1c.g5.v8.dt.moxel.util.V8MoxelSerializer.deserializeXML(InputStream[, CompatibilityMode])` —
  читання конфігураторного Template.xml тієї ж схеми в модель (конструктор бере
  `IDtProject` + `SpreadsheetDocument`).
- Навігація по моделі: `MoxelUtil.getCell(SpreadsheetDocument, row, col)`,
  `MoxelUtil.getRowContent`, `moxel.sheet.SheetAccessor` / `NamedItemInfo`
  (обгортки для UI-редактора, тягнуть формат-кеші).

Чому цей шлях НЕ використано: (1) реальні файли — XML, EMF-модель не дає нічого,
чого немає в XML; (2) підключення moxel вимагало б правок MANIFEST.MF/build.ps1
(заборонено в цій ітерації); (3) `MxlSerializer`/`MoxelResourceMxl` — internal
API з залежністю від `IDtProject` (реєстри кольорів/шрифтів/версія платформи).

## Формат Template.mxlx (перевірено на реальних файлах)

Корінь `<document xmlns="http://v8.1c.ru/8.2/data/spreadsheet" xmlns:v8="http://v8.1c.ru/8.1/data/core">`, діти:

- `<height>N</height>` — кількість рядків документа.
- `<columns>` — набір колонок: `<size>` (кількість); додаткові набори мають `<id>`
  (GUID) — рядки/області можуть посилатися через `<columnsID>`. Набір без `id` —
  типовий.
- `<rowsItem><index>R</index><row>…</row></rowsItem>` — рядки (розріджено, index
  0-базований). Усередині `<row>`: `<formatIndex>`, послідовність зовнішніх `<c>`
  (cellsItem): опційний `<i>C</i>` — явний індекс колонки (інакше попередній+1),
  внутрішній `<c>` — комірка:
  - `<f>N</f>` — індекс формату (формат містить fillType Text/Parameter/Template,
    у інструменті не використовується);
  - `<parameter>Ім'я</parameter>` — комірка-параметр (те, що заповнюють
    `Область.Параметры.Ім'я = …`);
  - `<detailParameter>` — параметр розшифровки;
  - `<tl><v8:item><v8:lang>ru</v8:lang><v8:content>текст</v8:content></v8:item></tl>` —
    текст (мультимовний; беремо перший item). У fillType=Template текст містить
    плейсхолдери `[Параметр]`.
- `<merge><r><c><w>(<h>)</merge>` — об'єднання комірок.
- `<namedItem xsi:type="NamedItemCells"><name>…<area><type>Rows|Columns|Rectangle</type>
  <beginRow><endRow><beginColumn><endColumn>[<columnsID>]</area></namedItem>` —
  іменовані області; `-1` у межі = вся розмірність. `xsi:type="NamedItemDrawing"` —
  іменований малюнок (`<drawingID>`).
- `<drawing>`, `<formats>`-подібні `<format>` — малюнки і таблиця форматів (лічимо
  кількість, вміст не розбираємо).

## Реалізація

`<repo>\src\com\polischuk\edt\prl\tools\metadata\GetMxlTool.java`

- name = `get_mxl`; параметри: `project?`, `kind`, `name`, `template`
  (для CommonTemplate не потрібен), `path?` (прямий шлях, як у get_skd),
  `section?` = `areas|cells|all` (типово all).
- Шлях до файлу — `MetadataIndex.objectFolder` (+ `/Templates/<template>`) +
  `/Template.mxlx`, за зразком GetTemplateTool; для легасі `.mxl` — зрозуміла
  помилка з порадою пересохранити макет у EDT.
- Парсер — статичний `parseMxlx(InputStream, section)` (StAX, один прохід,
  DTD/зовнішні сутності вимкнено), незалежний від Eclipse/EDT — його ж ганяє
  офлайн-харнес.
- Результат: `rows`, `columns`, `columnSets[{id,size}]`,
  `areas[{name,type,beginRow,endRow,beginColumn,endColumn,columnsID?,params[]}]`
  (params — зведення параметрів комірок області, `… [tpl]` = плейсхолдер
  шаблонного тексту; Drawing-області з `drawingID`),
  `cells[{row,col,area?,parameter?,detailParameter?,text?,templateParams[]?}]`
  (лише непорожні комірки), `cellCount`, `truncated`, `mergeCount`,
  `drawingCount`, `file`.
- Ліміти: 2000 комірок у відповіді (`truncated:true` + повний `cellCount`),
  300 символів тексту комірки, 100 параметрів на область.
- Плейсхолдери `[…]` визнаються параметрами лише якщо це ідентифікатор 1С
  (`[\p{L}_][\p{L}\p{Nd}_]*`) — відсікає хибні спрацьовування на кшталт
  `[c,e]`/`[2,2,2]` у текстах класифікаторів.

## Офлайн-перевірка (харнес)

Харнес: `scratchpad/mxl/harness/...\MxlHarness.java` (same package, викликає
`GetMxlTool.parseMxlx`), компіляція `--release 17` **усіх** .java проєкту +
харнеса з класпасом за еталоном `$cp` із build.ps1 — OK, без нових залежностей.

Результати на реальних макетах `%USERPROFILE%\git\int\src\cf_edt`:

| Макет | Результат |
|---|---|
| CommonTemplates/ПФ_MXL_БланкВозврата | 68×12, 10 областей (9 Rows + 1 Drawing), params: сДанные=[Номер, Номенклатура, Артикул, Размер, Кол, Цена, Сумма], сИтог=[Итог], сНомерЗаказа=[номер [tpl], датазаказа [tpl]]; 48 комірок, 27 merge, 8 drawing; 152 мс |
| Catalogs/Магазины/…/ПФ_MXL_ГрафикРаботыМагазина | 17×3, області Rows + Rectangle (КолонкаМесяца rows 0–6 col 2, params ИмяМесяца, КалендарныеДни, РабочиеДни…) |
| CommonTemplates/ПФ_MXL_ИНВ19 | 51 рядок, 3 додаткові набори колонок (10/35/15), області з десятками параметрів (Всего: ИтогоРезультатИзлишекКолво…) |
| Catalogs/КлассификаторТНВЭД/… (7.3 МБ) | 11736 рядків, області Columns + Rows, cellCount=23472, truncated, 391 мс |
| Catalogs/ШаблоныЭтикетокИЦенников/…67х52 (2 МБ) | 200×200, 7 комірок, 233 мс |
| **Усі 294 `*.mxlx` проєкту** | **розпарсено без помилок** |

## Обмеження

- Легасі-бінарний `.mxl` (сигнатура `MOXCEL`) не читається — у сучасних проєктах
  EDT таких файлів немає; за потреби шлях — `MoxelResourceMxl`/`MxlSerializer`
  (internal, потребує `IDtProject` і додавання `com._1c.g5.v8.dt.moxel` у
  Import-Package + build.ps1).
- fillType комірки не резолвиться з таблиці форматів; параметри визначаються за
  `<parameter>`/`<detailParameter>` та `[Плейсхолдерами]` шаблонного тексту — цього
  достатньо для аналітики друкованих форм (перекриває і випадок Template-заповнення).
- Текст береться першою мовою `<tl>`; інші мови опускаються.
- Вкладені таблиці (`NamedItemEmbeddedTable`), зведені таблиці, вміст малюнків не
  розбираються (малюнки — лише кількість і іменовані drawingID).

## Інтеграція (що додати при деплої)

1. `ToolRegistry` — зареєструвати `new GetMxlTool()` поруч з GetTemplateTool/GetSkdTool.
2. MANIFEST.MF, build.ps1, plugin.xml — **змін не потребують**.
3. Опційно в описі get_template згадати get_mxl для .mxl-макетів.

Приклад виклику:

```json
{"name":"get_mxl","arguments":{"kind":"CommonTemplate","name":"ПФ_MXL_БланкВозврата","section":"areas"}}
{"name":"get_mxl","arguments":{"kind":"Catalog","name":"Магазины","template":"ПФ_MXL_ГрафикРаботыМагазина"}}
```
