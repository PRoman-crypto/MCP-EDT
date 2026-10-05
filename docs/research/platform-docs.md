# Дослідження: довідка платформи 1С в EDT (get_platform_docs)

Дата: 2026-08-25. EDT 2025.2.6 (`C:\Program Files\1C\1CE\components\1c-edt-2025.2.6+4-x86_64`).
Метод: javap + перегляд вмісту jar-ів (сигнатури нижче — дослівно з javap).

## Висновок

Синтакс-помічник платформи в EDT — це бандл **`com._1c.g5.v8.dt.platform.doc`** (~90 МБ),
який містить і **публічний Java-API**, і **самі ресурси довідки**. Це найкращий шлях —
напрямок 1 із завдання; mcore-шлях (напрямок 3) не потрібен: у `com._1c.g5.v8.dt.mcore.Type`
описів немає, лише структура типів, а doc-бандл дає повні сторінки з описами, параметрами
і прикладами обома мовами.

## Ресурси (де лежить довідка)

Бандл `com._1c.g5.v8.dt.platform.doc_3.0.0.v202605050943.jar`:

- `satree.xml` (7.4 МБ) — дерево синтакс-помічника. Вузли:
  `<node id="00029L00001" ru-name="СтрНайти" en-name="StrFind" ru-element=... en-element=...
  v8uri="SyntaxHelperContext/objects/Global context/methods/catalog4838/StrFind4836.html"
  catalog="false">`.
- `nl/ru/html/**`, `nl/en/html/**` — HTML-сторінки:
  - `SyntaxHelperContext/objects/Global_context/methods/...` — глобальні методи;
  - `SyntaxHelperContext/objects/<catalog>/<object>/{methods,properties,events,ctors}/...` — типи платформи;
  - `SyntaxHelperQueries/...` — **мова запитів** (напрямок 4 покритий: напр. `SyntaxHelperQueries/StrFind.html`);
  - `SyntaxHelperLanguage/...` — опис вбудованої мови.
- `nl/ru/saindex/`, `nl/en/saindex/` — готовий **Lucene-індекс** повнотекстового пошуку.

Версійні бандли `com._1c.g5.v8.dt.platform.doc_v8_3_NN` — та сама структура для конкретної
версії платформи; для 8.3.8–8.3.24 це 16-КБ заглушки (loader відкочується на базовий бандл),
повні є для 8.3.25, 8.3.26, 8.3.27, 8.5.1.

HTML сторінок структурований (`div.chapter`: Синтаксис / Параметры / Возвращаемое значение /
Описание / Доступность / Пример; атрибути `data-syntax`, `data-optional`, `data-code`) —
конвертація htmlToText дає читабельний текст із сигнатурою та описами рос.+англ. імен.

## API (Export-Package: `com._1c.g5.v8.dt.platform.doc`)

Ключовий клас — `PlatformDocProvider` (javap):

```
public PlatformDocProvider(internal.PlatformDocLoader, PlatformDocTreeLoader);  // @Inject, клас @Singleton
public PlatformDocTree getTree(Version);
public PlatformDocPage loadPage(String path, Version, String lang) throws IOException;
public IFullTextSearchResults doFullTextSearch(String query, IProgressMonitor, Version, String lang, boolean russianNamesPriority);
public String getNameByPath(Version, String path, String lang);
public PlatformDocTreeNodeId getNodeIdByPath(Version, String path);
public URL getImageSource(Version, String lang, String path);
```

- `PlatformDocTree`: `getRootNode()`, `getNode(String|ShortId)`.
- `PlatformDocTreeNode`: `getName("ru"|"en")`, `getElementName(boolean russian)`, `getPath()`
  (= `v8uri`), `getChildren()`, `getOrderedChildren(lang)`, `getParent()`, `getIsCatalog()`.
- `PlatformDocPage`: `getSyntax()`, `getKind()` (`PlatformDocPageKind`: TYPE/PROPERTY/FUNCTION/…),
  `getValue(key)` з ключами-константами (`title`, `description`, `return`, `availability`, …).
- `IFullTextSearchResults`: `getCount()`, `doSearch(int from, int count, monitor)`;
  `IFullTextSearchResult`: `getTitle()/getPath()/getSummary()`. Пошук = первинний індекс імен
  (`PlatformDocPrimaryIndex`, camelCase-скоринг, ru+en) + Lucene-повнотекст
  (`PlatformDocRuEnLuceneAnalyzer`); boolean-параметр — `isRussianNamesPriority`.

Внутрішній `com._1c.g5.v8.dt.internal.platform.doc.PlatformDocLoader` (НЕ експортується):
`getHtmlInputStream(Version, lang, path)` → ресурс `nl/{lang}/html/{path}` (додає `.html`,
замінює пробіли на `_`), з fallback версійний бандл → базовий; `getInputStream(Version, "satree.xml")`.

## Як отримати екземпляр провайдера

Провайдер **не реєструється в OSGi** (Activator бандла порожній — перевірено дизасемблюванням)
і не є `IManagedService`. Єдиний штатний споживач — `com._1c.g5.v8.dt.bsl.ui`
(`internal...documentation.BslDocumentationProvider`, hover/F1), який отримує його через
**Guice-інжектор Xtext** — ззовні недоступний.

