# get_skd — читання структури СКД (Template.dcs)

Дата: 2026-08-26. Статус: інструмент написано (`tools/metadata/GetSkdTool.java`),
скомпільовано, у ToolRegistry **не** зареєстровано (окремий крок деплою).

## Підхід

Файловий, як у `get_form_image`: СКД у вихідниках EDT — це звичайний XML
(`Template.dcs`), його парсимо DOM-ом (`DocumentBuilderFactory`, `namespaceAware=false`,
теги з літеральними префіксами `dcsset:`, `v8:`). EMF/BM-модель
`com._1c.g5.v8.dt.dcs.model.*` не знадобилась — формат файла простий і стабільний.

Шлях до макета: `MetadataIndex.objectFolder(kind, name)` →
`src/<Collection>/<Name>/Templates/<Макет>/Template.dcs`. Виняток —
`CommonTemplate`: макет сам є об'єктом, файл лежить одразу в
`src/CommonTemplates/<Name>/Template.dcs` (у retail таких 4, напр.
`СхемаКомпоновкиПодбораРМК`).

Ознака СКД-макета — наявність файла `Template.dcs` у теці макета (MXL-макети мають
`Template.mxl`). Це дає безкоштовний list-режим без читання `.mdo`.

## Формат .dcs — ключові елементи

Корінь `<DataCompositionSchema>` (ns `http://v8.1c.ru/8.1/data-composition-system/schema`),
прямі дочірні елементи:

| Елемент | Зміст |
|---|---|
| `dataSource` | ім'я + `dataSourceType` (Local) — малоінформативно, пропускаємо |
| `dataSet` | набір даних; `xsi:type`: `DataSetQuery` (є `<query>` — текст запиту з розширеннями `{ВЫБРАТЬ…}`/`{ГДЕ…}`), `DataSetObject` (є `<objectName>`), `DataSetUnion` (вкладені набори — дочірні `<item xsi:type="DataSet…">`) |
| `dataSet/field` | `xsi:type=DataSetFieldField` або `DataSetFieldFolder`; дочірні: `dataPath`, `field` (вихідне поле), `title` (LocalStringType), `valueType/v8:Type`, `role`, `useRestriction`, `appearance` |
| `dataSetLink` | зв'язки наборів: `sourceDataSet`, `destinationDataSet`, `sourceExpression`, `destinationExpression`, іноді `parameter` |
| `calculatedField` | `dataPath`, `expression`, `title`, `valueType`, `appearance` |
| `totalField` | ресурси: `dataPath`, `expression` (напр. `Сумма(Сумма)`), опційно `group` (перелік групувань) |
| `parameter` | `name`, `title`, `valueType/v8:Type` (кілька типів можливо; префікс `d4p1:` = current-config, напр. `d4p1:CatalogRef.Номенклатура`), `value`, `useRestriction`, `expression` (напр. `&Період.ДатаНачала`), `availableAsField` |
| `settingsVariant` | варіант налаштувань: `dcsset:name`, `dcsset:presentation` (LocalStringType), `dcsset:settings` — усередині `dcsset:selection` (вибрані поля), `dcsset:filter`, `dcsset:dataParameters`, `dcsset:order`, `dcsset:outputParameters`, структура `dcsset:item xsi:type=StructureItemGroup/Table/Chart` із `dcsset:groupItems` |
| `template` | внутрішні макети оформлення СКД (полів/групувань) — не покриваємо |

Локалізовані рядки скрізь однакові:
`<title xsi:type="v8:LocalStringType"><v8:item><v8:lang>ru</v8:lang><v8:content>Текст</v8:content></v8:item></title>`.

Статистика по retail (135 файлів Template.dcs у Reports): полів `DataSetFieldField` —
2515, `DataSetQuery` — 155, `DataSetFieldFolder` — 63, `DataSetObject` — 10,
`DataSetUnion` — 6. Тобто ~95% — запитні набори; Object/Union рідкісні, але покриті.

## Інтерфейс інструмента

`get_skd`, параметри:

- `project?` — проєкт EDT (як скрізь);
- `kind?` — вид власника, типово `Report` (аліаси KindRegistry: «Отчет», «Звіт»…);
  працює і для `DataProcessor`, `Catalog`, `Document`, `CommonTemplate` тощо;
