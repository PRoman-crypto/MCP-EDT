/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.metadata;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.polischuk.edt.prl.edt.MetadataIndex;
import com.polischuk.edt.prl.edt.V8Access;
import com.polischuk.edt.prl.tools.McpTool;

import com._1c.g5.v8.dt.core.platform.IV8Project;

/**
 * Структура макета табличного документа (MXL, друкована форма): іменовані області
 * (рядкові/колонкові/прямокутні) з параметрами, комірки з текстами, параметрами
 * ({Параметр}) і шаблонами ([Вставка]), розмірність, набори колонок.
 *
 * <p>Працює файлово: EDT зберігає табличні документи як Template.mxlx — XML у
 * стандартній схемі 1С {@code http://v8.1c.ru/8.2/data/spreadsheet}. Парсинг —
 * потоковий StAX без залежності від бандлів com._1c.g5.v8.dt.moxel*, тож не
 * потребує ні BM-моделі, ні UI. Легасі-бінарний формат .mxl (префікс "MOXCEL")
 * у сучасних проєктах EDT не зустрічається і не підтримується.
 */
public final class GetMxlTool implements McpTool {

    /** Максимум комірок у відповіді. */
    private static final int MAX_CELLS = 2000;

    /** Максимум довжини тексту однієї комірки. */
    private static final int MAX_CELL_TEXT = 300;

    /** Максимум параметрів у переліку однієї області. */
    private static final int MAX_AREA_PARAMS = 100;

    /**
     * Плейсхолдери шаблонного заповнення: [Параметр]. Параметр — ідентифікатор 1С
     * (літери/цифри/підкреслення), тож [5], [c,e] чи довільний текст у дужках — не параметри.
     */
    private static final Pattern TEMPLATE_PARAM = Pattern.compile("\\[([\\p{L}_][\\p{L}\\p{Nd}_]*)\\]"); //$NON-NLS-1$

    @Override
    public String name() {
        return "get_mxl"; //$NON-NLS-1$
    }

    @Override
    public String description() {
        return "Структура макета табличного документа (MXL, друкована форма): іменовані області з параметрами, " //$NON-NLS-1$
                + "комірки з текстами і параметрами, розмірність. Список макетів об'єкта — get_template."; //$NON-NLS-1$
    }

    @Override
    public JsonObject inputSchema() {
        return JsonParser.parseString("""
                {"type":"object","properties":{
                  "project":{"type":"string","description":"Ім'я проєкту EDT (необов'язково, якщо проєкт один)"},
                  "kind":{"type":"string","description":"Вид власника: DataProcessor, Report, CommonTemplate, Catalog, Document…"},
                  "name":{"type":"string","description":"Ім'я об'єкта-власника (для CommonTemplate — ім'я макета)"},
                  "template":{"type":"string","description":"Ім'я макета (для CommonTemplate не потрібне); список макетів — get_template"},
                  "path":{"type":"string","description":"Або прямий шлях до Template.mxlx відносно проєкту"},
                  "section":{"type":"string","enum":["areas","cells","all"],"default":"all",
                             "description":"areas — лише області з параметрами; cells — лише комірки; all — усе"}
                },"required":[]}""").getAsJsonObject(); //$NON-NLS-1$
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
        String projectName = arguments.has("project") ? arguments.get("project").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$
        String section = arguments.has("section") ? arguments.get("section").getAsString() : "all"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        IV8Project v8Project = V8Access.resolveProject(projectName);
        IProject project = v8Project.getProject();

        IFile file;
        if (arguments.has("path") && !arguments.get("path").getAsString().isBlank()) { //$NON-NLS-1$ //$NON-NLS-2$
            file = project.getFile(arguments.get("path").getAsString()); //$NON-NLS-1$
        } else {
            if (!arguments.has("kind") || !arguments.has("name")) { //$NON-NLS-1$ //$NON-NLS-2$
                throw new IllegalArgumentException("Вкажіть kind+name (+template) або path до Template.mxlx."); //$NON-NLS-1$
            }
            String kind = arguments.get("kind").getAsString(); //$NON-NLS-1$
            String name = arguments.get("name").getAsString(); //$NON-NLS-1$
            String template = arguments.has("template") && !arguments.get("template").getAsString().isBlank() //$NON-NLS-1$ //$NON-NLS-2$
                    ? arguments.get("template").getAsString() : null; //$NON-NLS-1$

            EObject configuration = V8Access.configuration(v8Project);
            String objectFolder = MetadataIndex.objectFolder(configuration, kind, name);
            boolean commonTemplate = objectFolder.contains("/CommonTemplates/"); //$NON-NLS-1$
            if (!commonTemplate && template == null) {
                throw new IllegalArgumentException(
                        "Вкажіть template — ім'я макета. Список макетів об'єкта поверне get_template."); //$NON-NLS-1$
            }
            String folder = commonTemplate ? objectFolder : objectFolder + "/Templates/" + template; //$NON-NLS-1$
            file = project.getFile(folder + "/Template.mxlx"); //$NON-NLS-1$
            if (!file.exists()) {
                IFile legacy = project.getFile(folder + "/Template.mxl"); //$NON-NLS-1$
                if (legacy.exists()) {
                    throw new IllegalArgumentException("Макет збережено в легасі-бінарному форматі .mxl (MOXCEL) — " //$NON-NLS-1$
                            + "не підтримується. Пересохраніть макет у EDT (стане Template.mxlx)."); //$NON-NLS-1$
                }
            }
        }
        if (!file.exists()) {
            throw new IllegalArgumentException("Файл макета не знайдено: " + file.getProjectRelativePath() //$NON-NLS-1$
                    + ". Це не табличний документ? Список макетів і типи — get_template."); //$NON-NLS-1$
        }

        JsonObject result;
        try (InputStream in = file.getContents(true)) {
            result = parseMxlx(in, section);
        }
        result.addProperty("file", file.getProjectRelativePath().toString()); //$NON-NLS-1$
        return result;
    }

