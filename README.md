# MCP:PRL Server

Плагін для 1С:EDT, який при старті IDE піднімає локальний HTTP-сервер за стандартом
MCP (Model Context Protocol) і надає AI-клієнтам (Claude Code, Cursor, Windsurf, Gemini CLI…)
інструменти роботи з конфігураціями 1С у поточному workspace.

- Bundle: `com.polischuk.edt.prl.server`
- Транспорт: MCP Streamable HTTP (JSON-RPC 2.0), endpoint `http://127.0.0.1:8765/mcp`
- Порт: `-Dmcp.prl.port=NNNN` в `1cedt.ini`/аргументах запуску (дефолт 8765; якщо зайнятий — сканується +10 угору, фактичний порт видно в Error Log EDT)
- Біндинг тільки на loopback, перевірка `Origin`.

## Збірка та встановлення

**Спосіб 1 — p2 Update Site (рекомендований для дистрибуції):**

```bash
powershell.exe -NoProfile -File "./publish-site.ps1"
```

Створює `site/` (features + plugins + p2-метадані). Встановлення в EDT:
**Help → Install New Software → Add → Local → `site/`** — далі стандартний майстер,
оновлення наступних версій через той самий механізм.

**Спосіб 2 — dropins (швидкі ітерації розробки):**

```bash
powershell.exe -NoProfile -File "./deploy.ps1"
```

Копіює jar у `dropins` інсталяції EDT (потрібні права адміністратора).
Після деплою — перезапустити EDT; якщо плагін не підхопився — раз із `-clean`.

## Підключення клієнта

Claude Code:

```bash
claude mcp add --transport http 1c-rsv http://127.0.0.1:8765/mcp
```

Або в `.mcp.json` проєкту:

```json
{
  "mcpServers": {
    "1c-rsv": { "type": "http", "url": "http://127.0.0.1:8765/mcp" }
  }
}
```

> Ім'я сервера в конфігу клієнта (`1c-rsv`) залишено збіжним з тим, що очікують
> агенти titaa (`mcp__1c-rsv__*`); сам плагін називається MCP:PRL Server.

## Інструменти (v0.10.0 — 30 інструментів)

| Група | Інструменти |
|---|---|
| Огляд | `show_edt_version`, `list_workspace_projects`, `get_config_properties` |
| Метадані | `list_metadata_objects`, `get_object_details`, `get_object_help`, `ai_context`, `get_form_image` (structure) |
| Код BSL | `list_modules`, `get_module_structure`, `read_module_source`, `read_method_source`, `code_search`, `find_references` (Xtext-індекс), `get_call_hierarchy` (incoming/outgoing) |
| Відладка | `launch_debugger` (цілі, точки останову, стек, змінні, кроки; evaluate — у розробці) |
| Застосунки | `list_applications`, `run_application` (запуск 1С:Підприємства), `update_infobase` (оновлення конфігурації БД) |
| Структура | `get_subsystem_content` (склад підсистем), `get_role_rights` (права ролі + RLS) |
| Валідація | `get_validation_errors`, `validate_query` |
| Довідка | `get_platform_docs` (синтакс-помічник платформи, ru/en, повнотекстовий пошук) |
| Запис* | `write_module_source` (dryRun, захист від великих видалень), `diff_module`, `edit_metadata` (властивості, синоніми, реквізити, ТЧ; dryRun з відкатом транзакції) |
| Експорт | `export_object` (.epf/.erf; потребує проєкт зовнішньої обробки і пов'язану ІБ) |
| CI/тести* | `run_vrunner` (Vanessa Runner асинхронно, allowlist команд), `get_job_status` (статус/вивід/stop джоб) |

`edit_metadata` (v0.21.0): виміри й ресурси регістрів (`addDimension`/`addResource`, ссылочные типи,
кваліфікатори дати/числа), загальні `addItem`/`deleteItem`/`setItemProperty`/`setItemType` для будь-якої
колекції, склад підсистем (`addSubsystemContent`/`removeSubsystemContent`) і планів обміну
(`addExchangePlanContent`/`removeExchangePlanContent`), права ролей (`setRoleRights`); усе транзакційне,
з `dryRun` і в `batch`. Раніше (v0.8.0): properties, синоніми, реквізити з типами (у т.ч. в ТЧ), табличні
частини, створення і видалення top-об'єктів (`createObject`/`deleteObject`); усі операції
з `dryRun` через відкат BM-транзакції. Настройки: Window → Preferences → MCP:PRL (статус,
дозвіл запису); індикатор порту в статус-барі; discovery-файл `%USERPROFILE%\.edt-mcp\instance-*.json`.

\* Інструменти запису вимагають файл-прапорець `%USERPROFILE%\.edt-mcp\write-enabled`;
без нього сервер працює read-only.

Дорожня карта — [docs/PLAN.md](docs/PLAN.md). Цільовий контракт інструментів сумісний
із набором `1c-rsv`, який очікують агенти titaa (docs/reference — опис референс-набору).
