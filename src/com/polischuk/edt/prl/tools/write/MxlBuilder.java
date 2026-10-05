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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Генератор макетів табличних документів (друкованих форм): компактний JSON-опис →
 * повний Template.mxlx у стандартній схемі 1С {@code http://v8.1c.ru/8.2/data/spreadsheet}
 * (той самий формат, який читає {@code GetMxlTool}).
 *
 * <p>Специфікація (усі поля, крім {@code areas}, необов'язкові):
 *
 * <pre>
 * {
 *   "lang": "ru",                       // мова текстів комірок (типово ru)
 *   "columns": 8,                       // ширина документа в колонках (типово — за вмістом)
 *   "columnWidths": [200, 60, 80],      // ширина колонок (одиниці EDT, типова колонка = 72)
 *   "areas": [
 *     {"name": "Шапка", "type": "Rows", "rows": [
 *        {"height": 30, "cells": [
 *           {"text": "Рахунок №", "bold": true, "align": "Center", "span": 3},
 *           {"parameter": "НомерДокумента"}
 *        ]}
 *     ]}
 *   ]
 * }
 * </pre>
 *
 * <p>Області укладаються згори вниз у порядку оголошення: перша область займає рядки
 * 0..n, наступна — далі. Комірки в рядку йдуть послідовно зліва направо; {@code col}
 * задає явну колонку, {@code span}/{@code rowSpan} породжують {@code <merge>}.
 *
 * <p>Комірка — це або текст ({@code text}), або параметр ({@code parameter}), або
 * шаблонний текст ({@code template} чи {@code text} з плейсхолдерами {@code [Параметр]} —
 * розпізнається автоматично). Одночасно текст і параметр в одній комірці формат не
 * допускає (у 294 реальних макетах такого немає).
 *
 * <p>Свідомі спрощення (мінімалізм важливіший за повноту): один набір колонок (без
 * {@code columnsID}), без малюнків/картинок, без вкладених і зведених таблиць, шрифт —
 * типовий шрифт стилю з ознаками жирний/курсив, рамка — суцільна лінія товщиною 1.
 */
public final class MxlBuilder {

    /** Типова ширина колонки табличного документа (одиниці EDT). */
    private static final int DEFAULT_COLUMN_WIDTH = 72;

    /** Стеля рядків — щоб зіпсована специфікація не породила гігантський файл. */
    private static final int MAX_ROWS = 10000;

    /** Стеля колонок. */
    private static final int MAX_COLUMNS = 1000;

    /** Стеля довжини тексту однієї комірки. */
    private static final int MAX_CELL_TEXT = 10000;

    /** Висота порожнього макета (createTemplate без spec) — чистий бланк. */
    private static final int EMPTY_ROWS = 3;

    /** Ширина порожнього макета. */
    private static final int EMPTY_COLUMNS = 10;

    /** Ідентифікатор 1С: ім'я області, параметра, параметра розшифровки. */
    private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}_][\\p{L}\\p{Nd}_]*"); //$NON-NLS-1$

    /** Плейсхолдер шаблонного заповнення [Параметр] — як у GetMxlTool. */
    private static final Pattern TEMPLATE_PARAM = Pattern.compile("\\[([\\p{L}_][\\p{L}\\p{Nd}_]*)\\]"); //$NON-NLS-1$

    /** Опис мов для languageSettings: код → назва (для решти беремо сам код). */
    private static final Map<String, String> LANGUAGE_NAMES = Map.of(
            "ru", "Русский", //$NON-NLS-1$ //$NON-NLS-2$
            "uk", "Українська", //$NON-NLS-1$ //$NON-NLS-2$
            "en", "English", //$NON-NLS-1$ //$NON-NLS-2$
            "kz", "Казахский"); //$NON-NLS-1$ //$NON-NLS-2$

    private static final String[] H_ALIGN = { "Auto", "Left", "Center", "Right", "Justify" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

    private static final String[] V_ALIGN = { "Top", "Center", "Bottom" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private static final String[] AREA_TYPES = { "Rows", "Columns", "Rectangle" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private MxlBuilder() {
    }

    // ------------------------------------------------------------------
    // Публічний вхід
    // ------------------------------------------------------------------

    /**
     * Будує повний текст Template.mxlx зі специфікації. Порожня (або null)
     * специфікація дає валідний порожній макет.
     *
     * @param spec компактний опис макета, див. опис класу
     * @return XML-текст файлу Template.mxlx (UTF-8, без BOM)
     */
    public static String buildMxlx(JsonObject spec) {
        JsonObject source = spec == null ? new JsonObject() : spec;
        Doc doc = new Doc();
        doc.lang = text(source, "lang", "ru"); //$NON-NLS-1$ //$NON-NLS-2$

        // формат №1 — типовий (defaultFormatIndex); індекси форматів 1-базовані
        int defaultFormat = doc.format("<width>" + DEFAULT_COLUMN_WIDTH + "</width>"); //$NON-NLS-1$ //$NON-NLS-2$

        layoutAreas(source, doc);

        int[] columnFormats = columnFormats(source, doc);
        int columnCount = columnCount(source, doc, columnFormats.length);
        int height = doc.rows.isEmpty() ? EMPTY_ROWS : doc.rows.size();
        // об'єднання по вертикалі можуть виходити за останній заповнений рядок
        for (Merge merge : doc.merges) {
            height = Math.max(height, merge.row + merge.height + 1);
        }
        if (height > MAX_ROWS) {
            throw new IllegalArgumentException("Забагато рядків у макеті (ліміт " + MAX_ROWS + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        return emit(doc, defaultFormat, columnCount, columnFormats, height);
    }

    // ------------------------------------------------------------------
    // Розкладка: JSON → рядки, комірки, області, об'єднання
    // ------------------------------------------------------------------

    /** Розкладає області (або самотній масив rows) у документ згори вниз. */
    private static void layoutAreas(JsonObject spec, Doc doc) {
        JsonArray areas = array(spec, "areas"); //$NON-NLS-1$
        if (areas == null) {
            JsonArray rows = array(spec, "rows"); //$NON-NLS-1$
            if (rows != null) {
                layoutRows(rows, doc, 0);
            }
            return;
        }
        for (JsonElement element : areas) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("areas: кожен елемент має бути об'єктом " //$NON-NLS-1$
                        + "{name, type, rows}"); //$NON-NLS-1$
            }
            layoutArea(element.getAsJsonObject(), doc);
        }
    }

    /** Одна іменована область: її рядки лягають одразу за попередньою областю. */
    private static void layoutArea(JsonObject area, Doc doc) {
        String name = text(area, "name", null); //$NON-NLS-1$
        if (name == null || !IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("Ім'я області має бути ідентифікатором 1С " //$NON-NLS-1$
                    + "(літери/цифри/підкреслення, не з цифри): " + name); //$NON-NLS-1$
        }
        for (NamedArea existing : doc.areas) {
            if (existing.name.equalsIgnoreCase(name)) {
                throw new IllegalArgumentException("Область з таким ім'ям уже є: " + name); //$NON-NLS-1$
            }
        }
        String type = enumValue(area, "type", AREA_TYPES, "Rows"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonArray rows = array(area, "rows"); //$NON-NLS-1$
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("Область " + name + ": потрібен непорожній масив rows"); //$NON-NLS-1$
        }

        int beginRow = doc.rows.size();
        int[] columnBounds = layoutRows(rows, doc, beginRow);
        int endRow = doc.rows.size() - 1;

        NamedArea named = new NamedArea();
        named.name = name;
        named.type = type;
        if ("Columns".equals(type)) { //$NON-NLS-1$
            named.beginRow = -1;
            named.endRow = -1;
        } else {
            named.beginRow = beginRow;
            named.endRow = endRow;
        }
        if ("Rows".equals(type)) { //$NON-NLS-1$
            named.beginColumn = -1;
            named.endColumn = -1;
        } else {
            named.beginColumn = number(area, "beginColumn", columnBounds[0] < 0 ? 0 : columnBounds[0]); //$NON-NLS-1$
            named.endColumn = number(area, "endColumn", columnBounds[1] < 0 ? 0 : columnBounds[1]); //$NON-NLS-1$
        }
        doc.areas.add(named);
    }

    /**
     * Розкладає масив рядків починаючи з рядка startRow.
     *
     * @return {мінімальна, максимальна} задіяна колонка ({-1, -1} — комірок немає)
     */
    private static int[] layoutRows(JsonArray rows, Doc doc, int startRow) {
        int minColumn = -1;
        int maxColumn = -1;
        for (int index = 0; index < rows.size(); index++) {
            JsonElement element = rows.get(index);
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("rows: кожен рядок — об'єкт {cells:[…]}"); //$NON-NLS-1$
            }
            int rowIndex = startRow + index;
            if (rowIndex >= MAX_ROWS) {
                throw new IllegalArgumentException("Забагато рядків у макеті (ліміт " + MAX_ROWS + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            JsonObject rowSpec = element.getAsJsonObject();
            Row row = doc.row(rowIndex);
            int rowHeight = number(rowSpec, "height", 0); //$NON-NLS-1$
            if (rowHeight > 0) {
                row.formatIndex = doc.format("<height>" + rowHeight + "</height>"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            int[] bounds = layoutCells(rowSpec, doc, row, rowIndex);
            if (bounds[0] >= 0) {
                minColumn = minColumn < 0 ? bounds[0] : Math.min(minColumn, bounds[0]);
                maxColumn = Math.max(maxColumn, bounds[1]);
            }
        }
        return new int[] { minColumn, maxColumn };
    }

    /** Комірки одного рядка: послідовний курсор колонок, merge зі span/rowSpan. */
    private static int[] layoutCells(JsonObject rowSpec, Doc doc, Row row, int rowIndex) {
        JsonArray cells = array(rowSpec, "cells"); //$NON-NLS-1$
        int minColumn = -1;
        int maxColumn = -1;
        int cursor = 0;
        if (cells == null) {
            return new int[] { minColumn, maxColumn };
        }
        for (JsonElement element : cells) {
            JsonObject cellSpec = toCellSpec(element);
            int column = number(cellSpec, "col", cursor); //$NON-NLS-1$
            int span = Math.max(1, number(cellSpec, "span", 1)); //$NON-NLS-1$
            int rowSpan = Math.max(1, number(cellSpec, "rowSpan", 1)); //$NON-NLS-1$
            if (column < 0 || column + span > MAX_COLUMNS) {
                throw new IllegalArgumentException("Колонка поза межами (0.." + MAX_COLUMNS + "): " + column); //$NON-NLS-1$ //$NON-NLS-2$
            }
            cursor = column + span;

            Cell cell = buildCell(cellSpec, doc);
            if (cell != null) {
                cell.col = column;
                row.cells.add(cell);
            }
            if (span > 1 || rowSpan > 1) {
                Merge merge = new Merge();
                merge.row = rowIndex;
                merge.col = column;
                merge.width = span - 1;
                merge.height = rowSpan - 1;
                doc.merges.add(merge);
            }
            minColumn = minColumn < 0 ? column : Math.min(minColumn, column);
            maxColumn = Math.max(maxColumn, column + span - 1);
            doc.maxColumn = Math.max(doc.maxColumn, column + span - 1);
        }
        row.cells.sort(Comparator.comparingInt(cell -> cell.col));
        int previous = -1;
        for (Cell cell : row.cells) {
            if (cell.col == previous) {
                throw new IllegalArgumentException("Рядок " + rowIndex + ": дві комірки в колонці " + cell.col); //$NON-NLS-1$ //$NON-NLS-2$
            }
            previous = cell.col;
        }
        return new int[] { minColumn, maxColumn };
    }

    /** Дозволяємо скорочення: рядок у cells — це просто текст комірки. */
    private static JsonObject toCellSpec(JsonElement element) {
        if (element.isJsonObject()) {
            return element.getAsJsonObject();
        }
        if (element.isJsonPrimitive()) {
            JsonObject shorthand = new JsonObject();
            shorthand.addProperty("text", element.getAsString()); //$NON-NLS-1$
            return shorthand;
        }
        throw new IllegalArgumentException("cells: комірка — об'єкт {text|parameter|template, …} " //$NON-NLS-1$
                + "або просто рядок тексту"); //$NON-NLS-1$
    }

    /** Одна комірка: вміст (текст / параметр / шаблон) + індекс формату. */
    private static Cell buildCell(JsonObject spec, Doc doc) {
        Cell cell = new Cell();
        String fillType = null;

        String parameter = text(spec, "parameter", null); //$NON-NLS-1$
        String template = text(spec, "template", null); //$NON-NLS-1$
        String plain = text(spec, "text", null); //$NON-NLS-1$
        if (parameter != null && (template != null || plain != null)) {
            throw new IllegalArgumentException("Комірка не може мати одночасно parameter і text/template " //$NON-NLS-1$
                    + "(формат mxl цього не підтримує): " + parameter); //$NON-NLS-1$
        }
        if (parameter != null) {
            requireIdentifier(parameter, "parameter"); //$NON-NLS-1$
            cell.parameter = parameter;
            fillType = "Parameter"; //$NON-NLS-1$
        } else if (template != null) {
            cell.text = limit(template);
            fillType = "Template"; //$NON-NLS-1$
        } else if (plain != null) {
            cell.text = limit(plain);
            // текст із плейсхолдерами [Параметр] — це шаблонне заповнення
            if (TEMPLATE_PARAM.matcher(plain).find()) {
                fillType = "Template"; //$NON-NLS-1$
            }
        }

        String detail = text(spec, "detailParameter", null); //$NON-NLS-1$
        if (detail != null) {
            requireIdentifier(detail, "detailParameter"); //$NON-NLS-1$
            cell.detailParameter = detail;
        }

        String format = cellFormat(spec, doc, fillType);
        boolean empty = cell.parameter == null && cell.text == null && cell.detailParameter == null;
        if (empty && format.isEmpty()) {
            return null; // порожня комірка без оформлення — не пишемо
        }
        cell.formatIndex = doc.format(format);
        return cell;
    }

    /** Тіло елемента {@code <format>} комірки: шрифт, рамка, вирівнювання, тип заповнення, формат. */
    private static String cellFormat(JsonObject spec, Doc doc, String fillType) {
        List<String> lines = new ArrayList<>();
        boolean bold = flag(spec, "bold"); //$NON-NLS-1$
        boolean italic = flag(spec, "italic"); //$NON-NLS-1$
        if (bold || italic) {
            String font = "<font ref=\"style:NormalTextFont\" bold=\"" + bold + "\" italic=\"" + italic //$NON-NLS-1$ //$NON-NLS-2$
                    + "\" underline=\"false\" strikeout=\"false\" kind=\"StyleItem\"/>"; //$NON-NLS-1$
            lines.add("<font>" + doc.font(font) + "</font>"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (flag(spec, "border")) { //$NON-NLS-1$
            doc.needLine = true;
            lines.add("<border>0</border>"); //$NON-NLS-1$
        }
        String align = enumValue(spec, "align", H_ALIGN, null); //$NON-NLS-1$
        if (align != null) {
            lines.add("<horizontalAlignment>" + align + "</horizontalAlignment>"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String valign = enumValue(spec, "valign", V_ALIGN, null); //$NON-NLS-1$
        if (valign != null) {
            lines.add("<verticalAlignment>" + valign + "</verticalAlignment>"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (flag(spec, "wrap")) { //$NON-NLS-1$
            lines.add("<textPlacement>Wrap</textPlacement>"); //$NON-NLS-1$
        }
        if (fillType != null) {
            lines.add("<fillType>" + fillType + "</fillType>"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String mask = text(spec, "format", null); //$NON-NLS-1$
        if (mask != null) {
            lines.add("<format>"); //$NON-NLS-1$
            lines.add("\t<v8:item>"); //$NON-NLS-1$
            lines.add("\t\t<v8:lang>" + escape(doc.lang) + "</v8:lang>"); //$NON-NLS-1$ //$NON-NLS-2$
            lines.add("\t\t<v8:content>" + escape(mask) + "</v8:content>"); //$NON-NLS-1$ //$NON-NLS-2$
            lines.add("\t</v8:item>"); //$NON-NLS-1$
            lines.add("</format>"); //$NON-NLS-1$
        }
        return String.join("\n", lines); //$NON-NLS-1$
    }

    /** Формати колонок за columnWidths: 0 — типова ширина (елемент columnsItem не потрібен). */
    private static int[] columnFormats(JsonObject spec, Doc doc) {
        JsonArray widths = array(spec, "columnWidths"); //$NON-NLS-1$
        if (widths == null) {
            return new int[0];
        }
        if (widths.size() > MAX_COLUMNS) {
            throw new IllegalArgumentException("columnWidths: забагато колонок (ліміт " + MAX_COLUMNS + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        int[] formats = new int[widths.size()];
        for (int index = 0; index < widths.size(); index++) {
            JsonElement element = widths.get(index);
            int width = element.isJsonNull() ? 0 : element.getAsInt();
            if (width <= 0 || width == DEFAULT_COLUMN_WIDTH) {
                continue;
            }
            formats[index] = doc.format("<width>" + width + "</width>"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return formats;
    }

    /** Ширина документа: явна columns, інакше — за вмістом (мінімум 1). */
    private static int columnCount(JsonObject spec, Doc doc, int widthCount) {
        int declared = number(spec, "columns", 0); //$NON-NLS-1$
        int used = Math.max(doc.maxColumn + 1, widthCount);
        int count = Math.max(declared, used);
        if (count <= 0) {
            count = EMPTY_COLUMNS;
        }
        if (count > MAX_COLUMNS) {
            throw new IllegalArgumentException("Забагато колонок (ліміт " + MAX_COLUMNS + "): " + count); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return count;
    }

    // ------------------------------------------------------------------
    // Серіалізація в XML
    // ------------------------------------------------------------------

    /** Складає документ у порядку елементів, який пише сам EDT. */
    private static String emit(Doc doc, int defaultFormat, int columnCount, int[] columnFormats, int height) {
        StringBuilder xml = new StringBuilder(4096);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"); //$NON-NLS-1$
        xml.append("<document xmlns=\"http://v8.1c.ru/8.2/data/spreadsheet\"") //$NON-NLS-1$
                .append(" xmlns:style=\"http://v8.1c.ru/8.1/data/ui/style\"") //$NON-NLS-1$
                .append(" xmlns:v8=\"http://v8.1c.ru/8.1/data/core\"") //$NON-NLS-1$
                .append(" xmlns:v8ui=\"http://v8.1c.ru/8.1/data/ui\"") //$NON-NLS-1$
                .append(" xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"") //$NON-NLS-1$
                .append(" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n"); //$NON-NLS-1$

        emitLanguageSettings(xml, doc.lang);
        emitColumns(xml, columnCount, columnFormats);
        emitRows(xml, doc);

        xml.append("\t<templateMode>true</templateMode>\n"); //$NON-NLS-1$
        xml.append("\t<defaultFormatIndex>").append(defaultFormat).append("</defaultFormatIndex>\n"); //$NON-NLS-1$ //$NON-NLS-2$
        xml.append("\t<height>").append(height).append("</height>\n"); //$NON-NLS-1$ //$NON-NLS-2$
        xml.append("\t<vgRows>").append(height).append("</vgRows>\n"); //$NON-NLS-1$ //$NON-NLS-2$

        for (Merge merge : doc.merges) {
            xml.append("\t<merge>\n\t\t<r>").append(merge.row).append("</r>\n\t\t<c>") //$NON-NLS-1$ //$NON-NLS-2$
                    .append(merge.col).append("</c>\n"); //$NON-NLS-1$
            if (merge.width > 0) {
                xml.append("\t\t<w>").append(merge.width).append("</w>\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (merge.height > 0) {
                xml.append("\t\t<h>").append(merge.height).append("</h>\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            xml.append("\t</merge>\n"); //$NON-NLS-1$
        }

        for (NamedArea area : doc.areas) {
            xml.append("\t<namedItem xsi:type=\"NamedItemCells\">\n"); //$NON-NLS-1$
            xml.append("\t\t<name>").append(escape(area.name)).append("</name>\n"); //$NON-NLS-1$ //$NON-NLS-2$
            xml.append("\t\t<area>\n\t\t\t<type>").append(area.type).append("</type>\n"); //$NON-NLS-1$ //$NON-NLS-2$
            xml.append("\t\t\t<beginRow>").append(area.beginRow).append("</beginRow>\n"); //$NON-NLS-1$ //$NON-NLS-2$
            xml.append("\t\t\t<endRow>").append(area.endRow).append("</endRow>\n"); //$NON-NLS-1$ //$NON-NLS-2$
            xml.append("\t\t\t<beginColumn>").append(area.beginColumn).append("</beginColumn>\n"); //$NON-NLS-1$ //$NON-NLS-2$
            xml.append("\t\t\t<endColumn>").append(area.endColumn).append("</endColumn>\n"); //$NON-NLS-1$ //$NON-NLS-2$
            xml.append("\t\t</area>\n\t</namedItem>\n"); //$NON-NLS-1$
        }

        if (doc.needLine) {
            xml.append("\t<line width=\"1\" gap=\"false\">\n") //$NON-NLS-1$
                    .append("\t\t<v8ui:style xsi:type=\"v8ui:SpreadsheetDocumentCellLineType\">Solid") //$NON-NLS-1$
                    .append("</v8ui:style>\n\t</line>\n"); //$NON-NLS-1$
        }
        for (String font : doc.fonts.keySet()) {
            xml.append('\t').append(font).append('\n');
        }
        for (String body : doc.formats.keySet()) {
            if (body.isEmpty()) {
                xml.append("\t<format/>\n"); //$NON-NLS-1$
                continue;
            }
            xml.append("\t<format>\n"); //$NON-NLS-1$
            for (String line : body.split("\n")) { //$NON-NLS-1$
                xml.append("\t\t").append(line).append('\n'); //$NON-NLS-1$
            }
            xml.append("\t</format>\n"); //$NON-NLS-1$
        }
        xml.append("</document>\n"); //$NON-NLS-1$
        return xml.toString();
    }

    private static void emitLanguageSettings(StringBuilder xml, String lang) {
        String name = LANGUAGE_NAMES.getOrDefault(lang.toLowerCase(Locale.ROOT), lang);
        xml.append("\t<languageSettings>\n"); //$NON-NLS-1$
        xml.append("\t\t<currentLanguage>").append(escape(lang)).append("</currentLanguage>\n"); //$NON-NLS-1$ //$NON-NLS-2$
        xml.append("\t\t<defaultLanguage>").append(escape(lang)).append("</defaultLanguage>\n"); //$NON-NLS-1$ //$NON-NLS-2$
        xml.append("\t\t<languageInfo>\n\t\t\t<id>").append(escape(lang)).append("</id>\n"); //$NON-NLS-1$ //$NON-NLS-2$
        xml.append("\t\t\t<code>").append(escape(name)).append("</code>\n"); //$NON-NLS-1$ //$NON-NLS-2$
        xml.append("\t\t\t<description>").append(escape(name)).append("</description>\n"); //$NON-NLS-1$ //$NON-NLS-2$
        xml.append("\t\t</languageInfo>\n\t</languageSettings>\n"); //$NON-NLS-1$
    }

    private static void emitColumns(StringBuilder xml, int columnCount, int[] columnFormats) {
        xml.append("\t<columns>\n\t\t<size>").append(columnCount).append("</size>\n"); //$NON-NLS-1$ //$NON-NLS-2$
        for (int index = 0; index < columnFormats.length; index++) {
            if (columnFormats[index] == 0) {
                continue;
            }
            xml.append("\t\t<columnsItem>\n\t\t\t<index>").append(index).append("</index>\n"); //$NON-NLS-1$ //$NON-NLS-2$
            xml.append("\t\t\t<column>\n\t\t\t\t<formatIndex>").append(columnFormats[index]) //$NON-NLS-1$
                    .append("</formatIndex>\n\t\t\t</column>\n\t\t</columnsItem>\n"); //$NON-NLS-1$
        }
        xml.append("\t</columns>\n"); //$NON-NLS-1$
    }

    private static void emitRows(StringBuilder xml, Doc doc) {
        for (int index = 0; index < doc.rows.size(); index++) {
            Row row = doc.rows.get(index);
            if (row.cells.isEmpty() && row.formatIndex == 0) {
                continue; // порожній рядок без висоти — розріджений формат його пропускає
            }
            xml.append("\t<rowsItem>\n\t\t<index>").append(index).append("</index>\n\t\t<row>\n"); //$NON-NLS-1$ //$NON-NLS-2$
            if (row.formatIndex != 0) {
                xml.append("\t\t\t<formatIndex>").append(row.formatIndex).append("</formatIndex>\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (row.cells.isEmpty()) {
                xml.append("\t\t\t<empty>true</empty>\n"); //$NON-NLS-1$
            }
            int previous = -1;
            for (Cell cell : row.cells) {
                xml.append("\t\t\t<c>\n"); //$NON-NLS-1$
                if (cell.col != previous + 1) {
                    xml.append("\t\t\t\t<i>").append(cell.col).append("</i>\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                xml.append("\t\t\t\t<c>\n\t\t\t\t\t<f>").append(cell.formatIndex).append("</f>\n"); //$NON-NLS-1$ //$NON-NLS-2$
                if (cell.parameter != null) {
                    xml.append("\t\t\t\t\t<parameter>").append(escape(cell.parameter)).append("</parameter>\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                if (cell.text != null) {
                    xml.append("\t\t\t\t\t<tl>\n\t\t\t\t\t\t<v8:item>\n"); //$NON-NLS-1$
                    xml.append("\t\t\t\t\t\t\t<v8:lang>").append(escape(doc.lang)).append("</v8:lang>\n"); //$NON-NLS-1$ //$NON-NLS-2$
                    xml.append("\t\t\t\t\t\t\t<v8:content>").append(escape(cell.text)).append("</v8:content>\n"); //$NON-NLS-1$ //$NON-NLS-2$
                    xml.append("\t\t\t\t\t\t</v8:item>\n\t\t\t\t\t</tl>\n"); //$NON-NLS-1$
                }
                if (cell.detailParameter != null) {
                    xml.append("\t\t\t\t\t<detailParameter>").append(escape(cell.detailParameter)) //$NON-NLS-1$
                            .append("</detailParameter>\n"); //$NON-NLS-1$
                }
                xml.append("\t\t\t\t</c>\n\t\t\t</c>\n"); //$NON-NLS-1$
                previous = cell.col;
            }
            xml.append("\t\t</row>\n\t</rowsItem>\n"); //$NON-NLS-1$
        }
    }

    // ------------------------------------------------------------------
    // Стан збірки
    // ------------------------------------------------------------------

    /** Накопичувач документа: рядки, області, об'єднання, таблиці форматів і шрифтів. */
    private static final class Doc {
        String lang = "ru"; //$NON-NLS-1$
        final List<Row> rows = new ArrayList<>();
        final List<NamedArea> areas = new ArrayList<>();
        final List<Merge> merges = new ArrayList<>();
        /** Тіло {@code <format>} → 1-базований індекс (0 у формату означає «немає»). */
        final Map<String, Integer> formats = new LinkedHashMap<>();
        /** XML шрифту → 0-базований індекс (так на нього посилається {@code <font>}). */
        final Map<String, Integer> fonts = new LinkedHashMap<>();
        boolean needLine;
        int maxColumn = -1;

        int format(String body) {
            Integer index = formats.get(body);
            if (index == null) {
                index = Integer.valueOf(formats.size() + 1);
                formats.put(body, index);
            }
            return index.intValue();
        }

        int font(String xml) {
            Integer index = fonts.get(xml);
            if (index == null) {
                index = Integer.valueOf(fonts.size());
                fonts.put(xml, index);
            }
            return index.intValue();
        }

        Row row(int index) {
            while (rows.size() <= index) {
                rows.add(new Row());
            }
            return rows.get(index);
        }
    }

    /** Рядок документа. */
    private static final class Row {
        final List<Cell> cells = new ArrayList<>();
        int formatIndex;
    }

    /** Комірка після розкладки. */
    private static final class Cell {
        int col;
        int formatIndex;
        String parameter;
        String detailParameter;
        String text;
    }

    /** Іменована область (NamedItemCells). */
    private static final class NamedArea {
        String name;
        String type;
        int beginRow = -1;
        int endRow = -1;
        int beginColumn = -1;
        int endColumn = -1;
    }

    /** Об'єднання комірок: w/h — кількість ДОДАТКОВИХ колонок/рядків. */
    private static final class Merge {
        int row;
        int col;
        int width;
        int height;
    }

    // ------------------------------------------------------------------
    // Допоміжні
    // ------------------------------------------------------------------

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

    private static int number(JsonObject object, String key, int fallback) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(key).getAsInt();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Поле " + key + " має бути цілим числом"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static boolean flag(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() && object.get(key).getAsBoolean();
    }

    /** Значення переліку без урахування регістру; null — якщо поле відсутнє і немає типового. */
    private static String enumValue(JsonObject object, String key, String[] allowed, String fallback) {
        String value = text(object, key, null);
        if (value == null) {
            return fallback;
        }
        for (String candidate : allowed) {
            if (candidate.equalsIgnoreCase(value)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Поле " + key + ": допустимі значення " //$NON-NLS-1$ //$NON-NLS-2$
                + String.join("|", allowed) + ", отримано " + value); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void requireIdentifier(String value, String what) {
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(what + " має бути ідентифікатором 1С " //$NON-NLS-1$
                    + "(літери/цифри/підкреслення, не з цифри): " + value); //$NON-NLS-1$
        }
    }

    private static String limit(String value) {
        return value.length() > MAX_CELL_TEXT ? value.substring(0, MAX_CELL_TEXT) : value;
    }

    /** Екранування тексту для XML; заборонені керівні символи замінюються пробілом. */
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
            case '"':
                result.append("&quot;"); //$NON-NLS-1$
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
}
