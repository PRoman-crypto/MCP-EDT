# MCP:PRL Server — план робіт

Мета: власний плагін для 1С:EDT, який при старті IDE піднімає локальний HTTP-сервер за стандартом MCP
(Streamable HTTP, JSON-RPC 2.0) і надає AI-клієнтам (Claude Code, Cursor, Windsurf…) інструменти роботи
з конфігураціями 1С. Контракти інструментів — сумісні з набором `1c-rsv`, який очікують агенти titaa.

Цільове середовище: 1C:EDT 2025.2.6 (`C:\Program Files\1C\1CE\components\1c-edt-2025.2.6+4-x86_64`), Java 17.
Збірка: build.ps1 (javac + jar проти jar-ів EDT), пізніше — можлива міграція на Maven/Tycho.

## Milestone 1 — каркас і перше підключення клієнта
- [x] 1.1. Аналіз задачі, огляд дистрибутива MCP:RSV 5.4.0 (референс, код закритий — не використовується)
- [x] 1.2. Перевірка тулчейна (javac 17 є; Maven/pandoc немає → власний build-скрипт)
- [x] 1.3. Скопіювати референс-документацію (tools.html, guide.html) у docs/reference (тільки для внутрішнього використання)
- [x] 1.4. Структура проєкту: META-INF/MANIFEST.MF, plugin.xml, src/
- [x] 1.5. Activator + IStartup (org.eclipse.ui.startup) — старт сервера після старту workbench
- [x] 1.6. HTTP-сервер (JDK com.sun.net.httpserver), біндинг 127.0.0.1, порт із -Dmcp.prl.port (дефолт 8765)
- [x] 1.7. Ядро MCP: initialize / notifications/initialized / tools/list / tools/call / ping; Mcp-Session-Id; перевірка Origin
- [x] 1.8. Каркас інструментів: інтерфейс McpTool + ToolRegistry
- [x] 1.9. Інструменти: show_edt_version, list_workspace_projects (без EDT API — чисті Eclipse resources)
- [x] 1.10. build.ps1 — компіляція та збирання jar
- [x] 1.11. Збірка без помилок; deploy.ps1 → jar лежить у dropins EDT
- [x] 1.12. Перевірено наживо 2026-08-25: сервер стартував у EDT, initialize/tools/list/tools/call відповідають, обидва інструменти повертають коректні дані (workspace `int`, проєкт `retail`)