    // ------------------------------------------------------------------
    // Парсинг Template.mxlx (XML, схема http://v8.1c.ru/8.2/data/spreadsheet)
    // ------------------------------------------------------------------

    /** Іменована область (NamedItemCells) або іменований малюнок (NamedItemDrawing). */
    private static final class Area {
        String name;
        String type; // Rows | Columns | Rectangle | Table | Drawing
        int beginRow = -1;
        int endRow = -1;
        int beginColumn = -1;
        int endColumn = -1;
        String columnsId;
        String drawingId;
        final Set<String> params = new LinkedHashSet<>();
    }

    /** Непорожня комірка: текст, параметр або шаблон. */
    private static final class Cell {
        int row;
        int col;
        String text;
        String parameter;
        String detailParameter;
        List<String> templateParams;
    }

    /**
     * Розбирає потік Template.mxlx у підсумковий JSON. Статичний і незалежний від
     * Eclipse/EDT — використовується й офлайн-харнесом.
     */
    static JsonObject parseMxlx(InputStream in, String section) throws XMLStreamException {
        boolean wantCells = !"areas".equals(section); //$NON-NLS-1$
        boolean wantAreas = !"cells".equals(section); //$NON-NLS-1$

        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);
        XMLStreamReader reader = factory.createXMLStreamReader(in);

        int docHeight = -1;
        int defaultColumns = 0;
        int mergeCount = 0;
        int drawingCount = 0;
        List<JsonObject> columnSets = new ArrayList<>();
        List<Area> areas = new ArrayList<>();
        List<Cell> cells = new ArrayList<>();
        int totalCells = 0;
        int lastRowIndex = -1;

        try {
            // корінь <document>
            while (reader.hasNext() && reader.next() != XMLStreamConstants.START_ELEMENT) {
                // до кореня
            }
            int rootDepth = 1;
            while (reader.hasNext() && rootDepth > 0) {
                int event = reader.next();
                if (event == XMLStreamConstants.END_ELEMENT) {
                    rootDepth--;
                    continue;
                }
                if (event != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }
                String tag = reader.getLocalName();
                switch (tag) {
                case "height": //$NON-NLS-1$
                    docHeight = parseInt(readElementText(reader), -1);
                    break;
                case "columns": { //$NON-NLS-1$
                    JsonObject set = parseColumns(reader);
                    if (set.has("id")) { //$NON-NLS-1$
                        columnSets.add(set);
                    } else {
                        defaultColumns = set.get("size").getAsInt(); //$NON-NLS-1$
                    }
                    break;
                }
                case "rowsItem": { //$NON-NLS-1$
                    int[] total = { totalCells };
                    lastRowIndex = parseRowsItem(reader, lastRowIndex, cells, total);
                    totalCells = total[0];
                    break;
                }
                case "namedItem": //$NON-NLS-1$
                    areas.add(parseNamedItem(reader));
                    break;
                case "merge": //$NON-NLS-1$
                    mergeCount++;
                    skipElement(reader);
                    break;
                case "drawing": //$NON-NLS-1$
                    drawingCount++;
                    skipElement(reader);
                    break;
                default:
                    skipElement(reader);
                    break;
                }
            }
        } finally {
            reader.close();
        }