- `name` — ім'я об'єкта;
- `template?` — ім'я макета-СКД. Без нього: єдиний СКД-макет — беремо його; кілька —
  беремо той, чиє ім'я починається з «ОсновнаяСхема»/«ОсновнаСхема» (+ у відповідь
  додається `availableTemplates`); якщо «основного» нема — повертаємо перелік
  `templates` із підказкою (list-режим);
- `path?` — альтернатива: прямий шлях до Template.dcs відносно проєкту;
- `section?` — `dataSets | fields | parameters | variants | all` (типово `all`).
  Набори даних **з текстами запитів повертаються завжди**, section лише додає/прибирає
  решту: `fields` → поля наборів + calculatedFields + resources; `parameters` →
  параметри; `variants` → варіанти з деталізацією (selection + groupings зі структури).

Ліміти: загальний бюджет 1200 вузлів (`truncated:true` при вичерпанні, патерн
`get_form_image`), текст одного запиту ≤ 30 000 символів (`queryTruncated:true`),
списки полів у варіантах ≤ 100.

## Що покрито / не покрито

Покрито: набори всіх трьох типів (включно з рекурсією в Union), тексти запитів,
поля з заголовками і типами (префікси типів зрізаються: `d4p1:CatalogRef.Номенклатура`
→ `CatalogRef.Номенклатура`), папки полів, зв'язки наборів, обчислювані поля, ресурси
(totalField + groups), параметри (типи, вирази, useRestriction), варіанти налаштувань
(імена/подання; у section=variants — вибрані поля і групування).

Не покрито (свідомо, поки не запитають): внутрішні `template` (макети оформлення СКД),
`role` полів (вимір/період/рахунок), `appearance`/умовне оформлення, відбори і
параметри даних варіантів у деталях, вкладені схеми (`nestedDataSet`), зовнішні
джерела даних. Редагування СКД — поза скоупом (read-only).

## Смок-тест парсингу (виконано офлайн)

Ядро парсера прогнано окремим харнесом (scratchpad, `SkdParseSmoke.java`) на трьох
реальних схемах retail різної форми — все розібралось коректно:

- `Reports/int_ДлинаЧека` — DataSetQuery, 5 полів, query 1186 симв., 4 ресурси,
  16 параметрів (у т.ч. `Дата1` з `expression=&Период.ДатаНачала`), 1 варіант;
- `Reports/ОбъемНенужныхФайлов` — DataSetUnion із двома вкладеними DataSetObject,
  calculatedField, 3 ресурси;
- `Reports/СведенияОПользователях` — 3 набори (2×Object + Query), 2 dataSetLink,
  3 параметри, 3 варіанти.

## План живого тесту (після реєстрації в ToolRegistry і деплою)

Проєкт workspace: `retail` (РозницаДляКазахстана 2.2.1.15). Кандидати:

1. Базовий: `get_skd {kind:"Report", name:"int_ДлинаЧека"}` — очікуємо 1 набір
   DataSetQuery із повним текстом запиту, 16 параметрів, 4 ресурси, варіант «ДлинаЧека».
2. Кілька макетів: `get_skd {name:"ИтогиИнвентаризации"}` — макети
   `ОсновнаяСхемаКомпоновкиДанных` + `…Ассортимент`: має взятись основний і
   з'явитись `availableTemplates`; потім явно
   `{name:"ИтогиИнвентаризации", template:"ОсновнаяСхемаКомпоновкиДанныхАссортимент"}`.
3. Union/Object: `{name:"ОбъемНенужныхФайлов"}` і `{name:"СведенияОПользователях"}`
   (перевірити items і dataSetLinks).
4. Секції: `{name:"Продажи", section:"parameters"}`, `{..., section:"variants"}`
   (3 варіанти, selection/groupings), `{..., section:"dataSets"}`.
5. Загальний макет: `{kind:"CommonTemplate", name:"СхемаКомпоновкиПодбораРМК"}`.
6. Негативні: звіт без СКД, неіснуючий template — очікуємо зрозумілі помилки.
7. Великий файл: `{name:"Продажи"}` (70 КБ) — перевірити ліміти/truncated.

## Реєстрація (не робилось — заборона правити існуючі файли)

Один рядок у `ToolRegistry`: `register(new GetSkdTool());` поруч із
`GetFormImageTool`, потім `build.ps1` + деплой. Нових залежностей MANIFEST не треба —
використано лише вже імпортовані пакети (resources, emf, gson, javax.xml.parsers).