Рішення в `GetPlatformDocsTool` — зібрати провайдер так само, як Guice:

1. `Platform.getBundle("com._1c.g5.v8.dt.platform.doc").loadClass(<internal PlatformDocLoader>)`
   — `Bundle.loadClass` бачить неекспортовані класи власного бандла; конструктор публічний без параметрів.
2. `PlatformDocTreeLoader` (експортований) — конструктор package-private, береться через
   `getDeclaredConstructor(loaderClass)` + `trySetAccessible()` (бандли Equinox живуть в
   unnamed module — доступ дозволений).
3. `new PlatformDocProvider(loader, treeLoader)` — конструктор публічний, клас експортований.

Компільована залежність — тільки експортований пакет `com._1c.g5.v8.dt.platform.doc`;
рефлексія — лише для двох внутрішніх викликів (створення loader і `getHtmlInputStream`).

## Реалізація інструмента

`src/com/polischuk/edt/prl/tools/docs/GetPlatformDocsTool.java` — `get_platform_docs`:

- Параметри: `query` (ім'я або path сторінки), `member?`, `project?`, `lang?` (ru типово).
- Версія платформи: `IRuntimeVersionSupport.getRuntimeVersion(project)` (як у `Types.java`);
  без проєкту — єдиний проєкт workspace або `Version.LATEST`.
- Точний пошук: обхід дерева `satree` за ru/en іменами (нормалізація: регістр, ё→е);
  `member` шукається в піддереві знайденого об'єкта.
- ≤3 збігів → повний текст сторінки (htmlToText з raw HTML — сигнатура, параметри, опис,
  доступність, приклад) + перелік членів об'єкта (методи/властивості/події, ru+en, шляхи).
  Більше збігів → список path-ів (повтор виклику з `query`=path читає сторінку).
- Без точного збігу → `doFullTextSearch` (первинний індекс + Lucene): title/path/summary.
- Провайдер і Lucene-індекси кешуються (лінива статична ініціалізація; сам провайдер кешує
  дерево та індекси по (version, lang)).

## Інтеграція (не зроблено — заборонено чіпати існуючі файли)

1. `META-INF/MANIFEST.MF` → Import-Package додати:
   `com._1c.g5.v8.dt.platform.doc;resolution:=optional`.
2. `ToolRegistry.createDefault()` → `registry.register(new GetPlatformDocsTool());`.
3. `build.ps1` → до `$cp` додати jar
   `com._1c.g5.v8.dt.platform.doc_3.*.jar` (увага: `Resolve-Jar "com._1c.g5.v8.dt.platform.doc"`
   зловить версійні `doc_v8_3_27`-бандли — фільтрувати саме `doc_3.*`).

Компіляцію всього src разом із новим файлом перевірено: javac 17, exit 0
(класпас build.ps1 + platform.doc + xtext/metadata/services.core для нових інструментів
інших сесій — `ValidateQueryTool`, `ExportObjectTool`; їх теж варто додати в build.ps1,
якщо ще не додано).

## Обмеження

- Перше звернення до повнотекстового пошуку відкриває Lucene-індекс (секунди); пошук за
  точним іменем індексу не потребує (тільки дерево, ~7 МБ XML, кешується провайдером).
- `PlatformDocPage`/`PlatformDocPageKind` (структуровані секції) поки не використовуються —
  текст сторінки береться цілком з HTML; за потреби можна додати `getSyntax()`/`getValue(...)`
  для машиночитабельних полів.
- СКД (схема компоновки) окремого розділу в синтакс-помічнику не має — покрита лише мова
  запитів (`SyntaxHelperQueries`) і типи платформи (`СхемаКомпоновкиДанных` як тип).
- Версії 8.3.8–8.3.24: сторінки беруться з базового бандла (остання редакція довідки),
  тобто тексти можуть описувати новішу платформу, ніж у проєкті; `availability`/`available
  since` в тексті сторінки це компенсують.

## План тестів (після деплою)

1. `get_platform_docs {"query":"СтрНайти"}` — 2 збіги (глобальний метод + мова запитів),
   повний текст обох сторінок, сигнатура `СтрНайти(<Строка>, <ПодстрокаПоиска>, ...)`.
2. `{"query":"ТаблицаЗначений"}` — сторінка типу ValueTable + members (Добавить/Найти/…).
3. `{"query":"ТаблицаЗначений","member":"Найти"}` — сторінка методу Найти (Find).
4. `{"query":"ValueTable","lang":"en"}` — пошук за англійським іменем, англійська сторінка.
5. `{"query":"Найти"}` — багато збігів → список path-ів без текстів; повторний виклик із
   одним path → текст сторінки.
6. `{"query":"регулярное выражение"}` — немає точного збігу → searchResults із Lucene.
7. `{"query":"ВЫБРАТЬ"}` / `{"query":"ПОДОБНО"}` — мова запитів.
8. Проєкт із версією 8.3.22 (`project` вказано) — platformVersion у відповіді відповідає
   проєкту, сторінки читаються через fallback на базовий бандл.