## Milestone 2 — read-only discovery (EDT API)
- [x] 2.1. Контракти пишемо власні (сумісні за іменами з titaa); tools.html — довідник у docs/reference
- [x] 2.2. Шлюз доступу: EdtServices (OSGi-реєстр), V8Access (IV8ProjectManager), Emf (рефлексивне читання моделі), WorkspaceFiles (файли з кодуванням)
- [x] 2.3. list_workspace_projects через IV8ProjectManager (тип проєкту, ім'я/версія конфігурації)
- [x] 2.4. get_config_properties (атрибути Configuration через EMF)
- [x] 2.5. list_metadata_objects (зведення + список за видом, ru/uk/en псевдоніми), get_object_details (реквізити з типами, ТЧ, форми…); get_object_help — відкладено (потребує md.help API)
- [x] 2.6. list_modules, read_module_source, read_method_source, get_module_structure (легкий BSL-парсер; Xtext-модель — пізніше)
- [x] 2.7. code_search: текстовий/regex пошук по BSL; пошук посилань/ієрархія викликів — у backlog
- [x] 2.8. Жива перевірка 0.2.0 (2026-08-25): усі 10 інструментів працюють на конфігурації РозницаДляКазахстана 2.2.1.15 (907 загальних модулів, 243 довідники); OSGi-сервіси IV8ProjectManager/IBmModelManager доступні

## Milestone 3 — аналітика й валідація (v0.3.0)
- [x] 3.1. ai_context (структура + модулі об'єкта з методами; detail: minimal/standard/full)
- [ ] 3.2. get_platform_docs — backlog (потребує доступу до ІПС платформи, окреме дослідження)
- [x] 3.3. get_validation_errors (маркери проблем EDT, severity/pathFilter, помилки першими); validate_query — backlog (потребує QL-парсер Xtext)
- [x] 3.4. get_form_image (format=structure: дерево елементів із Form.form; PNG — backlog)
- [x] 3.5. get_object_help (HTML-сторінки Help із вихідників → текст)
- [x] 3.6. Виправлено totalMatches у code_search; MetadataIndex (спільний пошук колекцій/об'єктів + шляхи src/)
- [x] 3.7. Деплой при запущеному EDT: jar заблокований — deploy кладе нову версію поруч (singleton → вища), старий jar прибирається після рестарту
- [x] 3.8. Жива перевірка 0.3.0 (2026-08-25): 14 інструментів; get_validation_errors — 464 помилки в конфігурації; ai_context — 9 модулів Номенклатури з експортними методами; get_object_help — повний текст довідки; get_form_image — дерево елементів/реквізити/команди
- [ ] 3.9. Шліфування get_form_image: ім'я елемента брати з дочірнього <name> (зараз null), title парсити як пари key/value (зараз "ru Шапка" одним рядком) — у збірку 0.4.0
- [x] 3.10. Комерційний MCP:RSV 5.4.0 знайдено встановленим у user-області Eclipse (%USERPROFILE%\.eclipse\org.eclipse.platform_4.30.0_*) — блокував старт EDT діалогом ліцензії; користувач видалив через Installation Details; бекап bundles.info у backup/rsv-uninstall

## Milestone 4 — запис (v0.4.1, за прапорцем дозволу)
- [x] 4.1. write_module_source: replaceMethod/replaceLines/append/insertBeforeMethod/insertAfterMethod/replace(+confirmFullReplace); dryRun із превʼю; guard >50% скорочення (allowLargeRemoval), warning >30%; збереження BOM/CRLF; пост-валідація маркерами
- [x] 4.2. diff_module (базлайн на перший запис у сесії, diff префікс/суфікс одним блоком)
- [x] 4.3. WriteGate: запис дозволяється файлом %USERPROFILE%\.edt-mcp\write-enabled (поза досяжністю MCP-клієнта); зараз УВІМКНЕНО
- [x] 4.4. Жива перевірка (2026-08-25): no-op replaceMethod → git byte-identical; EOL-дефект (подвоєння CR) знайдено й виправлено у 0.4.1; diff_module показує +2; guard відбив скорочення на 83%
- [x] 4.5. edit_metadata (v0.5.2): операції help/setProperty/unsetProperty/setSynonym через BM-транзакції; dryRun = нативний executeAndRollback. КЛЮЧОВЕ ВІДКРИТТЯ: запис через model.execute() потрапляє в BM-store, але НЕ серіалізується у файли (розсинхрон!); правильний шлях — model.getGlobalContext().execute() → .mdo оновлюється за ~5 с. Перевірено round-trip: set comment → diff у .mdo → unset → git clean
- [x] 4.6. edit_metadata (v0.6.2): addAttribute/deleteAttribute (у т.ч. в ТЧ), addTabularSection/deleteTabularSection. Типи через IEObjectProvider.Registry.getProxy(platformName) + Types-мапер ru/uk→EN (Строка→String, СправочникСсылка.X→CatalogRef.X); кваліфікатори length/precision/scale. Round-trip перевірено: реквізит Строка(50) і ТЧ із Число(15,2) серіалізуються (EDT сам генерує producedTypes), видалення → git clean. Урок: LinkageError ловиться тільки catch(Throwable) — додано в усі шари; org.eclipse.emf.ecore.util потрібен в Import-Package
- [x] 4.7. export_object (v0.7.0): IExternalObjectDumper (platform.services.core) — XML-експорт у temp + конвертація товстим клієнтом. ПОВНИЙ ЦИКЛ ПЕРЕВІРЕНО 2026-09-04 у Milestone 22 (проєкт зовнішньої обробки створено самостійно, .epf 4562 Б)
- [x] 4.8. get_platform_docs (v0.7.0, паралельний агент): бандл com._1c.g5.v8.dt.platform.doc (~90 МБ, HTML ru/en + Lucene-індекс); PlatformDocProvider збирається вручну (loadClass + trySetAccessible). Перевірено: СтрНайти (повний опис функції), ТаблицаЗначений.Свернуть/GroupBy
- [x] 4.9. validate_query (v0.7.0, паралельний агент): QL-парсер через Xtext-реєстр за розширенням "ql" (без compile-залежності від ql-бандлів); синтаксис без project + семантика з project (з деградацією). Перевірено: валідний запит → true, зламаний → 2 помилки з line/column

## Milestone 4b — раннери тестів і CI-інструменти
- [x] 4b.1. run_vrunner (v0.8.0): allowlist команд (vanessa, xunit, run, init-dev, compile…), пошук vrunner у PATH або vrunnerPath, вивід у CP866, за WriteGate. Перевірено: чемна помилка без OneScript. Живий прогін — після встановлення OneScript+vrunner
- [x] 4b.3. Модель async-джоб (v0.8.0): JobManager (start → jobId, буфер виводу 2МБ) + get_job_status (статус/exitCode/хвіст виводу/stop; без jobId — список)
- [x] 4b.2. Закрито у Milestone 21: `yaxunit_tests` запускає тести напряму через EDT (без OneScript) і розбирає JUnit-звіт

## Milestone 4c — створення top-об'єктів (v0.8.0)
- [x] createObject/deleteObject в edit_metadata: attachTopObject/detachTopObject + реєстрація в колекції Configuration; properties для скалярних властивостей. Перевірено: ОбщийМодуль створено (mdo + запис у Configuration.mdo за 5 с), видалено → git clean
- [ ] Обмеження: Module.bsl для нового загального модуля не створюється автоматично (EDT створює при відкритті редактора) — додати створення порожнього файлу в наступній версії

## Milestone 5 — продукт (v0.8.0)
- [x] 5.1. Сторінка Preferences (Window → Preferences → MCP:PRL): статус сервера, підказка про порт, чекбокс дозволу запису (керує файлом WriteGate, діє одразу)
- [x] 5.2. Індикатор у статус-барі EDT ("MCP:PRL :8765")
- [x] 5.3. Discovery-файл %USERPROFILE%\.edt-mcp\instance-<hash>.json (endpoint, port, workspace, pid; перевірено наживо)
- [x] 5.4. p2 update site БЕЗ Maven/Tycho (publish-site.ps1): feature.xml генерується, headless p2 FeaturesAndBundlesPublisher із самого EDT (java -jar equinox.launcher -application ...) → site/ з features/plugins/content.jar/artifacts.jar. Встановлення: Help → Install New Software → Local → site/. Шум помилок wiring у консолі headless — нормальний, публікація успішна
- [x] 5.5. Документація підключення — README.md

## Milestone 6 — семантика, розширення, відладчик (v0.9.0, три паралельні агенти)
- [x] 6.1. find_references: Xtext builder state (Access.getIResourceDescriptions) + IReferenceFinder — той самий механізм, що штатний Find References EDT. Перевірено: 2276 посилань на СообщитьПользователю з іменами методів-контейнерів
- [x] 6.2. get_call_hierarchy: incoming (індекс + групування) / outgoing (AST Invocation), depth≤3, класифікація цілей. Перевірено outgoing
- [x] 6.3. edit_metadata adoptObject/listAdopted: штатний IModelObjectAdopter (сам відкриває транзакцію — НЕ обгортати в нашу BM-задачу); чемні помилки без CFE-проєктів (перевірено). Живий adopt — коли з'явиться розширення
- [x] 6.4. launch_debugger (скелет): listTargets/set-remove-list Breakpoint/getState/getVariables/stepOver/stepInto/resume/pause на Eclipse Debug model + EDT marker type com._1c.g5.v8.dt.debug.core.bslLineBreakpointMarker; evaluate — наступний етап (механізм описано в docs/research/debugger.md). listTargets перевірено (порожньо + підказка)
- [x] 6.5. list_applications (IApplicationManager), автостворення Module.bsl після createObject CommonModule
- [x] 6.6. Статус-бар: фіксована ширина "● MCP:PRL" (кружечок зелений/червоний, порт у тултіпі) у верхньому тримі — ширина комірки фіксується до старту сервера, тому текст незмінний

## Milestone 7 — швидкі перемоги (v0.10.0, 30 інструментів)
- [x] 7.1. run_application (IApplicationManager.start → процес у JobManager) і update_infobase (update INCREMENTAL/FULL як фонова задача) — перевірені чемні відповіді без підключеної ІБ; живий прогін — після підключення застосунку в EDT
- [x] 7.2. get_subsystem_content: склад підсистеми + дочірні (шлях через крапку). Перевірено: Продажи — 51 об'єкт, 8 дочірніх
- [x] 7.3. get_role_rights: права з Rights.rights + RLS-умови, фільтри/пагінація. Перевірено: АдминистраторСистемы
- [x] 7.4. JobManager розширено: wrapProcess (зовнішній процес) і startTask (фонова внутрішня операція)
- [x] 7.5. Індикатор: computeWidth +12px і хвостовий відступ проти обрізання

## Milestone 8 — UI-поліровка (v0.10.1–0.10.5)
- [x] 8.1. Порт у Preferences: поле з валідацією, застосування одразу (restartInstance без рестарту EDT); пріоритет Preferences → -Dmcp.prl.port → 8765
- [x] 8.2. Індикатор без обрізання — схема з дизасембльованого референса: Composite(GridLayout 2, margin 2/0) + Label-кружечок 14×14 + текстовий Label із ФІКСОВАНИМ GridData.widthHint=80 + isDynamic()=true (CLabel — НЕ використовувати, його ellipsis і різав текст)
- [x] 8.3. Меню по кліку на індикатор: запустити/перезапустити/зупинити сервер, чекбокс дозволу запису (WriteGate), копіювати адресу; enable/checked оновлюються при відкритті

## Milestone 9 — evaluate + локалізація (v0.11.0)
- [x] 9.1. launch_debugger evaluate (паралельний агент): DebugEvaluator — EvaluationRequest/IEvaluationListener, CountDownLatch із таймаутом (дефолт 10 с), декодування byte[] = чистий UTF-8 (підтверджено дизасемблюванням RuntimePresentationConverter); guard frame.isEnabled()+target.isSuspended() (двигун мовчки ігнорує запити інакше). Без debug-сесії — чемна помилка (перевірено). Живий тест — при першій відладці
- [x] 9.2. Локалізація UI (індикатор, меню, Preferences) — uk/ru/en за Platform.getNL(); ваш EDT = uk_UA
- [x] 9.3. Інцидент №2 «агент перезаписав MANIFEST маніфестом із jar-а EDT» — відновлено з контексту; УРОК: у промпти агентів додавати заборону розпакування jar-ів у корінь проєкту

## Milestone 9 — фінальна хвиля функціоналу (v0.12.0, 3 паралельні агенти + інлайн)
- [x] 9.1. edit_metadata renameObject (агент, рівень A): перейменування об'єктів/реквізитів/ТЧ З ОНОВЛЕННЯМ ПОСИЛАНЬ (BSL, форми, RLS) через IMdRefactoringService (headless, dryRun = план без застосування, force для об'єктів на замку). Рядкові літерали не оновлюються (чесний warning)
- [x] 9.2. get_skd (агент): структура СКД з Template.dcs (DOM): набори з ТЕКСТАМИ ЗАПИТІВ (Query/Object/Union), поля, параметри, ресурси, варіанти; парсер верифіковано офлайн на 3 реальних схемах retail (135 .dcs у конфігурації)
- [x] 9.3. code_review (агент): BSL Language Server v1.0.7 (LGPL) зовнішнім процесом, JSON-звіт → summary/full; jar користувач кладе в %USERPROFILE%\.bsl-language-server\ (ліцензійно чисто, без дистрибуції)
- [x] 9.4. get_template (інлайн): список макетів об'єкта з типами; вміст текстових (.dcs/.txt/.xml); .mxl — метаінформація
- [x] 9.5. Збірка 0.12.0 (34 інструменти) задеплоєна ПОРУЧ із працюючим EDT (рестарт не робився на прохання користувача) — активується при наступному рестарті EDT
- [x] 9.6. Живі тести (2026-08-26): get_skd int_ДлинаЧека — 1 набір із текстом запиту/16 параметрів/4 ресурси (як прогнозував агент); renameObject — dryRun-план + реальне перейменування (тека+Configuration.mdo оновились); code_review — чемна помилка з інструкцією без jar; git clean після відкату
- [x] 9.7. Фікс: після deleteObject тека об'єкта з рукотворними файлами прибирається (з очікуванням асинхронної серіалізації; тека видаляється лише коли .mdo уже зник) — у збірці 0.13.0

## Milestone 10 — закриття backlog (v0.13.0, ФІНАЛ)
- [x] 10.1. get_form_image format=image (агент, рівень sketch): PNG-схема компоновки форми з Form.form (SWT offscreen, UI-тред) — перевірено: ФормаЭлемента Номенклатури 752×2572, повністю читабельна (панелі, вкладки, поля, таблиці). Рівень WYSIWYG (FormWysiwygViewer + NativeRenderService) — досліджено, план у docs/research/form-png.md
- [x] 10.2. edit_metadata form-операції (агент): addFormField/addFormCommand/addFormGroup/deleteFormItem через штатний IFormItemManagementService (id/імена/тултіпи — автоматично). FQN форм: <Owner>.Form.<Ім'я>.Form. Перевірено наживо: dryRun поля (авто-ім'я Комментарий1), реальна група → серіалізація у Form.form → deleteFormItem → git clean. Нюанс: у форм списку головний реквізит «Список», не «Объект»
- [x] 10.3. Copyright-заголовок (захист від копіювання) в усіх 60 модулях (add-headers.ps1, ідемпотентний)
- [x] 10.4. deleteObject прибирає теку об'єкта (фікс 9.7)
- [x] 10.5. p2 Update Site 0.13.0 опубліковано (publish-site.ps1, headless p2 publisher EDT)

## Milestone 11 — топ-3 розширення (v0.14.0, перевірено наживо)
- [x] 11.1. get_mxl (агент): читання макетів табличних документів. ВІДКРИТТЯ: EDT зберігає їх як XML Template.mxlx (не бінарний mxl) — StAX-парсер без нових залежностей; офлайн-прогін на всіх 294 макетах retail без помилок. Живий тест: БланкВозврата — 10 областей
- [x] 11.2. edit_metadata batch: список транзакційних операцій ОДНІЄЮ атомарною BM-транзакцією (рефакторинг: applyTransactionalOperation). Живий тест: довідник+реквізит+ТЧ одним викликом → git-чистий цикл; помилка кроку відкочує все
- [x] 11.3. edit_metadata addFormHandler: композит команда+кнопка+каркас &НаКлиенте-обробника в модулі форми (FormHandlerOps; ідемпотентний, створює Module.bsl за потреби). dryRun перевірено
- [x] 11.4. Дистрибутив оновлено до 0.14.0 (site/ + MCP-PRL-Server-0.14.0-distr.zip)

## Milestone 12 — створення макетів друкованих форм (v0.15.0, перевірено наживо)
- [x] 12.1. MxlBuilder (агент): генерація Template.mxlx із компактного JSON-spec — області Rows/Columns/Rectangle, текст, параметри, шаблонний текст [Параметр], merge, ширини колонок, bold/align/format. Без нових залежностей (рядковий серіалізатор). ЗНАХІДКИ ФОРМАТУ: merge w/h = ДОДАТКОВІ колонки/рядки; formatIndex 1-базований; font/border 0-базовані; формат-рядок у вкладеному <format>, не <mask>
- [x] 12.2. TemplateOps: createTemplate (реєстрація Template у метаданих через BM + запис Template.mxlx) і setTemplateContent (перезапис вмісту); dryRun повертає XML-прев'ю
- [x] 12.3. Живий round-trip: створено обробку → макет із 3 областей (Шапка/Строка/Підвал) → прочитано власним get_mxl: 3 області з правильними межами, всі параметри й тексти на місцях → get_validation_errors: 0 помилок по об'єкту → deleteObject → git clean

## Milestone 13 — СКД: створення і редагування (v0.16.0, перевірено наживо)
- [x] 13.1. SkdBuilder (агент): генерація Template.dcs зі spec — набори Query/Object/Union, поля (з автопарсером колонок із тексту запиту: псевдоніми КАК, крапкові шляхи, РАЗРЕШЕННЫЕ/ПЕРВЫЕ, пакетні запити), dataSetLink, обчислювані поля, ресурси, параметри, варіанти. Плюс setDataSetQuery — рядкова заміна тексту запиту зі збереженням решти XML символ у символ
- [x] 13.2. TemplateOps узагальнено під два формати: templateFormat=spreadsheet|dcs (тип макета в метаданих — SpreadsheetDocument / DataCompositionSchema); setTemplateContent визначає формат за наявним файлом; нова операція setDataSetQuery
- [x] 13.3. Живий цикл: створено звіт → схема СКД (запит+ресурс+параметр+варіант) → get_skd прочитав усе (поля з автопарсера, ресурс, параметр StandardPeriod, варіант) → setDataSetQuery замінив запит, решта схеми ціла → 0 помилок валідації → deleteObject → git clean
- Нюанс API get_skd (не дефект): поля повертаються в ключі dataPath, варіанти — в settingsVariants; section=dataSets не включає поля (потрібен all/fields)

## Milestone 14 — get_validation_errors: канал перевірок EDT (v0.16.1)
- Дефект: інструмент читав лише Eclipse `IMarker.PROBLEM` (синтаксис Xtext) — на `cfe` 15 маркерів проти 2566 у референса; всі як `error`, без checkId. Результати фреймворку checks (якість коду, семантика BSL, форми, метадані, розширення) EDT тримає в окремому сховищі `IMarkerManager` (`com._1c.g5.v8.dt.validation.marker`, managed service `MARKER_MANAGER` — доступний з реєстру OSGi так само, як IV8ProjectManager).
- [x] 14.1. Другий канал: `IMarkerManager.markers(MarkerFilter.createProjectFilter(project))`; severity ERRORS/BLOCKER/CRITICAL/MAJOR→error, MINOR→warning, TRIVIAL→info, NONE відкидається; шлях BSL-маркера — `getMarkerObjectId()` = платформний шлях `/proj/src/...`; рядок/offset/length — з `getExtraInfo()` (`line`, `offset`, `length`); шлях BM-маркера (.mdo/.form/.dcs) — ліниво через `provideObject` → `EcoreUtil.getRootContainer` → `IResourceLookup.getPlatformResource`, кеш по topObjectId, лише для сторінки/pathFilter.
- [x] 14.2. Дедуплікація каналів (шлях+рядок+текст, пріоритет EDT — має checkId); нові параметри `checkId`, `source=all|edt|eclipse`, `offset`; у відповіді `channels{edtChannelAvailable, edtMarkers, eclipseMarkers, dedupedDuplicates}`, `matched`, `hasMore`; у записах `severityNative`, `source`, `checkId`, `location`, `object`, `markerObjectId`.
- [x] 14.3. Канал EDT загорнутий у catch(Throwable) — при дрейфі API падає лише канал, Eclipse-маркери повертаються з `hint`. `show_edt_version.edtServices` показує IMarkerManager/IResourceLookup.
- [ ] 14.4. Живий прогін після рестарту EDT: `cfe` ≈ 2.5 тис. маркерів (98 error / 1264 warning на .bsl), pathFilter по модулю, checkId=SU31.

## Milestone 15 — паритет із RSV: дефекти 1–5 (v0.16.2)
Джерело: `docs/research/rsv-parity-2026-09-03.md`.

- Корінь дефектів 1–2 — **холодний старт інфраструктури**, а не логіка перевірки: ті самі запити,
  що на початку сесії давали `valid:true` для неіснуючого поля і «таблиця не знайдена» для
  існуючої, через годину роботи EDT дають правильні відповіді. Два незалежні механізми:
  (а) `IResourceServiceProvider.get(IResourceSetProvider.class)` повертає null, поки UI-бандл мови
  не піднятий → NPE → мовчазна деградація до синтаксису; (б) до синхронізації BM-моделі скоуп
  порожній → кожна таблиця «не знайдена».
- [x] 15.1. `EdtResourceSets` — ResourceSet проєкту без UI-сервісу: `BmAwareSynchronizedXtextResourceSet(IDtProject, IBmModelManager)` з бандла `com._1c.g5.v8.dt.bm.xtext` (headless, прив'язаний до BM-моделі). Запасний шлях — старий `IResourceSetProvider`. Для BSL порядок зворотний (`forProjectPreferLanguage`): робочий шлях лишається штатним.
- [x] 15.2. `validate_query`: перед перевіркою `IBmModelManager.waitModelSynchronization(project)`; ресурс створюється фабрикою мови (`IResourceFactory`), а не `resourceSet.createResource`; спроба по черзі на BM-aware і мовному наборі.
- [x] 15.3. **Ніякої мовчазної деградації**: якщо задано `project`, а семантика не піднялась — інструмент падає з поясненням (як отримати лише синтаксис) замість неправдивого `valid:true`.
- [x] 15.4. `validate_query`: `isDcs=true` → мова `qldcs` (розширення зареєстроване бандлом `com._1c.g5.v8.dt.ql.dcs.ui`); у помилках додано `severity`, `offset`, `length`, `code`, лічильники (дефекти 3 і 14).
- [x] 15.5. `find_references` і `get_call_hierarchy`: замість `findAllReferences` по всьому індексу — `findReferences(targets, Set<URI>, …)` по ресурсах проєкту цілі та проєктів, що від нього залежать (`IDtProjectManager.getDependentProjects`). Параметр `scope=auto|project|workspace`; у відповіді `scope{mode, projects, scannedResources, searchMs}` (дефекти 4–5).
- [x] 15.6. Живий прогін на холодній EDT (0.16.2): дефект 1 ЗАКРИТО (неіснуюче поле → «Поле не найдено» з `code:"Field not found"`, більше не `valid:true`); дефект 3 ЗАКРИТО (`isDcs=true` дає 1-в-1 те саме, що RSV); дефекти 4–5 — сам пошук посилань 53–58 с → **54–620 мс**, кількість посилань не втрачена (12/23/1). Дефект 2 на холодному `cf` ВІДТВОРИВСЯ: перший виклик «Таблица не найдена», другий — валідно.
- Заміри 0.16.2 показали, що після фікса пошуку левову частку часу (~4.5 с із 4.7 с) з'їдає побудова області: `resourceUrisOfProjects` перебирав увесь індекс воркспейсу.

## Milestone 15b — доведення дефекту 2 і вартість області (v0.16.3)
- [x] 15b.1. Детермінований сигнал готовності скоупа: `IDerivedDataManagerProvider` (OSGi) → `IDerivedDataManager.isAllComputed()` / `waitImportantDataComputations(timeout)`. Модель таблиць БД — саме derived data; поки вона не порахована, скоуп порожній.
- [x] 15b.2. `validate_query`: чекаємо derived data (15 с); якщо серед помилок є `code:"Table not found"`, а скоуп не підтверджений — чекаємо ще (60 с) і перевіряємо вдруге; якщо і тоді не підтверджений — падаємо з поясненням замість хибної «таблиця не знайдена». У відповіді `scopeReady`.
- [x] 15b.3. Область пошуку посилань будується обходом дерева файлів (`IResourceProxyVisitor`, `*.bsl`), а не перебором індексу; відкат на індекс, якщо обхід порожній.
- [x] 15b.4. Живий прогін на холодній EDT (0.16.3) — усі п'ять дефектів закрито:
  - дефект 2: перший же виклик на холодному `cf` → `valid:true`, `scopeReady:true`, 1515 мс (RSV — 1918 мс);
  - дефект 1: неіснуюче поле → «Поле не найдено» з `code`, `offset`, `length`;
  - дефект 3: `isDcs` дає ті самі три діагностики, що RSV;
  - дефекти 4–5 (прогріте): `find_references` по об'єкту 312 мс і 977 мс (було 58 000 і 53 000), по методу 328 мс (було 5 200); `get_call_hierarchy` 397 мс проти 5 524 мс у RSV — тут ми швидші в 14 разів;
  - `scope=auto` знаходить 12 посилань, `scope=workspace` — 11: обхід лише по `*.bsl` нічого не губить (навпаки, додає посилання з ресурсу самої цілі);
  - Milestone 14 підтверджено: `edtMarkers: 2877`, `eclipseMarkers: 15` — точно як у RSV.
- УРОК: не видаляти jar зі старої версії з user-area dropins під запущеною EDT — `bundles.info` посилається прямим шляхом, і незавантажені класи дають `NoClassDefFoundError`.

## Milestone 16 — паритет: дефекти 6, 8, 10 (v0.17.0)
- [x] 16.1. `get_object_details`: для запозиченого об'єкта розширення додано `baseObject` — проєкт, ім'я, синонім і склад об'єкта базової конфігурації (реквізити з типами, ТЧ, форми, команди, макети). Зв'язок — атрибут `extendedConfigurationObject` (uuid), пошук по конфігураціях інших проєктів workspace. Якщо базової конфігурації в workspace немає — `found:false` із підказкою.
- [x] 16.2. `get_object_help`: збирає довідку не лише об'єкта, а й усіх його форм (обхід теки об'єкта, файли всередині будь-якої `Help`); у кожній сторінці `scope=object|form` і `form`; у відповіді додано `kind`, `name`, `synonym`, `comment`, `pageCount`.
- [x] 16.3. `code_search`: параметр `symbol` — «де це визначено». Приймає `Модуль.Метод`, `Kind.Ім'я.Метод` або саме ім'я методу; спершу адресний шлях до модуля, потім обхід. Повертає `definitions[]` із проєктом, шляхом, сигнатурою, межами рядків, тілом і doc-коментарем. `query` став необов'язковим (потрібен або `query`, або `symbol`).
- [x] 16.4. Живий прогін (0.17.0) — усі три закрито: `baseObject` для `Catalog.ВидыНоменклатуры` у `cfe` віддав проєкт `cf`, 94 реквізити з типами, 6 ТЧ, 7 форм, 1 команду (32 767 симв. проти 21 123 у RSV — тепер ми повніші); `get_object_help` на `Document.ЧекККМ` — 3 сторінки (дві формові + об’єктна), як у RSV; `code_search symbol` резолвить і `Модуль.Метод`, і саме ім’я методу, з сигнатурою, межами 34–36 і doc-коментарем.
- Спостереження: перші секунди після рестарту EDT `list_workspace_projects` віддає 2 проєкти із 17 — виклики по решті падають «Проєкт не знайдено». Не дефект, але перший виклик після рестарту треба повторити.

## Milestone 17 — паритет: дефекти 11, 9 (v0.17.1)
- [x] 17.1. `BslOutline.Method` доповнено: `parameters` (ім'я, `Знач`, значення за замовчуванням), `docComment` (суцільний блок `//` над методом, з урахуванням директив), `region` (найглибша `#Область`, що містить метод). Розбір параметрів іде по верхньому рівню дужок і поза рядковими літералами — інакше кома в `= "а,б"` або `Новий Массив(1,2)` рвала б параметр навпіл.
- [x] 17.2. Типи й описи параметрів підтягуються з doc-коментаря стандарту 1С (`// Параметры:` → `//  Имя - Тип - опис`); секції `Возвращаемое значение:` тощо закривають блок параметрів.
- [x] 17.3. Спільний серіалізатор `BslMethodJson.describe` — однаковий набір полів у `get_module_structure`, `read_method_source` і `code_search(symbol)`; у `code_search` прибрано власний дубль читання doc-коментаря.
- [x] 17.4. `ai_context`: параметр `form` — контекст форми (дерево елементів через `GetFormImageTool` + модуль форми з методами). Модулі об'єкта тепер віддаються повною структурою методів (ім'я, вид, export, сигнатура, директиви, межі, параметри, doc, область) замість самих сигнатур; хінт «тіла — окремим викликом» замінено на корисні напрямки.
- [x] 17.5. Для модуля форми без `full` окремий хінт: обробники не експортні, тож порожній `exportMethods` більше не виглядає як «методів немає».
- [x] 17.6. Живий прогін (0.17.1): `get_module_structure` на `ОписаниеТипаЧисло` віддав `parameters` з типами і описами з doc-коментаря, сам `docComment` і `region: ОписаниеТипов`; `ai_context` із `form=ФормаЭлемента` — `formStructure` + модуль форми з обробником `ISB_ПриСозданииНаСервереПосле` (`&НаСервере`, неекспортний).

## Milestone 18 — паритет: дефекти 12, 15 (v0.17.2)
- [x] 18.1. `list_metadata_objects`: у кожного об'єкта `fullName`, `uuid`, лічильники `attributesCount`/`tabularSectionsCount`/`formsCount`/`commandsCount` і `hierarchical` — «вагу» об'єкта видно без `get_object_details`. Порожні колекції не виводяться.
- [x] 18.2. `list_modules`: `sizeBytes`, `linesCount`, `ownerObjectType`; фільтри `moduleType`, `objectType` (приймає однину й множину), `objectName`; `sortBy=path|size|lines`; `compact=true` — рядок `шлях;тип;байт;рядків`; у відповіді `hasMore`. Збіги збираються повністю до сортування, інакше `sortBy` впорядковував би лише сторінку.
- [x] 18.3. `get_form_image` (structure): `subtree` (обхід однієї групи), `depth`, `maxElements`; у відповіді `totalElements`, `returnedElements` і хінт зі способами звузити обхід. Раніше при впиранні в ліміт було саме лише `truncated:true` без орієнтира.
- [ ] 18.4. Живий прогін після рестарту EDT.

## Milestone 19 — дефект 7: компактна карта СКД (v0.17.3)
Порівняння на `Report.ISB_СверкаПоИмпорту` (`cfe`): наш `get_object_details` — 474 символи (лише
імена макетів), RSV — 2 259 (додає `dcsTemplates`). Повні дані в нас є в `get_skd` (12 708 симв.,
проти 12 495 у RSV із `dcsInclude`) — тобто **можливість не була втрачена, втрачена була економія**:
агент, який питає «що це за звіт», не бачив нічого про схему і мусив тягнути 12.7 КБ.
- [x] 19.1. `DcsSummary` — окремий легкий парсер Template.dcs: ім'я макета, `isMain`, набори даних із `fieldCount` і `queryLength` (без самих текстів), імена й типи параметрів, кількість підсумкових і обчислюваних полів, імена варіантів налаштувань.
- [x] 19.2. `get_object_details` додає `dcsTemplates` + `dcsHint` (куди йти за повною схемою). `get_skd` не дублюється — свідомо: важкі дані лишаються за окремим викликом.
- [x] 19.3. Зіпсований або нечитний макет не ламає деталі об'єкта — у карті з'являється поле `error`.
- [x] 19.4. Живий прогін (0.17.3) — 18 і 19 закрито:
  - `list_metadata_objects`: `fullName`, `uuid`, `hierarchical`; порожні колекції не виводяться;
  - `list_modules` `sortBy=lines` впорядкував правильно (3593 / 3570 / 1644 рядків) із `linesCount`, `sizeBytes`, `hasMore`; `compact` дає рядок на модуль;
  - `get_form_image`: `depth=2` → `totalElements: 256, returnedElements: 8`; `subtree=СтраницаОсновное` → 60/20 із хінтом;
  - `dcsTemplates` на `Report.ISB_СверкаПоИмпорту`: 1 207 симв. замість 474, цифри збігаються з RSV один в один (`fieldCount: 17`, `queryLength: 9231`, 3 параметри, `totalFieldCount: 3`).

## Milestone 20 — дефект 13: diff_module проти git (v0.18.0)
Новий контракт: базлайн може братись із git, а не лише з сесії.
- [x] 20.1. `GitBaseline` — читання версії файлу через git CLI (`rev-parse --show-toplevel`, `show <ref>:<шлях>`): без JGit, процес без оболонки, тож шляхи з кирилицею і пробілами проходять як є; BOM зрізається; «файла немає в ref» відрізняється від помилки git за текстом stderr.
- [x] 20.2. `LineDiff` — справжній порядковий diff: зріз спільного префікса/суфікса, далі LCS на середині; при завеликій середині (стеля 4 млн комірок) — один блок, і це чесно позначено в `algorithm`. Unified-вивід із контекстом 3 і склеюванням близьких hunk-ів.
- [x] 20.3. `diff_module`: `against=git` (типово, `ref` — типово HEAD) або `against=session` (стара поведінка); `mode=summary|unified|methods`. У відповіді `refCommit` (хеш + заголовок коміту), `repositoryPath`, `algorithm`, лічильники рядків.
- [x] 20.4. `summary` порівнює методи за тілами: `addedMethods` / `changedMethods` / `removedMethods` зі startLine/endLine/region. Якщо змін у межах методів немає, а рядки різні — окрема нотатка, що правки поза тілами (шапка, коментарі).
- [x] 20.5. Живий прогін (0.18.0): на незміненому модулі — «змін немає» з `refCommit`; на `УправлениеСвойствамиПереопределяемый` проти `ee988d2c1~1` — **21 додано / 21 видалено, один в один із `git diff --stat`**, `algorithm: lcs`, `changedMethods` вказав саме змінений метод; `mode=unified` дав коректні hunk-и з контекстом; `against=session` повернув чесне «модуль не змінювався в цій сесії». Побічно: RSV на тому самому файлі досі віддає `isNewFile:true` — його git-детект не працює.

## Milestone 21 — прогалини складу: yaxunit_tests і rebuild_project (v0.19.0)
- [x] 21.1. `rebuild_project`: clean build одного проєкту або всіх відкритих; `rebuildAfterClean` — повна пересборка одразу; `confirmed=false` (типово) лише показує, що буде очищено. Виконується асинхронною джобою (`JobManager.startTask`) — на типовій конфігурації clean триває хвилини, синхронний виклик MCP обірвався б по таймауту.
- [x] 21.2. `yaxunit_tests`: формат файла параметрів звірений із самим YAxUnit у workspace (`ЮТФабрика.ПараметрыЗапуска`, `ЮТПараметрыЗапускаСлужебный`), а не вгаданий: ключ запуску `RunUnitTests=<json>`, у файлі `reportPath`, `reportFormat: jUnit`, `closeAfterTests`, `showReport`, `filter{extensions, modules, suites, tags, contexts, tests}`, `logging{file, console, level}`. Порожні фільтри не пишуться — YAxUnit розрізняє «немає фільтра» і «порожній список».
- [x] 21.3. Запуск — рідним шляхом EDT: `RuntimeExecutionArguments.setStartupOption("RunUnitTests=…")` у `ExecutionContext` під ключем `IApplication.CONTEXT_CLIENT_ARGUMENTS`, далі `IApplicationManager.start`. Тип значення встановлено по LocalVariableTable `RuntimeClientLaunchDelegate` (це `RuntimeExecutionArguments`, а не рядок чи список). Не треба ані шукати 1cv8.exe, ані складати рядок з'єднання.
- [x] 21.4. `updateBeforeLaunch` (типово true) — без оновлення ІБ 1С показує модальне вікно про застарілу конфігурацію і прогін зависає назавжди. Розбір JUnit-звіту: підсумки, набори, до 50 падінь із повідомленням і стектрейсом. `wait=false` — одразу jobId.
- [x] 21.5. Живий прогін ВІДБУВСЯ (2026-09-04, ІБ `amadeo` підключена до проєкту `cf`): `yaxunit_tests` із фільтром `extensions=YAXUNIT, modules=Спр_ПредопределенныеЭлементы` — **64 тести, 58 пройдено, 4 failure, 2 error, 2.7 с**. Весь ланцюг: згенерований run-settings.json → старт 1С через EDT → YAxUnit прогнав фільтр → report.xml → розібраний JUnit із повідомленнями і стектрейсами. Падіння — реальні розбіжності в даних бази (предопределённые елементи), не дефекти інструмента.
- [x] 21.6. ДЕФЕКТ, знайдений живим прогоном: `ExecutionContext` вимагає ще й `CONTEXT_CLIENT_TYPE` (id типу компоненти: `...componentTypes.ThinClient` / `ThickClient`), інакше `IApplicationManager.start` падає з «Контекст выполнения не предоставляет тип клиента для запуска». Той самий дефект був у `run_application` з v0.10.0 — він завжди передавав порожній контекст, тобто НІКОЛИ не міг запустити базу; не виявлявся, бо ІБ не була підключена. Виправлено в обох (v0.19.1), доданий параметр `clientType`.

## ВІДКЛАДЕНО: індикатор у тримі (сага 0.10.x–0.11.13)
Текст у нижньому тримі цього EDT обрізається/ховається попри повне відтворення схем двох
робочих референсів (MCP:RSV і 1C Workmate: від'ємні margin -5, текстовий Label, прозора іконка).
Діагностика: трим-рядок рендериться 7px. Поточний стан 0.11.13 — структура 1:1 з Workmate.
Меню і кружечок працюють; напис — повернутись пізніше (можливо, специфіка теми/DPI користувача).

## Backlog
- get_form_image: PNG-рендер форми
- code_search: пошук посилань, ієрархія викликів (Xtext-індекс)
- write_module_source: створення нового модуля (файл відсутній)
- edit_metadata: форми/команди/макети, СКД
- export_object: живий тест із проєктом зовнішньої обробки і пов'язаною ІБ
- run_yaxunit + парсинг JUnit-звіту (після OneScript)

## Зафіксовані рішення
- Назва плагіна: **MCP:PRL Server**; bundle `com.polischuk.edt.prl.server`, версія 0.1.0.
- HTTP: JDK httpserver (без залежностей); якщо Equinox не віддасть пакет — fallback на Jetty з платформи.
- JSON: Gson (бандл `com.google.gson` 2.10.1 є в EDT), Jackson не використовуємо.
- Усі EDT-пакети в Import-Package — `resolution:=optional` (плагін не падає при дрейфі API).
- Імена інструментів — як у `mcp__1c-rsv__*` (сумісність із titaa).
- Запис у модель 1С — вимкнений за замовчуванням, вмикається явно.

## Milestone 22 — export_object: власний проєкт зовнішньої обробки (v0.20.0)
Живий цикл `export_object` з Milestone 4.7 два тижні висів через відсутність проєкту зовнішніх
обробок. Проєкт створено самостійно, поза git-репозиторієм користувача.
- [x] 22.1. Структуру взято з наявного проєкту (`git/ka-int-4edt/src/epf/ExtDataProcessors`), а не вигадано: `.project` із натурами `org.eclipse.xtext.ui.shared.xtextNature` + `com._1c.g5.v8.dt.core.V8ExternalObjectsNature`, `DT-INF/PROJECT.PMF` (`Runtime-Version`, `Base-Project: cf` — прив'язка до конфігурації, від якої йде ІБ), `.settings`, `src/ExternalDataProcessors/<Ім'я>/<Ім'я>.mdo` + `ObjectModule.bsl`.
- [x] 22.2. Проєкт `PRL_ExportTest` створено в `%USERPROFILE%\EDT\external\` — свідомо поза `%USERPROFILE%\git\ka-amadeo-4edt`, щоб не додавати сміття в репозиторій користувача.
- [x] 22.3. Новий інструмент `import_project` — підключення наявного каталогу до workspace (аналог File → Import → Existing Projects). Без нього створений ззовні проєкт EDT просто не бачить: Eclipse не сканує диск. За WriteGate (змінює склад workspace).
- [x] 22.4. Живий прогін (0.20.0) — ЛАНЦЮГ ЗАМКНУТО: `import_project` підключив каталог (natures правильні, `v8ProjectType: ExternalObjectProject`); `get_validation_errors` по новому проєкту — **0 помилок**, тобто зібраний вручну `.mdo` EDT прийняла; `export_object` зібрав **PRL_ТестExport.epf, 4562 Б, сигнатура `FF FF FF 7F`** (бінарний контейнер 1С). Пункт 4.7 із Milestone 4, що висів із 2026-08-25, закрито.


## Milestone 23 — структурні метадані в edit_metadata (v0.21.0)
Закриває прогалини «блоку 1»: виміри/ресурси регістрів, ссылочные типи, права ролей, склад підсистем і планів обміну.
- [x] 23.1. `addDimension`/`addResource` + загальні `addItem`/`deleteItem`/`setItemProperty`/`setItemType` (будь-яка containment-колекція; помилка перелічує доступні); `properties` при створенні елемента; enum-властивості приймають ім'я чи літерал без урахування регістру.
- [x] 23.2. Типи: `ЛюбаяСсылка`/`AnyRef`, `СправочникСсылка` без імені (усі довідники) та ін.; кваліфікатори `dateFractions`, `nonNegative`.
- [x] 23.3. Склад підсистем (`add/removeSubsystemContent`, вкладені — через крапку) і планів обміну (`add/removeExchangePlanContent`, `autoRecord`).
- [x] 23.4. `setRoleRights` — права ролі через `RightsModelUtil`/`IRightInfosService` у BM-транзакції (RLS не чіпає); нові Import-Package `dt.rights*`.
- [x] 23.5. Усе транзакційне: працює в `batch` і з `dryRun`. Збірка 0.21.0 компілюється.
- [x] 23.6. ЖИВИЙ ПРОГІН (0.21.7, проєкт cf): dryRun усіх операцій; реальне застосування — регістр з виміром зі ссылочним типом (через MdRefType.type), ресурс, реквізит, склад підсистеми, роль з правами (RoleDescription створюється разом з роллю; права, у т.ч. на вимір, пишуться в Rights.rights), видалення без залишків. Знайдені й виправлені дефекти: ссылочные типи (0.21.4), опис прав нової ролі (0.21.5), роль потрапляла в defaultRoles (0.21.6-0.21.7).
- [x] 23.7. (0.22.0-0.22.5) Автозаимствование ссылочных типів у розширенні: перед основною транзакцією заимствуються потрібні об'єкти бази (adopt відкриває власну транзакцію), далі чекаємо, поки обчисляться згенеровані типи (опитування executeReadonlyTask); у dryRun - wouldAdopt. ПЕРЕВІРЕНО на cfe: int_ВидыОплат заимствувався, тип резолвиться, повтор бачить його наявним.
- [x] 23.8. Очищення форм після deleteAttribute/deleteTabularSection/deleteItem(attributes): прибираються поля/таблиці, dataPath яких = [головний реквізит, ...]; cleanupForms:false вимикає. Перевірено dryRun (Номенклатура.Артикул - 4 форми). Реальний прогін на формах не робився (змінював би існуючі об'єкти cf).
- [x] 23.9. addAccountExtDimensionType/removeAccountExtDimensionType (ПЕРЕВІРЕНО: запис у .mdo, видалення повертає файл у початковий стан).
- [x] 23.10. XDTO: setXdtoNamespace, addXdtoObjectType, addXdtoValueType, addXdtoProperty, removeXdtoType, removeXdtoProperty. Package - окремий top-об'єкт XDTOPackage.<Ім'я>.Package (прикріплення обов'язкове); серіалізація Package.xdto з затримкою ~5-10 с. deleteObject тепер відкріплює Package. ПЕРЕВІРЕНО: файл Package.xdto коректний, видалення чисте.
- [ ] 23.11. Не зроблено: addEnumValue-пакет, автозаимствование в deleteObject, реальний тест очищення форм.

## Milestone 24: дефекти TG-4 і TG-6 із тікета INITKZ-1528 (0.22.6, зібрано, НЕ перевірено наживо)

- [x] 24.1. TG-4: `addSubsystemContent`/`removeSubsystemContent` та інші операції над вкладеною підсистемою. Причина: у EDT вкладена підсистема - не containment батька (у `getEAllContainments` лише synonym/explanation), а `findChild` вимагав containment. Тепер `StructureOps.resolveSubsystem`: список `subsystems` батька без вимоги containment, далі BM-FQN `Subsystem.Батько.Subsystem.Дитина`; роздільники `.` і `/`, службовий сегмент `Subsystem` відкидається; коротке унікальне ім'я вкладеної теж працює, неоднозначне - помилка зі шляхами.
- [x] 24.2. TG-6a: `update_infobase` і `yaxunit_tests` з оновленням падали з «Shell is not provided in execution context». Причина: `InfobaseApplicationBehaviourDelegate.update` (бандл `applications.infobases.ui`) бере з `ExecutionContext` властивість `activeShell` (SWT Shell) для діалогів змін в ІБ і облікових даних; HTTP-потік її не клав. Новий `edt/EdtExecution.context()` кладе вікно workbench (активне, інакше вікно активного/першого workbench-вікна). `run_application` теж отримує вікно (необов'язкове).
- [x] 24.3. TG-6b: `yaxunit_tests wait=true` упирався в таймаут виклику MCP. Оновлення ІБ, старт 1С і очікування звіту тепер у фоновій джобі (`JobManager.startResultTask`); виклик чекає не довше `waitSeconds` (45 с типово, максимум 55). Не вклався - `pending:true` + `jobId`, підсумок у `get_job_status` (нове поле `result`, при помилці - `error`). `jobId` тепер джоба прогону, джоба процесу 1С - `processJobId`.
- [x] 24.4. ПЕРЕВІРЕНО НАЖИВО 2026-10-08 (0.22.6, workspace `int ka`, ІБ `KA_INTERTOP_local`): dryRun `addSubsystemContent` для `МП_МобильноеПриложение.МаркетПлейс` резолвиться в усіх формах (`.`, `/`, FQN із `Subsystem`, коротке унікальне ім'я, третій рівень `...МаркетПлейс.НСИ`); `update_infobase` - `UPDATED`, код 0, 39 с, без помилки Shell і без діалогів; `yaxunit_tests` (15 тестів `РС_МП_Штрихкоды_*`) - виклик повернувся за 45 с з `pending:true`, через `get_job_status` підсумок 15/15 PASS (оновлення + старт 1С + прогін ~200 с). НЕ перевірено: гілка «вклалося в waitSeconds» (старт клієнта 1С сам триває ~60 с).
