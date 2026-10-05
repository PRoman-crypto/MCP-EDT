# code_review — перевірка BSL за стандартами 1С через BSL Language Server

Дослідження для інструмента `code_review` (2026-08-26). Джерела: README
github.com/prepod2003/mcp-rsv-codereview, документація
1c-syntax.github.io/bsl-language-server (розділ Analyze і JSON-репортер),
GitHub API релізів bsl-language-server.

## Ліцензії — чи можна використовувати

| Компонент | Ліцензія | Висновок |
|---|---|---|
| bsl-language-server (1c-syntax) | **LGPL-3.0-or-later** | Можна. Ми не лінкуємось і не вбудовуємо jar у бандл — запускаємо як **окремий зовнішній процес** через CLI. Це найчистіший режим щодо LGPL: наш код залишається під своєю ліцензією, jar користувач завантажує сам. |
| mcp-rsv-codereview (Радзівіллович) | **GPL-3.0-or-later** (код плагіна) | Код НЕ використовуємо і не читали — clean-room. Взято лише публічно описану ідею: «двигун BSL LS постачається окремим замінним jar, плагін його викликає». Ідеї/архітектурні підходи GPL не покриває. |

Комерційний аналог влаштований так: EDT-плагін (GPL) + окремий jar BSL LS
(LGPL), який кладеться в resources при збірці; аналіз показується в Problems
panel через контекстне меню. Наш варіант відрізняється: jar не постачається
взагалі (нема ліцензійного питання дистрибуції), результат повертається як
структурований JSON у MCP-відповідь.

## CLI BSL Language Server

- Актуальний реліз: **v1.0.7** (2026-08-08). Виконуваний артефакт —
  **`bsl-language-server-1.0.7-exec.jar`** (~130 МБ, fat-jar; звичайний
  `bsl-language-server-1.0.7.jar` — тонкий, без залежностей, для CLI не
  годиться). Потрібна **Java 17+** (у нас: JVM самого EDT — Axiom JDK 17,
  `System.getProperty("java.home")`; запасні варіанти JAVA_HOME / PATH /
  `C:\Program Files\Zulu\zulu-17\bin\java.exe`).
- Точний виклик аналізатора (з офіційної документації):

```
java -Xmx2g -jar bsl-language-server-1.0.7-exec.jar --analyze ^
    --srcDir <каталог з .bsl> ^
    --outputDir <каталог звітів> ^
    --reporter json ^
    --silent ^
    [--configuration <шлях до .bsl-language-server.json>]
```

  Опції analyze: `--srcDir|-s`, `--outputDir|-o`, `--reporter|-r`
  (console, junit, json, tslint, generic; можна кілька), `--workspaceDir|-w`,
  `--configuration|-c`, `--silent|-q`. Для великих конфігурацій документація
  радить `-Xmx4g`.
- Конфігурація діагностик — файл `.bsl-language-server.json` у корені проєкту
  (мова діагностик, вкл/викл правил, параметри). Наш інструмент підхоплює його
  автоматично, якщо лежить у корені EDT-проєкту.

## Формат JSON-звіту (репортер `json`)

Файл **`bsl-json.json`** у `--outputDir`. Структура (`AnalysisInfo`):

```json
{
  "date": "…",
  "sourceDir": "D:/…/src",
  "fileinfos": [
    {
      "path": "file:///D:/…/src/CommonModules/X/Module.bsl",
      "mdoRef": "CommonModule.X",
      "diagnostics": [
        {
          "range": {"start": {"line": 12, "character": 4}, "end": {…}},
          "severity": "Error | Warning | Information | Hint",
          "code": "CanonicalSpellingKeywords",
          "source": "bsl-language-server",
          "message": "…",
          "tags": null,
          "relatedInformation": null
        }
      ],
      "metrics": {…}
    }
  ]
}
```