        // параметри комірок → області; ім'я області → комірки
        for (Cell cell : cells) {
            Area owner = findArea(areas, cell.row, cell.col);
            if (owner != null) {
                collectCellParams(owner, cell);
            }
        }

        JsonObject result = new JsonObject();
        if (docHeight >= 0) {
            result.addProperty("rows", docHeight); //$NON-NLS-1$
        }
        result.addProperty("columns", defaultColumns); //$NON-NLS-1$
        if (!columnSets.isEmpty()) {
            JsonArray sets = new JsonArray();
            columnSets.forEach(sets::add);
            result.add("columnSets", sets); //$NON-NLS-1$
        }

        if (wantAreas) {
            JsonArray areasJson = new JsonArray();
            for (Area area : areas) {
                areasJson.add(areaToJson(area));
            }
            result.add("areas", areasJson); //$NON-NLS-1$
        }

        if (wantCells) {
            JsonArray cellsJson = new JsonArray();
            int emitted = 0;
            for (Cell cell : cells) {
                if (emitted >= MAX_CELLS) {
                    break;
                }
                JsonObject c = new JsonObject();
                c.addProperty("row", cell.row); //$NON-NLS-1$
                c.addProperty("col", cell.col); //$NON-NLS-1$
                Area owner = findArea(areas, cell.row, cell.col);
                if (owner != null) {
                    c.addProperty("area", owner.name); //$NON-NLS-1$
                }
                if (cell.parameter != null) {
                    c.addProperty("parameter", cell.parameter); //$NON-NLS-1$
                }
                if (cell.detailParameter != null) {
                    c.addProperty("detailParameter", cell.detailParameter); //$NON-NLS-1$
                }
                if (cell.text != null) {
                    c.addProperty("text", cell.text); //$NON-NLS-1$
                }
                if (cell.templateParams != null) {
                    JsonArray tp = new JsonArray();
                    cell.templateParams.forEach(tp::add);
                    c.add("templateParams", tp); //$NON-NLS-1$
                }
                cellsJson.add(c);
                emitted++;
            }
            result.addProperty("cellCount", totalCells); //$NON-NLS-1$
            if (totalCells > emitted) {
                result.addProperty("truncated", true); //$NON-NLS-1$
            }
            result.add("cells", cellsJson); //$NON-NLS-1$
        }

