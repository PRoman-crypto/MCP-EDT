/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.write;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Генератор схем компоновки даних (СКД): компактний JSON-опис → повний Template.dcs
 * у стандартній схемі 1С {@code http://v8.1c.ru/8.1/data-composition-system/schema}
 * (той самий формат, який читає {@code GetSkdTool}).
 *
 * <p>Специфікація (усі поля необов'язкові; порожня специфікація дає валідну порожню схему):
 *
 * <pre>
 * {
 *   "lang": "ru",                          // мова заголовків (типово ru)
 *   "dataSourceName": "ИсточникДанных1",   // ім'я джерела даних (типове)
 *   "dataSets": [
 *     {"name": "НаборДанных1", "type": "Query",      // Query (типово) | Object | Union
 *      "query": "ВЫБРАТЬ …",                        // Query
 *      "objectName": "ТаблицаЗначений",             // Object
 *      "autoFillFields": true,                      // false → &lt;autoFillFields&gt;false&lt;/…&gt;
 *      "fields": ["Номенклатура",                   // рядок — скорочення для {name:…}
 *                 {"name": "Количество", "title": "Кількість",
 *                  "type": "Число(15,3)", "field": "Кол"}],
 *      "items": [ …вкладені набори для Union… ]}
 *   ],
 *   "dataSetLinks": [
 *     {"source": "Шапка", "destination": "Строки",
 *      "sourceExpression": "Ссылка", "destinationExpression": "Ссылка", "parameter": "Отбор"}
 *   ],
 *   "calculatedFields": [
 *     {"name": "Средняя", "expression": "Сумма / Количество", "title": "Середня", "type": "Число"}
 *   ],
 *   "resources": [
 *     {"field": "Количество", "expression": "Сумма(Количество)", "groups": ["Номенклатура"]}
 *   ],
 *   "parameters": [
 *     {"name": "Период", "type": "СтандартныйПериод", "title": "Період"},
 *     {"name": "Дата1", "type": "Дата", "expression": "&amp;Период.ДатаНачала", "restricted": true},
 *     {"name": "Склад", "type": "СправочникСсылка.Склады"}
 *   ],
 *   "variants": [
 *     {"name": "Основной", "title": "Основний",
 *      "selection": ["Номенклатура", "Количество"], "groupings": ["Номенклатура"]}
 *   ]
 * }
 * </pre>
 *
 * <p>Скорочення верхнього рівня: якщо {@code dataSets} немає, але є {@code query}
 * (і, за потреби, {@code fields}), будується один набір {@code НаборДанных1}.
 *
 * <p>Автогенерація полів: якщо у наборі типу Query не задано {@code fields}, список
 * колонок витягується з тексту запиту (секція ВЫБРАТЬ … до ИЗ). Підтримано псевдоніми
 * {@code КАК Алиас}, крапкові шляхи ({@code Таблица.Поле} → {@code Поле}), модифікатори
 * РАЗРЕШЕННЫЕ/РАЗЛИЧНЫЕ/ПЕРВЫЕ N, блоки-розширення СКД {@code {ВЫБРАТЬ …}}, коментарі
 * {@code //}, рядкові літерали і пакетні запити (береться останній запит пакета).
 * Вираз без псевдоніма розпарсити неможливо — буде зрозуміла помилка з проханням
 * задати {@code fields} явно.
 *
 * <p>Свідомі спрощення (мінімалізм важливіший за повноту): одне локальне джерело даних,
 * без внутрішніх макетів оформлення ({@code template}), без умовного оформлення,
 * відборів і порядку у варіантах, без вкладених схем і зовнішніх джерел даних,
 * без {@code userSettingID} (EDT згенерує їх при першому редагуванні).
 */
public final class SkdBuilder {

    /** Типове ім'я локального джерела даних — як його створює конструктор 1С. */
    private static final String DEFAULT_DATA_SOURCE = "ИсточникДанных1"; //$NON-NLS-1$

    /** Типове ім'я єдиного набору даних. */
    private static final String DEFAULT_DATA_SET = "НаборДанных1"; //$NON-NLS-1$

    /** Типове ім'я варіанта налаштувань. */
    private static final String DEFAULT_VARIANT = "Основной"; //$NON-NLS-1$

    /** Стеля наборів даних (разом із вкладеними). */
    private static final int MAX_DATA_SETS = 50;

    /** Стеля полів одного набору. */
    private static final int MAX_FIELDS = 500;

    /** Стеля параметрів схеми. */
    private static final int MAX_PARAMETERS = 200;

    /** Стеля варіантів налаштувань. */
    private static final int MAX_VARIANTS = 20;

    /** Стеля довжини тексту запиту. */
    private static final int MAX_QUERY_CHARS = 100_000;

    /** Ідентифікатор 1С: ім'я набору, поля, параметра, варіанта. */
    private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}_][\\p{L}\\p{Nd}_]*"); //$NON-NLS-1$

    /** Шлях до даних: ідентифікатор або крапковий ланцюжок (Магазин.Наименование). */
    private static final Pattern DATA_PATH =
            Pattern.compile("[\\p{L}_][\\p{L}\\p{Nd}_]*(\\.[\\p{L}_][\\p{L}\\p{Nd}_]*)*"); //$NON-NLS-1$

    /** Ключові слова, що завершують секцію вибірки запиту (рос. і англ. варіанти). */
    private static final Set<String> SELECT_STOP_WORDS = Set.of(
            "ИЗ", "FROM", "ГДЕ", "WHERE", "СГРУППИРОВАТЬ", "GROUP", "ИМЕЮЩИЕ", "HAVING", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
            "УПОРЯДОЧИТЬ", "ORDER", "ИТОГИ", "TOTALS", "ОБЪЕДИНИТЬ", "UNION", "ПОМЕСТИТЬ", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
            "INTO", "ИНДЕКСИРОВАТЬ", "INDEX", "АВТОУПОРЯДОЧИВАНИЕ", "AUTOORDER"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

    /** Модифікатори одразу після ВЫБРАТЬ, які треба пропустити. */
    private static final Set<String> SELECT_MODIFIERS = Set.of(
            "РАЗРЕШЕННЫЕ", "ALLOWED", "РАЗЛИЧНЫЕ", "DISTINCT"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    /** Простір імен посилальних типів поточної конфігурації. */
    private static final String CURRENT_CONFIG_NS =
            "http://v8.1c.ru/8.1/data/enterprise/current-config"; //$NON-NLS-1$

    /** Українські/російські назви посилальних типів → англійські імена XDTO. */
    private static final String[][] REF_TYPES = {
            {"СправочникСсылка", "CatalogRef"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"ДокументСсылка", "DocumentRef"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"ПеречислениеСсылка", "EnumRef"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"ПланВидовХарактеристикСсылка", "ChartOfCharacteristicTypesRef"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"ПланСчетовСсылка", "ChartOfAccountsRef"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"ПланВидовРасчетаСсылка", "ChartOfCalculationTypesRef"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"ПланОбменаСсылка", "ExchangePlanRef"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"БизнесПроцессСсылка", "BusinessProcessRef"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"ЗадачаСсылка", "TaskRef"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"ТочкаМаршрутаБизнесПроцессаСсылка", "BusinessProcessRoutePointRef"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"CatalogRef", "CatalogRef"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"DocumentRef", "DocumentRef"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"EnumRef", "EnumRef"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"ChartOfCharacteristicTypesRef", "ChartOfCharacteristicTypesRef"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"ChartOfAccountsRef", "ChartOfAccountsRef"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"ChartOfCalculationTypesRef", "ChartOfCalculationTypesRef"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"ExchangePlanRef", "ExchangePlanRef"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"BusinessProcessRef", "BusinessProcessRef"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"TaskRef", "TaskRef"}, //$NON-NLS-1$ //$NON-NLS-2$
    };

    private SkdBuilder() {
    }

    // ------------------------------------------------------------------
    // Публічний вхід
    // ------------------------------------------------------------------

    /**
     * Будує повний текст Template.dcs зі специфікації. Порожня (або null)
     * специфікація дає валідну порожню схему (як щойно створена в конструкторі).
     *
     * @param spec компактний опис схеми, див. опис класу
     * @return XML-текст файлу Template.dcs (UTF-8, без BOM)
     */
    public static String buildDcs(JsonObject spec) {
        JsonObject source = spec == null ? new JsonObject() : spec;
        String lang = text(source, "lang", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        String dataSourceName = text(source, "dataSourceName", DEFAULT_DATA_SOURCE); //$NON-NLS-1$
        requireIdentifier(dataSourceName, "dataSourceName"); //$NON-NLS-1$

        List<DataSet> dataSets = readDataSets(source);
        Set<String> knownFields = new LinkedHashSet<>();
        boolean fieldsComplete = collectFieldNames(dataSets, knownFields);

        StringBuilder xml = new StringBuilder(4096);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"); //$NON-NLS-1$
        xml.append("<DataCompositionSchema xmlns=\"http://v8.1c.ru/8.1/data-composition-system/schema\"") //$NON-NLS-1$
                .append(" xmlns:dcscom=\"http://v8.1c.ru/8.1/data-composition-system/common\"") //$NON-NLS-1$
                .append(" xmlns:dcscor=\"http://v8.1c.ru/8.1/data-composition-system/core\"") //$NON-NLS-1$
                .append(" xmlns:dcsset=\"http://v8.1c.ru/8.1/data-composition-system/settings\"") //$NON-NLS-1$
                .append(" xmlns:v8=\"http://v8.1c.ru/8.1/data/core\"") //$NON-NLS-1$
                .append(" xmlns:v8ui=\"http://v8.1c.ru/8.1/data/ui\"") //$NON-NLS-1$
                .append(" xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"") //$NON-NLS-1$
                .append(" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n"); //$NON-NLS-1$

        // Порядок елементів — той самий, який пише EDT (послідовність XML-схеми):
        // dataSource, dataSet, dataSetLink, calculatedField, totalField, parameter, settingsVariant
        if (!dataSets.isEmpty()) {
            xml.append("\t<dataSource>\n\t\t<name>").append(escape(dataSourceName)).append("</name>\n") //$NON-NLS-1$ //$NON-NLS-2$
                    .append("\t\t<dataSourceType>Local</dataSourceType>\n\t</dataSource>\n"); //$NON-NLS-1$
        }
        for (DataSet dataSet : dataSets) {
            emitDataSet(xml, dataSet, "dataSet", 1, dataSourceName, lang); //$NON-NLS-1$
        }
        emitDataSetLinks(xml, source);
        List<Field> calculated = readCalculatedFields(source, knownFields);
        for (Field field : calculated) {
            emitCalculatedField(xml, field, lang);
        }
        emitResources(xml, source, knownFields, fieldsComplete);
        emitParameters(xml, source, lang);
        emitVariants(xml, source, lang, dataSets.isEmpty());

        xml.append("</DataCompositionSchema>\n"); //$NON-NLS-1$
        return xml.toString();
    }

    /**
     * Точкова заміна тексту запиту в наявній схемі: решта XML зберігається символ у символ.
     *
     * @param existingDcsXml вміст наявного Template.dcs
     * @param dataSetName ім'я набору даних (типу Query), у т.ч. вкладеного в об'єднання
     * @param newQuery новий текст запиту
     * @return змінений XML
     */
    public static String setDataSetQuery(String existingDcsXml, String dataSetName, String newQuery) {
        if (existingDcsXml == null || existingDcsXml.isBlank()) {
            throw new IllegalArgumentException("Порожній вміст Template.dcs"); //$NON-NLS-1$
        }
        if (dataSetName == null || dataSetName.isBlank()) {
            throw new IllegalArgumentException("Не вказано ім'я набору даних"); //$NON-NLS-1$
        }
        if (newQuery == null) {
            throw new IllegalArgumentException("Не вказано новий текст запиту"); //$NON-NLS-1$
        }
        if (newQuery.length() > MAX_QUERY_CHARS) {
            throw new IllegalArgumentException("Текст запиту задовгий (ліміт " + MAX_QUERY_CHARS + " символів)"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        List<String> available = new ArrayList<>();
        int position = 0;
        while ((position = existingDcsXml.indexOf('<', position)) >= 0) {
            String tag = openTagName(existingDcsXml, position);
            if (tag == null) {
                position++;
                continue;
            }
            int tagEnd = existingDcsXml.indexOf('>', position);
            if (tagEnd < 0) {
                break;
            }
            if (existingDcsXml.charAt(tagEnd - 1) == '/') {
                position++;
                continue;
            }
            int blockEnd = matchingClose(existingDcsXml, tagEnd + 1, tag);
            if (blockEnd < 0) {
                position++;
                continue;
            }
            String name = firstChildText(existingDcsXml, tagEnd + 1, blockEnd, "name"); //$NON-NLS-1$
            if (name != null) {
                available.add(name);
                if (name.equals(dataSetName)) {
                    String openTag = existingDcsXml.substring(position, tagEnd + 1);
                    if (openTag.contains("DataSetObject") || openTag.contains("DataSetUnion")) { //$NON-NLS-1$ //$NON-NLS-2$
                        throw new IllegalArgumentException("Набір даних «" + dataSetName //$NON-NLS-1$
                                + "» не є запитним (Query) — тексту запиту в нього немає"); //$NON-NLS-1$
                    }
                    return replaceQuery(existingDcsXml, tagEnd + 1, blockEnd, newQuery);
                }
            }
            position++;
        }
        throw new IllegalArgumentException("Набір даних не знайдено: " + dataSetName //$NON-NLS-1$
                + (available.isEmpty() ? "" : ". Доступні: " + String.join(", ", available))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    // ------------------------------------------------------------------
    // Точкова заміна запиту: рядковий пошук блоків
    // ------------------------------------------------------------------

    /** Ім'я відкривального тега dataSet/item у позиції position, або null. */
    private static String openTagName(String xml, int position) {
        for (String candidate : new String[] {"dataSet", "item"}) { //$NON-NLS-1$ //$NON-NLS-2$
            if (!xml.startsWith("<" + candidate, position)) { //$NON-NLS-1$
                continue;
            }
            int after = position + 1 + candidate.length();
            if (after >= xml.length()) {
                continue;
            }
            char symbol = xml.charAt(after);
            if (symbol == ' ' || symbol == '\t' || symbol == '\n' || symbol == '\r' || symbol == '>'
                    || symbol == '/') {
                return candidate;
            }
        }
        return null;
    }

    /** Позиція «&lt;/tag&gt;», що закриває елемент, врахувавши вкладені однойменні. */
    private static int matchingClose(String xml, int from, String tag) {
        String open = "<" + tag; //$NON-NLS-1$
        String close = "</" + tag + ">"; //$NON-NLS-1$ //$NON-NLS-2$
        int depth = 0;
        int position = from;
        while (true) {
            int nextClose = xml.indexOf(close, position);
            if (nextClose < 0) {
                return -1;
            }
            // перший СПРАВЖНІЙ відкривальний тег до найближчого закривального
            int nested = -1;
            int scan = position;
            while (true) {
                int candidate = xml.indexOf(open, scan);
                if (candidate < 0 || candidate > nextClose) {
                    break;
                }
                int end = xml.indexOf('>', candidate);
                if (end < 0) {
                    return -1;
                }
                if (openTagName(xml, candidate) != null && xml.charAt(end - 1) != '/') {
                    nested = candidate;
                    break;
                }
                scan = candidate + 1;
            }
            if (nested >= 0) {
                depth++;
                position = xml.indexOf('>', nested) + 1;
                continue;
            }
            if (depth == 0) {
                return nextClose;
            }
            depth--;
            position = nextClose + close.length();
        }
    }

    /** Текст першого дочірнього елемента tag у діапазоні [from, to). */
    private static String firstChildText(String xml, int from, int to, String tag) {
        int start = xml.indexOf("<" + tag + ">", from); //$NON-NLS-1$ //$NON-NLS-2$
        if (start < 0 || start >= to) {
            return null;
        }
        int contentStart = start + tag.length() + 2;
        int end = xml.indexOf("</" + tag + ">", contentStart); //$NON-NLS-1$ //$NON-NLS-2$
        if (end < 0 || end > to) {
            return null;
        }
        return unescape(xml.substring(contentStart, end)).strip();
    }

    /** Замінює (або вставляє) елемент query в межах блоку набору даних. */
    private static String replaceQuery(String xml, int blockStart, int blockEnd, String newQuery) {
        String body = escape(newQuery);
        int start = xml.indexOf("<query>", blockStart); //$NON-NLS-1$
        if (start >= 0 && start < blockEnd) {
            int end = xml.indexOf("</query>", start); //$NON-NLS-1$
            if (end < 0 || end > blockEnd) {
                throw new IllegalArgumentException("Пошкоджений XML: не закрито елемент query"); //$NON-NLS-1$
            }
            return xml.substring(0, start + "<query>".length()) + body + xml.substring(end); //$NON-NLS-1$
        }
        for (String empty : new String[] {"<query/>", "<query />"}) { //$NON-NLS-1$ //$NON-NLS-2$
            int position = xml.indexOf(empty, blockStart);
            if (position >= 0 && position < blockEnd) {
                return xml.substring(0, position) + "<query>" + body + "</query>" //$NON-NLS-1$ //$NON-NLS-2$
                        + xml.substring(position + empty.length());
            }
        }
        // Елемента ще немає: вставляємо перед autoFillFields (порядок XML-схеми) або в кінець блоку
        int insert = xml.indexOf("<autoFillFields", blockStart); //$NON-NLS-1$
        if (insert < 0 || insert > blockEnd) {
            insert = blockEnd;
        }
        int lineStart = xml.lastIndexOf('\n', insert - 1) + 1;
        String indent = xml.substring(lineStart, insert);
        if (!indent.isBlank()) {
            indent = "\t\t"; //$NON-NLS-1$
        }
        return xml.substring(0, insert) + "<query>" + body + "</query>\n" + indent //$NON-NLS-1$ //$NON-NLS-2$
                + xml.substring(insert);
    }

    // ------------------------------------------------------------------
    // Набори даних
    // ------------------------------------------------------------------

    /** Набори даних зі spec; підтримано скорочення query/fields на верхньому рівні. */
    private static List<DataSet> readDataSets(JsonObject spec) {
        JsonArray declared = array(spec, "dataSets"); //$NON-NLS-1$
        List<DataSet> result = new ArrayList<>();
        if (declared == null) {
            if (spec.has("query") || spec.has("fields") || spec.has("objectName")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                result.add(readDataSet(spec, DEFAULT_DATA_SET, new int[] {MAX_DATA_SETS}));
            }
            return result;
        }
        int[] budget = {MAX_DATA_SETS};
        Set<String> names = new LinkedHashSet<>();
        for (JsonElement element : declared) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("dataSets: кожен набір — об'єкт " //$NON-NLS-1$
                        + "{name, type, query|objectName, fields}"); //$NON-NLS-1$
            }
            DataSet dataSet = readDataSet(element.getAsJsonObject(),
                    DEFAULT_DATA_SET.substring(0, DEFAULT_DATA_SET.length() - 1) + (result.size() + 1), budget);
            if (!names.add(dataSet.name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Набір даних з таким ім'ям уже є: " + dataSet.name); //$NON-NLS-1$
            }
            result.add(dataSet);
        }
        return result;
    }

    /** Один набір даних: тип, джерело вмісту, поля (задані або витягнуті із запиту). */
    private static DataSet readDataSet(JsonObject spec, String fallbackName, int[] budget) {
        if (--budget[0] < 0) {
            throw new IllegalArgumentException("Забагато наборів даних (ліміт " + MAX_DATA_SETS + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        DataSet dataSet = new DataSet();
        dataSet.name = text(spec, "name", fallbackName); //$NON-NLS-1$
        requireIdentifier(dataSet.name, "name набору даних"); //$NON-NLS-1$
        dataSet.query = text(spec, "query", null); //$NON-NLS-1$
        dataSet.objectName = text(spec, "objectName", null); //$NON-NLS-1$

        JsonArray items = array(spec, "items"); //$NON-NLS-1$
        String type = text(spec, "type", null); //$NON-NLS-1$
        dataSet.type = resolveDataSetType(type, dataSet, items != null);

        if (dataSet.query != null && dataSet.query.length() > MAX_QUERY_CHARS) {
            throw new IllegalArgumentException("Текст запиту набору " + dataSet.name //$NON-NLS-1$
                    + " задовгий (ліміт " + MAX_QUERY_CHARS + " символів)"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if ("DataSetObject".equals(dataSet.type) && dataSet.objectName == null) { //$NON-NLS-1$
            throw new IllegalArgumentException("Набір " + dataSet.name //$NON-NLS-1$
                    + " типу Object потребує objectName (ім'я об'єкта-джерела)"); //$NON-NLS-1$
        }
        if (spec.has("autoFillFields") && !spec.get("autoFillFields").isJsonNull()) { //$NON-NLS-1$ //$NON-NLS-2$
            dataSet.autoFillFields = Boolean.valueOf(spec.get("autoFillFields").getAsBoolean()); //$NON-NLS-1$
        }

        JsonArray fields = array(spec, "fields"); //$NON-NLS-1$
        if (fields != null) {
            Set<String> seen = new LinkedHashSet<>();
            for (JsonElement element : fields) {
                Field field = readField(element, "fields набору " + dataSet.name); //$NON-NLS-1$
                if (!seen.add(field.dataPath.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("Поле повторюється в наборі " + dataSet.name //$NON-NLS-1$
                            + ": " + field.dataPath); //$NON-NLS-1$
                }
                dataSet.fields.add(field);
                if (dataSet.fields.size() > MAX_FIELDS) {
                    throw new IllegalArgumentException("Забагато полів у наборі " + dataSet.name //$NON-NLS-1$
                            + " (ліміт " + MAX_FIELDS + ")"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        } else if ("DataSetQuery".equals(dataSet.type) && dataSet.query != null) { //$NON-NLS-1$
            for (String name : parseQueryFields(dataSet.query, dataSet.name)) {
                Field field = new Field();
                field.dataPath = name;
                dataSet.fields.add(field);
            }
        }

        if (items != null) {
            for (JsonElement element : items) {
                if (!element.isJsonObject()) {
                    throw new IllegalArgumentException("items набору " + dataSet.name //$NON-NLS-1$
                            + ": кожен вкладений набір — об'єкт"); //$NON-NLS-1$
                }
                dataSet.items.add(readDataSet(element.getAsJsonObject(),
                        dataSet.name + (dataSet.items.size() + 1), budget));
            }
        }
        return dataSet;
    }

    /** Тип набору: явний type або висновок із наявних query/objectName/items. */
    private static String resolveDataSetType(String type, DataSet dataSet, boolean hasItems) {
        if (type != null) {
            String normalized = type.toLowerCase(Locale.ROOT);
            switch (normalized) {
            case "query", "datasetquery", "запрос": //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                return "DataSetQuery"; //$NON-NLS-1$
            case "object", "datasetobject", "объект": //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                return "DataSetObject"; //$NON-NLS-1$
            case "union", "datasetunion", "объединение": //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                return "DataSetUnion"; //$NON-NLS-1$
            default:
                throw new IllegalArgumentException("Невідомий тип набору даних: " + type //$NON-NLS-1$
                        + ". Допустимі: Query, Object, Union."); //$NON-NLS-1$
            }
        }
        if (hasItems) {
            return "DataSetUnion"; //$NON-NLS-1$
        }
        if (dataSet.objectName != null) {
            return "DataSetObject"; //$NON-NLS-1$
        }
        return "DataSetQuery"; //$NON-NLS-1$
    }

    /** Поле набору: рядок-скорочення або об'єкт {name, title, type, field}. */
    private static Field readField(JsonElement element, String where) {
        Field field = new Field();
        if (element.isJsonPrimitive()) {
            field.dataPath = element.getAsString();
        } else if (element.isJsonObject()) {
            JsonObject spec = element.getAsJsonObject();
            field.dataPath = text(spec, "name", text(spec, "dataPath", null)); //$NON-NLS-1$ //$NON-NLS-2$
            field.source = text(spec, "field", null); //$NON-NLS-1$
            field.title = text(spec, "title", null); //$NON-NLS-1$
            field.type = text(spec, "type", null); //$NON-NLS-1$
        } else {
            throw new IllegalArgumentException(where + ": поле — рядок або об'єкт {name, title, type}"); //$NON-NLS-1$
        }
        if (field.dataPath == null) {
            throw new IllegalArgumentException(where + ": у поля має бути ім'я (name)"); //$NON-NLS-1$
        }
        requireDataPath(field.dataPath, where);
        return field;
    }

    /** Імена всіх полів усіх наборів; false — якщо хоч у якогось набору полів немає. */
    private static boolean collectFieldNames(List<DataSet> dataSets, Set<String> out) {
        boolean complete = true;
        for (DataSet dataSet : dataSets) {
            if (dataSet.fields.isEmpty() && dataSet.items.isEmpty()) {
                complete = false;
            }
            for (Field field : dataSet.fields) {
                out.add(field.dataPath.toLowerCase(Locale.ROOT));
            }
            complete &= collectFieldNames(dataSet.items, out);
        }
        return complete;
    }

    // ------------------------------------------------------------------
    // Серіалізація елементів схеми
    // ------------------------------------------------------------------

    private static void emitDataSet(StringBuilder xml, DataSet dataSet, String tag, int level,
            String dataSourceName, String lang) {
        String pad = indent(level);
        xml.append(pad).append('<').append(tag).append(" xsi:type=\"").append(dataSet.type).append("\">\n"); //$NON-NLS-1$ //$NON-NLS-2$
        xml.append(indent(level + 1)).append("<name>").append(escape(dataSet.name)).append("</name>\n"); //$NON-NLS-1$ //$NON-NLS-2$
        for (Field field : dataSet.fields) {
            emitField(xml, field, level + 1, lang);
        }
        if (!"DataSetUnion".equals(dataSet.type)) { //$NON-NLS-1$
            xml.append(indent(level + 1)).append("<dataSource>").append(escape(dataSourceName)) //$NON-NLS-1$
                    .append("</dataSource>\n"); //$NON-NLS-1$
        }
        if ("DataSetQuery".equals(dataSet.type)) { //$NON-NLS-1$
            xml.append(indent(level + 1)).append("<query>") //$NON-NLS-1$
                    .append(escape(dataSet.query == null ? "" : dataSet.query)).append("</query>\n"); //$NON-NLS-1$ //$NON-NLS-2$
        } else if ("DataSetObject".equals(dataSet.type)) { //$NON-NLS-1$
            xml.append(indent(level + 1)).append("<objectName>").append(escape(dataSet.objectName)) //$NON-NLS-1$
                    .append("</objectName>\n"); //$NON-NLS-1$
        }
        if (dataSet.autoFillFields != null && !dataSet.autoFillFields.booleanValue()) {
            xml.append(indent(level + 1)).append("<autoFillFields>false</autoFillFields>\n"); //$NON-NLS-1$
        }
        for (DataSet item : dataSet.items) {
            emitDataSet(xml, item, "item", level + 1, dataSourceName, lang); //$NON-NLS-1$
        }
        xml.append(pad).append("</").append(tag).append(">\n"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void emitField(StringBuilder xml, Field field, int level, String lang) {
        String pad = indent(level);
        xml.append(pad).append("<field xsi:type=\"DataSetFieldField\">\n"); //$NON-NLS-1$
        xml.append(indent(level + 1)).append("<dataPath>").append(escape(field.dataPath)) //$NON-NLS-1$
                .append("</dataPath>\n"); //$NON-NLS-1$
        xml.append(indent(level + 1)).append("<field>") //$NON-NLS-1$
                .append(escape(field.source == null ? field.dataPath : field.source)).append("</field>\n"); //$NON-NLS-1$
        emitLocalString(xml, "title", field.title, level + 1, lang); //$NON-NLS-1$
        emitValueType(xml, field.type, level + 1);
        xml.append(pad).append("</field>\n"); //$NON-NLS-1$
    }

    private static void emitDataSetLinks(StringBuilder xml, JsonObject spec) {
        JsonArray links = array(spec, "dataSetLinks"); //$NON-NLS-1$
        if (links == null) {
            return;
        }
        for (JsonElement element : links) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("dataSetLinks: кожен зв'язок — об'єкт " //$NON-NLS-1$
                        + "{source, destination, sourceExpression, destinationExpression}"); //$NON-NLS-1$
            }
            JsonObject link = element.getAsJsonObject();
            String source = required(link, "source", "dataSetLinks"); //$NON-NLS-1$ //$NON-NLS-2$
            String destination = required(link, "destination", "dataSetLinks"); //$NON-NLS-1$ //$NON-NLS-2$
            String sourceExpression = required(link, "sourceExpression", "dataSetLinks"); //$NON-NLS-1$ //$NON-NLS-2$
            String destinationExpression = required(link, "destinationExpression", "dataSetLinks"); //$NON-NLS-1$ //$NON-NLS-2$
            xml.append("\t<dataSetLink>\n"); //$NON-NLS-1$
            xml.append("\t\t<sourceDataSet>").append(escape(source)).append("</sourceDataSet>\n"); //$NON-NLS-1$ //$NON-NLS-2$
            xml.append("\t\t<destinationDataSet>").append(escape(destination)) //$NON-NLS-1$
                    .append("</destinationDataSet>\n"); //$NON-NLS-1$
            xml.append("\t\t<sourceExpression>").append(escape(sourceExpression)) //$NON-NLS-1$
                    .append("</sourceExpression>\n"); //$NON-NLS-1$
            xml.append("\t\t<destinationExpression>").append(escape(destinationExpression)) //$NON-NLS-1$
                    .append("</destinationExpression>\n"); //$NON-NLS-1$
            String parameter = text(link, "parameter", null); //$NON-NLS-1$
            if (parameter != null) {
                xml.append("\t\t<parameter>").append(escape(parameter)).append("</parameter>\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            xml.append("\t</dataSetLink>\n"); //$NON-NLS-1$
        }
    }

    private static List<Field> readCalculatedFields(JsonObject spec, Set<String> knownFields) {
        JsonArray declared = array(spec, "calculatedFields"); //$NON-NLS-1$
        List<Field> result = new ArrayList<>();
        if (declared == null) {
            return result;
        }
        for (JsonElement element : declared) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("calculatedFields: кожне поле — об'єкт " //$NON-NLS-1$
                        + "{name, expression, title, type}"); //$NON-NLS-1$
            }
            JsonObject item = element.getAsJsonObject();
            Field field = new Field();
            field.dataPath = text(item, "name", text(item, "dataPath", null)); //$NON-NLS-1$ //$NON-NLS-2$
            if (field.dataPath == null) {
                throw new IllegalArgumentException("calculatedFields: у поля має бути ім'я (name)"); //$NON-NLS-1$
            }
            requireDataPath(field.dataPath, "calculatedFields"); //$NON-NLS-1$
            field.source = required(item, "expression", "calculatedFields (" + field.dataPath + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            field.title = text(item, "title", null); //$NON-NLS-1$
            field.type = text(item, "type", null); //$NON-NLS-1$
            result.add(field);
            knownFields.add(field.dataPath.toLowerCase(Locale.ROOT));
        }
        return result;
    }

    private static void emitCalculatedField(StringBuilder xml, Field field, String lang) {
        xml.append("\t<calculatedField>\n"); //$NON-NLS-1$
        xml.append("\t\t<dataPath>").append(escape(field.dataPath)).append("</dataPath>\n"); //$NON-NLS-1$ //$NON-NLS-2$
        xml.append("\t\t<expression>").append(escape(field.source)).append("</expression>\n"); //$NON-NLS-1$ //$NON-NLS-2$
        emitLocalString(xml, "title", field.title, 2, lang); //$NON-NLS-1$
        emitValueType(xml, field.type, 2);
        xml.append("\t</calculatedField>\n"); //$NON-NLS-1$
    }

    /** Ресурси схеми — totalField із виразом агрегації (типово Сумма(Поле)). */
    private static void emitResources(StringBuilder xml, JsonObject spec, Set<String> knownFields,
            boolean fieldsComplete) {
        JsonArray resources = array(spec, "resources"); //$NON-NLS-1$
        if (resources == null) {
            return;
        }
        for (JsonElement element : resources) {
            String dataPath;
            String expression;
            JsonArray groups = null;
            if (element.isJsonPrimitive()) {
                dataPath = element.getAsString();
                expression = null;
            } else if (element.isJsonObject()) {
                JsonObject resource = element.getAsJsonObject();
                dataPath = text(resource, "field", text(resource, "name", //$NON-NLS-1$ //$NON-NLS-2$
                        text(resource, "dataPath", null))); //$NON-NLS-1$
                expression = text(resource, "expression", null); //$NON-NLS-1$
                groups = array(resource, "groups"); //$NON-NLS-1$
            } else {
                throw new IllegalArgumentException("resources: ресурс — рядок або об'єкт " //$NON-NLS-1$
                        + "{field, expression, groups}"); //$NON-NLS-1$
            }
            if (dataPath == null) {
                throw new IllegalArgumentException("resources: у ресурсу має бути поле (field)"); //$NON-NLS-1$
            }
            requireDataPath(dataPath, "resources"); //$NON-NLS-1$
            if (fieldsComplete && !knownFields.contains(dataPath.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("resources: поле «" + dataPath //$NON-NLS-1$
                        + "» не оголошене в наборах даних і не є обчислюваним"); //$NON-NLS-1$
            }
            xml.append("\t<totalField>\n"); //$NON-NLS-1$
            xml.append("\t\t<dataPath>").append(escape(dataPath)).append("</dataPath>\n"); //$NON-NLS-1$ //$NON-NLS-2$
            xml.append("\t\t<expression>") //$NON-NLS-1$
                    .append(escape(expression == null ? "Сумма(" + dataPath + ")" : expression)) //$NON-NLS-1$ //$NON-NLS-2$
                    .append("</expression>\n"); //$NON-NLS-1$
            if (groups != null) {
                for (JsonElement group : groups) {
                    xml.append("\t\t<group>").append(escape(group.getAsString())).append("</group>\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            xml.append("\t</totalField>\n"); //$NON-NLS-1$
        }
    }

    private static void emitParameters(StringBuilder xml, JsonObject spec, String lang) {
        JsonArray parameters = array(spec, "parameters"); //$NON-NLS-1$
        if (parameters == null) {
            return;
        }
        if (parameters.size() > MAX_PARAMETERS) {
            throw new IllegalArgumentException("Забагато параметрів (ліміт " + MAX_PARAMETERS + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        Set<String> names = new LinkedHashSet<>();
        for (JsonElement element : parameters) {
            String name;
            JsonObject parameter;
            if (element.isJsonPrimitive()) {
                name = element.getAsString();
                parameter = new JsonObject();
            } else if (element.isJsonObject()) {
                parameter = element.getAsJsonObject();
                name = text(parameter, "name", null); //$NON-NLS-1$
            } else {
                throw new IllegalArgumentException("parameters: параметр — рядок або об'єкт " //$NON-NLS-1$
                        + "{name, type, title, expression}"); //$NON-NLS-1$
            }
            if (name == null) {
                throw new IllegalArgumentException("parameters: у параметра має бути ім'я (name)"); //$NON-NLS-1$
            }
            requireIdentifier(name, "parameters.name"); //$NON-NLS-1$
            if (!names.add(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Параметр із таким ім'ям уже є: " + name); //$NON-NLS-1$
            }
            String type = text(parameter, "type", null); //$NON-NLS-1$

            xml.append("\t<parameter>\n"); //$NON-NLS-1$
            xml.append("\t\t<name>").append(escape(name)).append("</name>\n"); //$NON-NLS-1$ //$NON-NLS-2$
            emitLocalString(xml, "title", text(parameter, "title", name), 2, lang); //$NON-NLS-1$ //$NON-NLS-2$
            emitValueType(xml, type, 2);
            xml.append("\t\t").append(defaultValue(type)).append('\n'); //$NON-NLS-1$
            xml.append("\t\t<useRestriction>").append(flag(parameter, "restricted", false)) //$NON-NLS-1$ //$NON-NLS-2$
                    .append("</useRestriction>\n"); //$NON-NLS-1$
            String expression = text(parameter, "expression", null); //$NON-NLS-1$
            if (expression != null) {
                xml.append("\t\t<expression>").append(escape(expression)).append("</expression>\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (parameter.has("availableAsField")) { //$NON-NLS-1$
                xml.append("\t\t<availableAsField>").append(flag(parameter, "availableAsField", true)) //$NON-NLS-1$ //$NON-NLS-2$
                        .append("</availableAsField>\n"); //$NON-NLS-1$
            }
            String use = text(parameter, "use", null); //$NON-NLS-1$
            if (use != null) {
                if (!"Auto".equals(use) && !"Always".equals(use)) { //$NON-NLS-1$ //$NON-NLS-2$
                    throw new IllegalArgumentException("parameters.use: допустимі значення Auto|Always, отримано " //$NON-NLS-1$
                            + use);
                }
                xml.append("\t\t<use>").append(use).append("</use>\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            xml.append("\t</parameter>\n"); //$NON-NLS-1$
        }
    }

    // ------------------------------------------------------------------
    // Варіанти налаштувань
    // ------------------------------------------------------------------

    private static void emitVariants(StringBuilder xml, JsonObject spec, String lang, boolean schemaEmpty) {
        JsonArray variants = array(spec, "variants"); //$NON-NLS-1$
        if (variants == null) {
            if (schemaEmpty) {
                return; // порожня схема — варіант ні до чого не прив'яжеш
            }
            JsonObject basic = new JsonObject();
            basic.addProperty("name", DEFAULT_VARIANT); //$NON-NLS-1$
            emitVariant(xml, basic, lang);
            return;
        }
        if (variants.size() > MAX_VARIANTS) {
            throw new IllegalArgumentException("Забагато варіантів налаштувань (ліміт " + MAX_VARIANTS + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        Set<String> names = new LinkedHashSet<>();
        for (JsonElement element : variants) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("variants: варіант — об'єкт " //$NON-NLS-1$
                        + "{name, title, selection, groupings}"); //$NON-NLS-1$
            }
            JsonObject variant = element.getAsJsonObject();
            String name = text(variant, "name", DEFAULT_VARIANT); //$NON-NLS-1$
            requireIdentifier(name, "variants.name"); //$NON-NLS-1$
            if (!names.add(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Варіант із таким ім'ям уже є: " + name); //$NON-NLS-1$
            }
            emitVariant(xml, variant, lang);
        }
    }

    private static void emitVariant(StringBuilder xml, JsonObject variant, String lang) {
        String name = text(variant, "name", DEFAULT_VARIANT); //$NON-NLS-1$
        String title = text(variant, "title", name); //$NON-NLS-1$
        xml.append("\t<settingsVariant>\n"); //$NON-NLS-1$
        xml.append("\t\t<dcsset:name>").append(escape(name)).append("</dcsset:name>\n"); //$NON-NLS-1$ //$NON-NLS-2$
        emitLocalString(xml, "dcsset:presentation", title, 2, lang); //$NON-NLS-1$
        xml.append("\t\t<dcsset:settings>\n"); //$NON-NLS-1$

        // 1) вибрані поля схеми (порядок елементів усередині settings — за XML-схемою)
        xml.append("\t\t\t<dcsset:selection>\n"); //$NON-NLS-1$
        JsonArray selection = array(variant, "selection"); //$NON-NLS-1$
        if (selection == null || selection.isEmpty()) {
            xml.append("\t\t\t\t<dcsset:item xsi:type=\"dcsset:SelectedItemAuto\"/>\n"); //$NON-NLS-1$
        } else {
            for (JsonElement element : selection) {
                String field = element.getAsString();
                requireDataPath(field, "variants.selection"); //$NON-NLS-1$
                xml.append("\t\t\t\t<dcsset:item xsi:type=\"dcsset:SelectedItemField\">\n"); //$NON-NLS-1$
                xml.append("\t\t\t\t\t<dcsset:field>").append(escape(field)).append("</dcsset:field>\n"); //$NON-NLS-1$ //$NON-NLS-2$
                xml.append("\t\t\t\t</dcsset:item>\n"); //$NON-NLS-1$
            }
        }
        xml.append("\t\t\t</dcsset:selection>\n"); //$NON-NLS-1$

        // 2) заголовок звіту (вихідний параметр)
        if (variant.has("title")) { //$NON-NLS-1$
            xml.append("\t\t\t<dcsset:outputParameters>\n"); //$NON-NLS-1$
            xml.append("\t\t\t\t<dcscor:item xsi:type=\"dcsset:SettingsParameterValue\">\n"); //$NON-NLS-1$
            xml.append("\t\t\t\t\t<dcscor:parameter>Заголовок</dcscor:parameter>\n"); //$NON-NLS-1$
            xml.append("\t\t\t\t\t<dcscor:value xsi:type=\"v8:LocalStringType\">\n"); //$NON-NLS-1$
            xml.append("\t\t\t\t\t\t<v8:item>\n\t\t\t\t\t\t\t<v8:lang>").append(escape(lang)) //$NON-NLS-1$
                    .append("</v8:lang>\n\t\t\t\t\t\t\t<v8:content>").append(escape(title)) //$NON-NLS-1$
                    .append("</v8:content>\n\t\t\t\t\t\t</v8:item>\n"); //$NON-NLS-1$
            xml.append("\t\t\t\t\t</dcscor:value>\n"); //$NON-NLS-1$
            xml.append("\t\t\t\t</dcscor:item>\n"); //$NON-NLS-1$
            xml.append("\t\t\t</dcsset:outputParameters>\n"); //$NON-NLS-1$
        }

        // 3) структура виводу: одне групування (порожнє = детальні записи)
        xml.append("\t\t\t<dcsset:item xsi:type=\"dcsset:StructureItemGroup\">\n"); //$NON-NLS-1$
        JsonArray groupings = array(variant, "groupings"); //$NON-NLS-1$
        if (groupings != null && !groupings.isEmpty()) {
            xml.append("\t\t\t\t<dcsset:groupItems>\n"); //$NON-NLS-1$
            for (JsonElement element : groupings) {
                String field = element.getAsString();
                requireDataPath(field, "variants.groupings"); //$NON-NLS-1$
                xml.append("\t\t\t\t\t<dcsset:item xsi:type=\"dcsset:GroupItemField\">\n"); //$NON-NLS-1$
                xml.append("\t\t\t\t\t\t<dcsset:field>").append(escape(field)).append("</dcsset:field>\n"); //$NON-NLS-1$ //$NON-NLS-2$
                xml.append("\t\t\t\t\t\t<dcsset:groupType>Items</dcsset:groupType>\n"); //$NON-NLS-1$
                xml.append("\t\t\t\t\t\t<dcsset:periodAdditionType>None</dcsset:periodAdditionType>\n"); //$NON-NLS-1$
                xml.append("\t\t\t\t\t\t<dcsset:periodAdditionBegin xsi:type=\"xs:dateTime\">") //$NON-NLS-1$
                        .append("0001-01-01T00:00:00</dcsset:periodAdditionBegin>\n"); //$NON-NLS-1$
                xml.append("\t\t\t\t\t\t<dcsset:periodAdditionEnd xsi:type=\"xs:dateTime\">") //$NON-NLS-1$
                        .append("0001-01-01T00:00:00</dcsset:periodAdditionEnd>\n"); //$NON-NLS-1$
                xml.append("\t\t\t\t\t</dcsset:item>\n"); //$NON-NLS-1$
            }
            xml.append("\t\t\t\t</dcsset:groupItems>\n"); //$NON-NLS-1$
        }
        xml.append("\t\t\t\t<dcsset:order>\n\t\t\t\t\t<dcsset:item xsi:type=\"dcsset:OrderItemAuto\"/>\n") //$NON-NLS-1$
                .append("\t\t\t\t</dcsset:order>\n"); //$NON-NLS-1$
        xml.append("\t\t\t\t<dcsset:selection>\n\t\t\t\t\t<dcsset:item xsi:type=\"dcsset:SelectedItemAuto\"/>\n") //$NON-NLS-1$
                .append("\t\t\t\t</dcsset:selection>\n"); //$NON-NLS-1$
        xml.append("\t\t\t</dcsset:item>\n"); //$NON-NLS-1$

        xml.append("\t\t</dcsset:settings>\n"); //$NON-NLS-1$
        xml.append("\t</settingsVariant>\n"); //$NON-NLS-1$
    }

    // ------------------------------------------------------------------
    // Типи значень
    // ------------------------------------------------------------------

    /** Елемент valueType для заданого опису типу; нічого не пише, якщо тип не задано. */
    private static void emitValueType(StringBuilder xml, String type, int level) {
        if (type == null || type.isBlank()) {
            return;
        }
        List<String> types = splitTypes(type);
        String pad = indent(level);
        xml.append(pad).append("<valueType>\n"); //$NON-NLS-1$
        boolean single = types.size() == 1;
        for (String item : types) {
            xml.append(indent(level + 1)).append(typeElement(item)).append('\n');
            if (single) {
                String qualifiers = qualifiers(item, level + 1);
                if (!qualifiers.isEmpty()) {
                    xml.append(qualifiers);
                }
            }
        }
        xml.append(pad).append("</valueType>\n"); //$NON-NLS-1$
    }

    /** Один елемент {@code v8:Type} для назви типу 1С. */
    private static String typeElement(String type) {
        String base = baseType(type);
        switch (base) {
        case "строка", "string", "xs:string": //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return "<v8:Type>xs:string</v8:Type>"; //$NON-NLS-1$
        case "число", "number", "xs:decimal": //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return "<v8:Type>xs:decimal</v8:Type>"; //$NON-NLS-1$
        case "дата", "датавремя", "date", "datetime", "xs:datetime": //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            return "<v8:Type>xs:dateTime</v8:Type>"; //$NON-NLS-1$
        case "булево", "boolean", "xs:boolean": //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return "<v8:Type>xs:boolean</v8:Type>"; //$NON-NLS-1$
        case "стандартныйпериод", "standardperiod", "v8:standardperiod": //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return "<v8:Type>v8:StandardPeriod</v8:Type>"; //$NON-NLS-1$
        case "уникальныйидентификатор", "uuid", "v8:uuid": //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return "<v8:Type>v8:UUID</v8:Type>"; //$NON-NLS-1$
        case "списокзначений", "valuelisttype", "v8:valuelisttype": //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return "<v8:Type>v8:ValueListType</v8:Type>"; //$NON-NLS-1$
        default:
            return refTypeElement(type);
        }
    }

    /** Посилальний тип поточної конфігурації: СправочникСсылка.Склады → d4p1:CatalogRef.Склады. */
    private static String refTypeElement(String type) {
        int dot = type.indexOf('.');
        if (dot > 0) {
            String head = type.substring(0, dot).strip();
            String tail = type.substring(dot + 1).strip();
            for (String[] pair : REF_TYPES) {
                if (pair[0].equalsIgnoreCase(head)) {
                    if (!IDENTIFIER.matcher(tail).matches()) {
                        throw new IllegalArgumentException("Некоректне ім'я об'єкта в типі: " + type); //$NON-NLS-1$
                    }
                    return "<v8:Type xmlns:d4p1=\"" + CURRENT_CONFIG_NS + "\">d4p1:" + pair[1] + "." //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                            + escape(tail) + "</v8:Type>"; //$NON-NLS-1$
                }
            }
        }
        throw new IllegalArgumentException("Невідомий тип значення: " + type //$NON-NLS-1$
                + ". Підтримано: Строка[(N)], Число[(N,M)], Дата, Булево, СтандартныйПериод, " //$NON-NLS-1$
                + "УникальныйИдентификатор, СписокЗначений, СправочникСсылка.X, ДокументСсылка.X, " //$NON-NLS-1$
                + "ПеречислениеСсылка.X, ПланВидовХарактеристикСсылка.X та інші *Ссылка.X."); //$NON-NLS-1$
    }

    /** Кваліфікатори типу (довжина рядка, розрядність числа, склад дати). */
    private static String qualifiers(String type, int level) {
        String base = baseType(type);
        String pad = indent(level);
        String inner = indent(level + 1);
        switch (base) {
        case "строка", "string", "xs:string": { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            int length = intArgument(type, 0, 0);
            return pad + "<v8:StringQualifiers>\n" + inner + "<v8:Length>" + length + "</v8:Length>\n" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + inner + "<v8:AllowedLength>Variable</v8:AllowedLength>\n" + pad //$NON-NLS-1$
                    + "</v8:StringQualifiers>\n"; //$NON-NLS-1$
        }
        case "число", "number", "xs:decimal": { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            int digits = intArgument(type, 0, 15);
            int fraction = intArgument(type, 1, 2);
            return pad + "<v8:NumberQualifiers>\n" + inner + "<v8:Digits>" + digits + "</v8:Digits>\n" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + inner + "<v8:FractionDigits>" + fraction + "</v8:FractionDigits>\n" //$NON-NLS-1$ //$NON-NLS-2$
                    + inner + "<v8:AllowedSign>Any</v8:AllowedSign>\n" + pad + "</v8:NumberQualifiers>\n"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        case "дата", "датавремя", "date", "datetime", "xs:datetime": //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            return pad + "<v8:DateQualifiers>\n" + inner + "<v8:DateFractions>DateTime</v8:DateFractions>\n" //$NON-NLS-1$ //$NON-NLS-2$
                    + pad + "</v8:DateQualifiers>\n"; //$NON-NLS-1$
        default:
            return ""; //$NON-NLS-1$
        }
    }

    /** Перелік типів: кома-роздільник верхнього рівня (кома в Число(15,2) — не роздільник). */
    private static List<String> splitTypes(String type) {
        List<String> types = new ArrayList<>();
        for (String part : type.split(",(?![^()]*\\))")) { //$NON-NLS-1$
            if (!part.isBlank()) {
                types.add(part.strip());
            }
        }
        return types;
    }

    /** Значення параметра за замовчуванням для заданого типу. */
    private static String defaultValue(String type) {
        if (type == null || type.isBlank()) {
            return "<value xsi:nil=\"true\"/>"; //$NON-NLS-1$
        }
        List<String> types = splitTypes(type);
        if (types.size() != 1) {
            return "<value xsi:nil=\"true\"/>"; //$NON-NLS-1$
        }
        switch (baseType(types.get(0))) {
        case "строка", "string", "xs:string": //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return "<value xsi:type=\"xs:string\"/>"; //$NON-NLS-1$
        case "число", "number", "xs:decimal": //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return "<value xsi:type=\"xs:decimal\">0</value>"; //$NON-NLS-1$
        case "дата", "датавремя", "date", "datetime", "xs:datetime": //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            return "<value xsi:type=\"xs:dateTime\">0001-01-01T00:00:00</value>"; //$NON-NLS-1$
        case "булево", "boolean", "xs:boolean": //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return "<value xsi:type=\"xs:boolean\">false</value>"; //$NON-NLS-1$
        case "стандартныйпериод", "standardperiod", "v8:standardperiod": //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return "<value xsi:type=\"v8:StandardPeriod\">\n" //$NON-NLS-1$
                    + "\t\t\t<v8:variant xsi:type=\"v8:StandardPeriodVariant\">Custom</v8:variant>\n" //$NON-NLS-1$
                    + "\t\t\t<v8:startDate>0001-01-01T00:00:00</v8:startDate>\n" //$NON-NLS-1$
                    + "\t\t\t<v8:endDate>0001-01-01T00:00:00</v8:endDate>\n\t\t</value>"; //$NON-NLS-1$
        default:
            return "<value xsi:nil=\"true\"/>"; //$NON-NLS-1$
        }
    }

    /** Назва типу без аргументів у дужках, у нижньому регістрі. */
    private static String baseType(String type) {
        int bracket = type.indexOf('(');
        String base = bracket < 0 ? type : type.substring(0, bracket);
        return base.strip().toLowerCase(Locale.ROOT);
    }

    /** Числовий аргумент типу: Строка(100) → 100; Число(15,3) → 15 та 3. */
    private static int intArgument(String type, int index, int fallback) {
        int open = type.indexOf('(');
        int close = type.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return fallback;
        }
        String[] parts = type.substring(open + 1, close).split(","); //$NON-NLS-1$
        if (index >= parts.length || parts[index].isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(parts[index].strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Некоректний аргумент типу: " + type); //$NON-NLS-1$
        }
    }

    // ------------------------------------------------------------------
    // Автогенерація полів із тексту запиту
    // ------------------------------------------------------------------

    /**
     * Витягує список колонок із тексту запиту 1С: секція ВЫБРАТЬ … до ИЗ (чи іншого
     * ключового слова), з урахуванням псевдонімів КАК, крапкових шляхів і дужок.
     *
     * @param query текст запиту
     * @param dataSetName ім'я набору — для повідомлень про помилку
     * @return імена колонок у порядку вибірки
     */
    static List<String> parseQueryFields(String query, String dataSetName) {
        String text = sanitizeQuery(query);
        String statement = lastStatement(text);
        int selectEnd = selectKeywordEnd(statement);
        if (selectEnd < 0) {
            throw new IllegalArgumentException("Не вдалося знайти ВЫБРАТЬ у запиті набору " + dataSetName //$NON-NLS-1$
                    + ". Задайте список полів явно через fields."); //$NON-NLS-1$
        }
        int position = skipSelectModifiers(statement, selectEnd);
        List<String> items = selectItems(statement, position);

        List<String> names = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String item : items) {
            if (item.isBlank()) {
                continue;
            }
            String name = fieldName(item, dataSetName);
            if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Колонка «" + name + "» у запиті набору " + dataSetName //$NON-NLS-1$ //$NON-NLS-2$
                        + " повторюється. Задайте унікальні псевдоніми або список fields явно."); //$NON-NLS-1$
            }
            names.add(name);
            if (names.size() > MAX_FIELDS) {
                throw new IllegalArgumentException("Забагато колонок у запиті набору " + dataSetName //$NON-NLS-1$
                        + " (ліміт " + MAX_FIELDS + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        if (names.isEmpty()) {
            throw new IllegalArgumentException("Не вдалося визначити колонки запиту набору " + dataSetName //$NON-NLS-1$
                    + ". Задайте список полів явно через fields."); //$NON-NLS-1$
        }
        return names;
    }

    /**
     * Прибирає з тексту запиту те, що заважає розбору, зберігаючи зміщення символів:
     * коментарі {@code //}, вміст рядкових літералів і блоки-розширення СКД {@code {…}}.
     */
    private static String sanitizeQuery(String query) {
        StringBuilder out = new StringBuilder(query.length());
        int braces = 0;
        int index = 0;
        while (index < query.length()) {
            char symbol = query.charAt(index);
            if (symbol == '/' && index + 1 < query.length() && query.charAt(index + 1) == '/') {
                while (index < query.length() && query.charAt(index) != '\n') {
                    out.append(' ');
                    index++;
                }
                continue;
            }
            if (symbol == '{') {
                braces++;
                out.append(' ');
                index++;
                continue;
            }
            if (symbol == '}') {
                if (braces > 0) {
                    braces--;
                }
                out.append(' ');
                index++;
                continue;
            }
            if (symbol == '"') {
                // рядковий літерал: вміст глушимо, лапки лишаємо (поза блоками-розширеннями)
                out.append(braces > 0 ? ' ' : '"');
                index++;
                while (index < query.length()) {
                    char inner = query.charAt(index);
                    if (inner == '"' && index + 1 < query.length() && query.charAt(index + 1) == '"') {
                        out.append("  "); //$NON-NLS-1$
                        index += 2;
                        continue;
                    }
                    if (inner == '"') {
                        break;
                    }
                    out.append(inner == '\n' ? '\n' : ' ');
                    index++;
                }
                if (index < query.length()) {
                    out.append(braces > 0 ? ' ' : '"');
                    index++;
                }
                continue;
            }
            out.append(braces > 0 && symbol != '\n' ? ' ' : symbol);
            index++;
        }
        return out.toString();
    }

    /** Останній запит пакета (розділювач «;» поза дужками) — саме він дає поля СКД. */
    private static String lastStatement(String text) {
        int depth = 0;
        int start = 0;
        String last = text;
        for (int index = 0; index < text.length(); index++) {
            char symbol = text.charAt(index);
            if (symbol == '(') {
                depth++;
            } else if (symbol == ')') {
                depth = Math.max(0, depth - 1);
            } else if (symbol == ';' && depth == 0) {
                String candidate = text.substring(start, index);
                if (!candidate.isBlank()) {
                    last = candidate;
                }
                start = index + 1;
            }
        }
        String tail = text.substring(start);
        return tail.isBlank() ? last : tail;
    }

    /** Позиція одразу після першого ВЫБРАТЬ/SELECT поза дужками, або -1. */
    private static int selectKeywordEnd(String text) {
        int depth = 0;
        int index = 0;
        while (index < text.length()) {
            char symbol = text.charAt(index);
            if (symbol == '(') {
                depth++;
                index++;
            } else if (symbol == ')') {
                depth = Math.max(0, depth - 1);
                index++;
            } else if (isWordChar(symbol)) {
                int start = index;
                while (index < text.length() && isWordChar(text.charAt(index))) {
                    index++;
                }
                String word = text.substring(start, index);
                if (depth == 0 && ("ВЫБРАТЬ".equalsIgnoreCase(word) || "SELECT".equalsIgnoreCase(word))) { //$NON-NLS-1$ //$NON-NLS-2$
                    return index;
                }
            } else {
                index++;
            }
        }
        return -1;
    }

    /** Пропускає РАЗРЕШЕННЫЕ / РАЗЛИЧНЫЕ / ПЕРВЫЕ N одразу після ВЫБРАТЬ. */
    private static int skipSelectModifiers(String text, int from) {
        int position = from;
        while (true) {
            int[] word = nextWord(text, position);
            if (word == null) {
                return position;
            }
            String value = text.substring(word[0], word[1]).toUpperCase(Locale.ROOT);
            if (SELECT_MODIFIERS.contains(value)) {
                position = word[1];
                continue;
            }
            if ("ПЕРВЫЕ".equals(value) || "TOP".equals(value)) { //$NON-NLS-1$ //$NON-NLS-2$
                int[] count = nextWord(text, word[1]);
                position = count == null ? word[1] : count[1];
                continue;
            }
            return position;
        }
    }

    /** Розбиває секцію вибірки на елементи по комах верхнього рівня. */
    private static List<String> selectItems(String text, int from) {
        List<String> items = new ArrayList<>();
        int depth = 0;
        int itemStart = from;
        int index = from;
        while (index < text.length()) {
            char symbol = text.charAt(index);
            if (symbol == '(') {
                depth++;
                index++;
            } else if (symbol == ')') {
                depth = Math.max(0, depth - 1);
                index++;
            } else if (symbol == ',' && depth == 0) {
                items.add(text.substring(itemStart, index));
                itemStart = index + 1;
                index++;
            } else if (isWordChar(symbol)) {
                int start = index;
                while (index < text.length() && isWordChar(text.charAt(index))) {
                    index++;
                }
                if (depth == 0 && SELECT_STOP_WORDS.contains(
                        text.substring(start, index).toUpperCase(Locale.ROOT))) {
                    items.add(text.substring(itemStart, start));
                    return items;
                }
            } else {
                index++;
            }
        }
        items.add(text.substring(itemStart));
        return items;
    }

    /** Ім'я колонки: псевдонім після КАК або останній сегмент крапкового шляху. */
    private static String fieldName(String item, String dataSetName) {
        int depth = 0;
        int aliasStart = -1;
        int index = 0;
        while (index < item.length()) {
            char symbol = item.charAt(index);
            if (symbol == '(') {
                depth++;
                index++;
            } else if (symbol == ')') {
                depth = Math.max(0, depth - 1);
                index++;
            } else if (isWordChar(symbol)) {
                int start = index;
                while (index < item.length() && isWordChar(item.charAt(index))) {
                    index++;
                }
                String word = item.substring(start, index);
                if (depth == 0 && ("КАК".equalsIgnoreCase(word) || "AS".equalsIgnoreCase(word))) { //$NON-NLS-1$ //$NON-NLS-2$
                    aliasStart = index;
                }
            } else {
                index++;
            }
        }
        if (aliasStart >= 0) {
            String alias = item.substring(aliasStart).strip();
            if (!IDENTIFIER.matcher(alias).matches()) {
                throw new IllegalArgumentException("Не вдалося прочитати псевдонім «" + shorten(alias) //$NON-NLS-1$
                        + "» у запиті набору " + dataSetName + ". Задайте fields явно."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return alias;
        }
        String plain = item.strip();
        if (DATA_PATH.matcher(plain).matches()) {
            int dot = plain.lastIndexOf('.');
            return dot < 0 ? plain : plain.substring(dot + 1);
        }
        throw new IllegalArgumentException("Не вдалося визначити ім'я колонки для «" + shorten(plain) //$NON-NLS-1$
                + "» у запиті набору " + dataSetName //$NON-NLS-1$
                + ". Додайте псевдонім (… КАК ІмʼяПоля) або задайте список fields явно."); //$NON-NLS-1$
    }

    private static boolean isWordChar(char symbol) {
        return Character.isLetterOrDigit(symbol) || symbol == '_';
    }

    /** Наступне слово після позиції index (пропускаючи пробіли), або null. */
    private static int[] nextWord(String text, int index) {
        int position = index;
        while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
            position++;
        }
        if (position >= text.length() || !isWordChar(text.charAt(position))) {
            return null;
        }
        int start = position;
        while (position < text.length() && isWordChar(text.charAt(position))) {
            position++;
        }
        return new int[] {start, position};
    }

    private static String shorten(String value) {
        String single = value.replaceAll("\\s+", " ").strip(); //$NON-NLS-1$ //$NON-NLS-2$
        return single.length() > 60 ? single.substring(0, 60) + "…" : single; //$NON-NLS-1$
    }

    // ------------------------------------------------------------------
    // Стан збірки
    // ------------------------------------------------------------------

    /** Набір даних після розбору специфікації. */
    private static final class DataSet {
        String name;
        String type;
        String query;
        String objectName;
        Boolean autoFillFields;
        final List<Field> fields = new ArrayList<>();
        final List<DataSet> items = new ArrayList<>();
    }

    /** Поле набору або обчислюване поле ({@code source} — вихідне поле чи вираз). */
    private static final class Field {
        String dataPath;
        String source;
        String title;
        String type;
    }

    // ------------------------------------------------------------------
    // Допоміжні
    // ------------------------------------------------------------------

    /** Локалізований рядок 1С: {@code <tag xsi:type="v8:LocalStringType"><v8:item>…}. */
    private static void emitLocalString(StringBuilder xml, String tag, String value, int level, String lang) {
        if (value == null || value.isBlank()) {
            return;
        }
        String pad = indent(level);
        xml.append(pad).append('<').append(tag).append(" xsi:type=\"v8:LocalStringType\">\n"); //$NON-NLS-1$
        xml.append(indent(level + 1)).append("<v8:item>\n"); //$NON-NLS-1$
        xml.append(indent(level + 2)).append("<v8:lang>").append(escape(lang)).append("</v8:lang>\n"); //$NON-NLS-1$ //$NON-NLS-2$
        xml.append(indent(level + 2)).append("<v8:content>").append(escape(value)).append("</v8:content>\n"); //$NON-NLS-1$ //$NON-NLS-2$
        xml.append(indent(level + 1)).append("</v8:item>\n"); //$NON-NLS-1$
        xml.append(pad).append("</").append(tag).append(">\n"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String indent(int level) {
        return "\t".repeat(Math.max(0, level)); //$NON-NLS-1$
    }

    private static JsonArray array(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        if (!object.get(key).isJsonArray()) {
            throw new IllegalArgumentException("Поле " + key + " має бути масивом"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return object.getAsJsonArray(key);
    }

    private static String text(JsonObject object, String key, String fallback) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        String value = object.get(key).getAsString();
        return value.isEmpty() ? fallback : value;
    }

    private static String required(JsonObject object, String key, String where) {
        String value = text(object, key, null);
        if (value == null) {
            throw new IllegalArgumentException(where + ": обов'язкове поле " + key); //$NON-NLS-1$
        }
        return value;
    }

    private static boolean flag(JsonObject object, String key, boolean fallback) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        return object.get(key).getAsBoolean();
    }

    private static void requireIdentifier(String value, String what) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(what + " має бути ідентифікатором 1С " //$NON-NLS-1$
                    + "(літери/цифри/підкреслення, не з цифри): " + value); //$NON-NLS-1$
        }
    }

    private static void requireDataPath(String value, String what) {
        if (value == null || !DATA_PATH.matcher(value).matches()) {
            throw new IllegalArgumentException(what + ": некоректний шлях до поля «" + value //$NON-NLS-1$
                    + "» (очікується Поле або Поле.Підполе)"); //$NON-NLS-1$
        }
    }

    /** Екранування тексту XML; заборонені керівні символи замінюються пробілом. */
    private static String escape(String value) {
        StringBuilder result = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char symbol = value.charAt(index);
            switch (symbol) {
            case '&':
                result.append("&amp;"); //$NON-NLS-1$
                break;
            case '<':
                result.append("&lt;"); //$NON-NLS-1$
                break;
            case '>':
                result.append("&gt;"); //$NON-NLS-1$
                break;
            default:
                if (symbol < 0x20 && symbol != '\t' && symbol != '\n' && symbol != '\r') {
                    result.append(' ');
                } else {
                    result.append(symbol);
                }
                break;
            }
        }
        return result.toString();
    }

    /** Зворотне перетворення для читання імен наборів із наявного XML. */
    private static String unescape(String value) {
        return value.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                .replace("&apos;", "'").replace("&amp;", "&"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }
}