Нюанси парсингу (враховано в коді): `range` — 0-based (LSP), у відповіді
переводимо в 1-based; `code` може бути рядком або LSP-об'єктом `{value:…}`;
`path` — file-URI з URL-кодуванням (декодуємо і релятивізуємо до srcDir).

## Архітектура інструмента

`src/com/polischuk/edt/prl/tools/validation/CodeReviewTool.java`,
`name()="code_review"`. **Синхронний** запуск зовнішнього процесу з таймаутом
(за замовч. 300 с, параметр `timeoutSeconds` до 1800) — а не JobManager-джоба:
відповідь мусить бути розпарсеним звітом, а не сирим консольним хвостом;
для одного модуля/підмножини аналіз секундний. WriteGate не потрібен —
операція read-only.

Параметри: `project?`, `path?` (один модуль), `pathFilter?` (підмножина),
`bslLsPath?`, `format` (`summary`|`full`, дефолт summary), `timeoutSeconds?`.

Вибір srcDir:
- без `path`/`pathFilter` — прямо `<проєкт>/src` на диску (без копіювання);
- `path` — копія одного .bsl у темп-каталог зі збереженням відносного шляху;
- `pathFilter` — обхід `src` (WorkspaceFiles.walk), копія відповідних .bsl у
  темп. Темп-каталог — `Files.createTempDirectory`, прибирається у `finally`.

Пошук jar: `bslLsPath` (файл або каталог) → `%USERPROFILE%\.bsl-language-server\`
→ корінь проєкту → каталог поруч із проєктом; перевага `-exec.jar`, найновіший
за іменем; якщо нема — `IllegalStateException` з посиланням на релізи.

Вивід процесу читається окремим потоком (UTF-8, лог BSL LS) — лише для
діагностики збоїв; результат — тільки з `bsl-json.json`.

Формат відповіді:
- завжди: `project`, `bslLsJar`, `elapsedSeconds`, `filesAnalyzed`,
  `filesWithIssues`, `totalDiagnostics`, `bySeverity`;
- `summary`: `rules` — до 50 правил `{rule, severity, count, sampleMessage}`
  за спаданням count;
- `full`: `issues` — до 200 `{path, line, rule, severity, message}` +
  `issuesTruncated` за перевищення.

## Що потрібно від користувача

1. Завантажити `bsl-language-server-1.0.7-exec.jar` зі сторінки
   https://github.com/1c-syntax/bsl-language-server/releases (~130 МБ).
2. Покласти в `%USERPROFILE%\.bsl-language-server\` (створити каталог) — або
   передавати `bslLsPath` у кожному виклику.
3. Зареєструвати інструмент: у `ToolRegistry.createDefault()` додати
   `registry.register(new CodeReviewTool());` + import (свідомо не зроблено в
   цій сесії — ToolRegistry заморожений умовами задачі), підняти версію,
   `build.ps1`, деплой, рестарт EDT.

## План живого тесту

1. Реєстрація в ToolRegistry, збірка, деплой (поза цією сесією).
2. Smoke без jar: виклик `code_review` — очікуємо чемну помилку з посиланням
   на релізи.
3. Покласти exec-jar у `%USERPROFILE%\.bsl-language-server\`.
4. Один модуль: `code_review {path:"src/CommonModules/<X>/Module.bsl",
   format:"full"}` — очікуємо issues з номерами рядків, що збігаються з
   модулем (перевірити 1-based).
5. Підмножина: `{pathFilter:"CommonModules", format:"summary"}` — очікуємо
   згруповані правила з count.
6. Весь проєкт: `{format:"summary", timeoutSeconds:900}` на робочій
   конфігурації — заміряти час; якщо довго, задокументувати рекомендацію
   pathFilter.
7. Негативні: неіснуючий path; pathFilter без збігів; бите значення bslLsPath.
8. Проєкт із `.bsl-language-server.json` у корені — перевірити, що конфіг
   підхоплюється (напр., вимкнене правило зникає зі звіту).