        result.addProperty("mergeCount", mergeCount); //$NON-NLS-1$
        result.addProperty("drawingCount", drawingCount); //$NON-NLS-1$
        return result;
    }

    /** {@code <columns>}: size і необов'язковий id (додатковий набір колонок). */
    private static JsonObject parseColumns(XMLStreamReader reader) throws XMLStreamException {
        JsonObject set = new JsonObject();
        int size = 0;
        int depth = 1;
        while (reader.hasNext() && depth > 0) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String tag = reader.getLocalName();
                if (depth == 1 && "size".equals(tag)) { //$NON-NLS-1$
                    size = parseInt(readElementText(reader), 0);
                } else if (depth == 1 && "id".equals(tag)) { //$NON-NLS-1$
                    set.addProperty("id", readElementText(reader)); //$NON-NLS-1$
                } else {
                    depth++;
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
        set.addProperty("size", size); //$NON-NLS-1$
        return set;
    }

    /**
     * {@code <rowsItem><index>N</index><row>…</row></rowsItem>}: комірки рядка.
     * Повертає індекс поточного рядка (для рядків без явного index — попередній + 1).
     */
    private static int parseRowsItem(XMLStreamReader reader, int lastRowIndex, List<Cell> cells, int[] totalCells)
            throws XMLStreamException {
        int rowIndex = lastRowIndex + 1;
        int depth = 1;
        while (reader.hasNext() && depth > 0) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String tag = reader.getLocalName();
                if (depth == 1 && "index".equals(tag)) { //$NON-NLS-1$
                    rowIndex = parseInt(readElementText(reader), rowIndex);
                } else if (depth == 1 && "row".equals(tag)) { //$NON-NLS-1$
                    parseRow(reader, rowIndex, cells, totalCells);
                } else {
                    depth++;
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
        return rowIndex;
    }

    /** {@code <row>}: послідовність {@code <c>} (cellsItem) з опційним {@code <i>}. */
    private static void parseRow(XMLStreamReader reader, int rowIndex, List<Cell> cells, int[] totalCells)
            throws XMLStreamException {
        int colIndex = -1;
        int depth = 1;
        while (reader.hasNext() && depth > 0) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String tag = reader.getLocalName();
                if (depth == 1 && "c".equals(tag)) { //$NON-NLS-1$
                    colIndex = parseCellsItem(reader, rowIndex, colIndex, cells, totalCells);
                } else {
                    depth++;
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
    }

    /**
     * Зовнішній {@code <c>} (cellsItem): опційний {@code <i>} — явний індекс колонки,
     * внутрішній {@code <c>} — сама комірка. Повертає індекс колонки цієї комірки.
     */
    private static int parseCellsItem(XMLStreamReader reader, int rowIndex, int prevCol, List<Cell> cells,
            int[] totalCells) throws XMLStreamException {
        int colIndex = prevCol + 1;
        int depth = 1;
        while (reader.hasNext() && depth > 0) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String tag = reader.getLocalName();
                if (depth == 1 && "i".equals(tag)) { //$NON-NLS-1$
                    colIndex = parseInt(readElementText(reader), colIndex);
                } else if (depth == 1 && "c".equals(tag)) { //$NON-NLS-1$
                    Cell cell = parseCell(reader, rowIndex, colIndex);
                    if (cell != null) {
                        totalCells[0]++;
                        cells.add(cell);
                    }
                } else {
                    depth++;
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
        return colIndex;
    }

    /** Внутрішній {@code <c>} — комірка: f, parameter, detailParameter, tl. */
    private static Cell parseCell(XMLStreamReader reader, int rowIndex, int colIndex) throws XMLStreamException {
        Cell cell = new Cell();
        cell.row = rowIndex;
        cell.col = colIndex;
        boolean meaningful = false;
        int depth = 1;
        while (reader.hasNext() && depth > 0) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String tag = reader.getLocalName();
                if (depth == 1 && "parameter".equals(tag)) { //$NON-NLS-1$
                    cell.parameter = readElementText(reader);
                    meaningful = true;
                } else if (depth == 1 && "detailParameter".equals(tag)) { //$NON-NLS-1$
                    cell.detailParameter = readElementText(reader);
                    meaningful = true;
                } else if (depth == 1 && "tl".equals(tag)) { //$NON-NLS-1$
                    String text = parseLocalString(reader);
                    if (text != null && !text.isBlank()) {
                        cell.text = text.length() > MAX_CELL_TEXT
                                ? text.substring(0, MAX_CELL_TEXT) + "…" //$NON-NLS-1$
                                : text;
                        cell.templateParams = extractTemplateParams(text);
                        meaningful = true;
                    }
                } else {
                    depth++;
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
        return meaningful ? cell : null;
    }

    /** {@code <tl><v8:item><v8:lang>…<v8:content>текст</v8:content></v8:item></tl>} — перший вміст. */
    private static String parseLocalString(XMLStreamReader reader) throws XMLStreamException {
        String text = null;
        int depth = 1;
        while (reader.hasNext() && depth > 0) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                if (text == null && "content".equals(reader.getLocalName())) { //$NON-NLS-1$
                    text = readElementText(reader);
                } else {
                    depth++;
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
        return text;
    }

    /** {@code <namedItem xsi:type="NamedItemCells|NamedItemDrawing">}. */
    private static Area parseNamedItem(XMLStreamReader reader) throws XMLStreamException {
        Area area = new Area();
        String xsiType = reader.getAttributeValue("http://www.w3.org/2001/XMLSchema-instance", "type"); //$NON-NLS-1$ //$NON-NLS-2$
        boolean drawing = xsiType != null && xsiType.contains("NamedItemDrawing"); //$NON-NLS-1$
        if (drawing) {
            area.type = "Drawing"; //$NON-NLS-1$
        }
        int depth = 1;
        while (reader.hasNext() && depth > 0) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String tag = reader.getLocalName();
                if (depth == 1 && "name".equals(tag)) { //$NON-NLS-1$
                    area.name = readElementText(reader);
                } else if (depth == 1 && "drawingID".equals(tag)) { //$NON-NLS-1$
                    area.drawingId = readElementText(reader);
                } else if (depth == 1 && "area".equals(tag)) { //$NON-NLS-1$
                    parseAreaBounds(reader, area);
                } else {
                    depth++;
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
        return area;
    }

    /** {@code <area><type>Rows|Columns|Rectangle…<beginRow>…}. */
    private static void parseAreaBounds(XMLStreamReader reader, Area area) throws XMLStreamException {
        int depth = 1;
        while (reader.hasNext() && depth > 0) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String tag = reader.getLocalName();
                if (depth == 1) {
                    switch (tag) {
                    case "type": area.type = readElementText(reader); break; //$NON-NLS-1$
                    case "beginRow": area.beginRow = parseInt(readElementText(reader), -1); break; //$NON-NLS-1$
                    case "endRow": area.endRow = parseInt(readElementText(reader), -1); break; //$NON-NLS-1$
                    case "beginColumn": area.beginColumn = parseInt(readElementText(reader), -1); break; //$NON-NLS-1$
                    case "endColumn": area.endColumn = parseInt(readElementText(reader), -1); break; //$NON-NLS-1$
                    case "columnsID": area.columnsId = readElementText(reader); break; //$NON-NLS-1$
                    default: depth++; break;
                    }
                } else {
                    depth++;
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
    }

    // ------------------------------------------------------------------
    // Допоміжні
    // ------------------------------------------------------------------

    /** Перша область, що містить комірку: спершу Rows/Rectangle/Table, потім Columns. */
    private static Area findArea(List<Area> areas, int row, int col) {
        for (Area area : areas) {
            if (area.type == null || "Columns".equals(area.type) || "Drawing".equals(area.type)) { //$NON-NLS-1$ //$NON-NLS-2$
                continue;
            }
            boolean rowIn = inRange(row, area.beginRow, area.endRow);
            boolean colIn = "Rows".equals(area.type) || inRange(col, area.beginColumn, area.endColumn); //$NON-NLS-1$
            if (rowIn && colIn) {
                return area;
            }
        }
        for (Area area : areas) {
            if ("Columns".equals(area.type) && inRange(col, area.beginColumn, area.endColumn)) { //$NON-NLS-1$
                return area;
            }
        }
        return null;
    }

    private static boolean inRange(int value, int begin, int end) {
        return (begin < 0 || value >= begin) && (end < 0 || value <= end);
    }

    private static void collectCellParams(Area area, Cell cell) {
        if (area.params.size() >= MAX_AREA_PARAMS) {
            return;
        }
        if (cell.parameter != null) {
            area.params.add(cell.parameter);
        }
        if (cell.templateParams != null) {
            for (String p : cell.templateParams) {
                if (area.params.size() >= MAX_AREA_PARAMS) {
                    break;
                }
                area.params.add(p + " [tpl]"); //$NON-NLS-1$
            }
        }
    }

    /** Імена [плейсхолдерів] шаблонного тексту; числові ([5]) пропускаються. */
    private static List<String> extractTemplateParams(String text) {
        if (text.indexOf('[') < 0) {
            return null;
        }
        List<String> params = null;
        Matcher matcher = TEMPLATE_PARAM.matcher(text);
        while (matcher.find()) {
            String value = matcher.group(1);
            if (params == null) {
                params = new ArrayList<>();
            }
            params.add(value);
        }
        return params;
    }

    private static JsonObject areaToJson(Area area) {
        JsonObject json = new JsonObject();
        json.addProperty("name", area.name); //$NON-NLS-1$
        json.addProperty("type", area.type == null ? "Rectangle" : area.type); //$NON-NLS-1$ //$NON-NLS-2$
        if ("Drawing".equals(area.type)) { //$NON-NLS-1$
            if (area.drawingId != null) {
                json.addProperty("drawingID", area.drawingId); //$NON-NLS-1$
            }
            return json;
        }
        if (area.beginRow >= 0 || area.endRow >= 0) {
            json.addProperty("beginRow", area.beginRow); //$NON-NLS-1$
            json.addProperty("endRow", area.endRow); //$NON-NLS-1$
        }
        if (area.beginColumn >= 0 || area.endColumn >= 0) {
            json.addProperty("beginColumn", area.beginColumn); //$NON-NLS-1$
            json.addProperty("endColumn", area.endColumn); //$NON-NLS-1$
        }
        if (area.columnsId != null) {
            json.addProperty("columnsID", area.columnsId); //$NON-NLS-1$
        }
        if (!area.params.isEmpty()) {
            JsonArray params = new JsonArray();
            area.params.forEach(params::add);
            json.add("params", params); //$NON-NLS-1$
        }
        return json;
    }

    private static String readElementText(XMLStreamReader reader) throws XMLStreamException {
        return reader.getElementText();
    }

    /** Пропускає поточний елемент з усім вмістом (до відповідного END_ELEMENT). */
    private static void skipElement(XMLStreamReader reader) throws XMLStreamException {
        int depth = 1;
        while (reader.hasNext() && depth > 0) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
