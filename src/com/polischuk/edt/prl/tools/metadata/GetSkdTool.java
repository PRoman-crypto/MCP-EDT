/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.metadata;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.emf.ecore.EObject;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.polischuk.edt.prl.edt.KindRegistry;
import com.polischuk.edt.prl.edt.MetadataIndex;
import com.polischuk.edt.prl.edt.V8Access;
import com.polischuk.edt.prl.edt.WorkspaceFiles;
import com.polischuk.edt.prl.tools.McpTool;

import com._1c.g5.v8.dt.core.platform.IV8Project;

/**
 * Структура схеми компоновки даних (СКД, Template.dcs): набори даних із текстами
 * запитів, поля, обчислювані поля, ресурси (totalField), параметри, зв'язки наборів
 * і варіанти налаштувань. Працює файлово — парсить XML макета у вихідниках EDT,
 * як GetFormImageTool для форм.
 */
public final class GetSkdTool implements McpTool {

    /** Загальний бюджет вузлів відповіді — захист від гігантських схем. */
    private static final int MAX_NODES = 1200;

    /** Ліміт довжини одного тексту запиту в символах. */
    private static final int MAX_QUERY_CHARS = 30_000;

    @Override
    public String name() {
        return "get_skd"; //$NON-NLS-1$
    }

    @Override
    public String description() {
        return "Структура СКД (схеми компоновки даних) звіту чи іншого об'єкта: набори даних з текстами запитів, " //$NON-NLS-1$
                + "поля, параметри, ресурси, варіанти налаштувань. Вкажіть kind+name (+template, якщо СКД-макетів " //$NON-NLS-1$
                + "кілька) або одразу path до Template.dcs. section звужує відповідь; тексти запитів включені завжди."; //$NON-NLS-1$
    }

    @Override
    public JsonObject inputSchema() {
        return JsonParser.parseString("""
                {"type":"object","properties":{
                  "project":{"type":"string","description":"Ім'я проєкту EDT (необов'язково, якщо проєкт один)"},
                  "kind":{"type":"string","description":"Вид власника: Report (типово), DataProcessor, CommonTemplate, Catalog…"},
                  "name":{"type":"string","description":"Ім'я об'єкта-власника (для CommonTemplate — ім'я макета)"},
                  "template":{"type":"string","description":"Ім'я макета-СКД; без нього — єдиний/основний макет або перелік доступних"},
                  "path":{"type":"string","description":"Або прямий шлях до Template.dcs відносно проєкту"},
                  "section":{"type":"string","enum":["dataSets","fields","parameters","variants","all"],"default":"all",
                             "description":"Розділ відповіді; набори даних із запитами повертаються завжди"}
                }}""").getAsJsonObject(); //$NON-NLS-1$
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
        String projectName = arguments.has("project") ? arguments.get("project").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$
        IV8Project v8Project = V8Access.resolveProject(projectName);
        IProject project = v8Project.getProject();
        String section = arguments.has("section") ? arguments.get("section").getAsString() : "all"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (!List.of("dataSets", "fields", "parameters", "variants", "all").contains(section)) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            throw new IllegalArgumentException("Невідомий section: " + section //$NON-NLS-1$
                    + ". Допустимі: dataSets, fields, parameters, variants, all."); //$NON-NLS-1$
        }

        String path;
        JsonArray availableTemplates = null;
        if (arguments.has("path")) { //$NON-NLS-1$
            path = arguments.get("path").getAsString(); //$NON-NLS-1$
        } else {
            if (!arguments.has("name")) { //$NON-NLS-1$
                throw new IllegalArgumentException("Вкажіть path або kind+name (+template)"); //$NON-NLS-1$
            }
            String kind = arguments.has("kind") ? arguments.get("kind").getAsString() : "Report"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            EObject configuration = V8Access.configuration(v8Project);
            String folder = MetadataIndex.objectFolder(configuration, kind, arguments.get("name").getAsString()); //$NON-NLS-1$
            if ("CommonTemplate".equals(KindRegistry.canonical(kind))) { //$NON-NLS-1$
                // Загальний макет — сам є макетом: Template.dcs лежить одразу в його каталозі
                path = folder + "/Template.dcs"; //$NON-NLS-1$
            } else if (arguments.has("template")) { //$NON-NLS-1$
                path = folder + "/Templates/" + arguments.get("template").getAsString() + "/Template.dcs"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            } else {
                List<String> dcsTemplates = listDcsTemplates(project, folder);
                if (dcsTemplates.isEmpty()) {
                    throw new IllegalArgumentException("У " + folder + " немає макетів-СКД (Template.dcs). " //$NON-NLS-1$ //$NON-NLS-2$
                            + "Можливо, звіт використовує загальний макет (kind=CommonTemplate) або макет іншого типу."); //$NON-NLS-1$
                }
                String chosen = chooseMainTemplate(dcsTemplates);
                if (chosen == null) {
                    // Кілька рівнозначних СКД — повертаємо перелік, щоб користувач вибрав
                    JsonObject listing = new JsonObject();
                    listing.addProperty("folder", folder); //$NON-NLS-1$
                    JsonArray names = new JsonArray();
                    dcsTemplates.forEach(names::add);
                    listing.add("templates", names); //$NON-NLS-1$
                    listing.addProperty("hint", "Кілька СКД-макетів — повторіть виклик із параметром template"); //$NON-NLS-1$ //$NON-NLS-2$
                    return listing;
                }
                if (dcsTemplates.size() > 1) {
                    availableTemplates = new JsonArray();
                    dcsTemplates.forEach(availableTemplates::add);
                }
                path = folder + "/Templates/" + chosen + "/Template.dcs"; //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        String xml = WorkspaceFiles.read(project, path);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false); //$NON-NLS-1$
        DocumentBuilder builder = factory.newDocumentBuilder();
        Element root = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                .getDocumentElement();

        boolean withFields = "fields".equals(section) || "all".equals(section); //$NON-NLS-1$ //$NON-NLS-2$

        int[] budget = {MAX_NODES};
        JsonObject result = new JsonObject();
        result.addProperty("path", path); //$NON-NLS-1$
        if (availableTemplates != null) {
            result.add("availableTemplates", availableTemplates); //$NON-NLS-1$
        }

        // Набори даних із текстами запитів — завжди, це головний зміст СКД
        result.add("dataSets", dataSets(root, "dataSet", withFields, budget)); //$NON-NLS-1$ //$NON-NLS-2$

        if ("dataSets".equals(section) || "all".equals(section)) { //$NON-NLS-1$ //$NON-NLS-2$
            JsonArray links = dataSetLinks(root, budget);
            if (links.size() > 0) {
                result.add("dataSetLinks", links); //$NON-NLS-1$
            }
        }
        if (withFields) {
            JsonArray calculated = calculatedFields(root, budget);
            if (calculated.size() > 0) {
                result.add("calculatedFields", calculated); //$NON-NLS-1$
            }
            JsonArray totals = totalFields(root, budget);
            if (totals.size() > 0) {
                result.add("resources", totals); //$NON-NLS-1$
            }
        }
        if ("parameters".equals(section) || "all".equals(section)) { //$NON-NLS-1$ //$NON-NLS-2$
            JsonArray parameters = parameters(root, budget);
            if (parameters.size() > 0) {
                result.add("parameters", parameters); //$NON-NLS-1$
            }
        }
        if ("variants".equals(section) || "all".equals(section)) { //$NON-NLS-1$ //$NON-NLS-2$
            JsonArray variants = settingsVariants(root, "variants".equals(section), budget); //$NON-NLS-1$
            if (variants.size() > 0) {
                result.add("settingsVariants", variants); //$NON-NLS-1$
            }
        }
        if (budget[0] <= 0) {
            result.addProperty("truncated", true); //$NON-NLS-1$
        }
        return result;
    }

    // ------------------------------------------------------------------ вибір макета

    /** Підкаталоги Templates, що містять Template.dcs, — тобто макети типу СКД. */
    private static List<String> listDcsTemplates(IProject project, String objectFolder) {
        List<String> result = new ArrayList<>();
        IFolder templates = project.getFolder(objectFolder.replace('\\', '/') + "/Templates"); //$NON-NLS-1$
        if (!templates.exists()) {
            return result;
        }
        try {
            for (IResource member : templates.members()) {
                if (member instanceof IFolder child && child.getFile("Template.dcs").exists()) { //$NON-NLS-1$
                    result.add(child.getName());
                }
            }
        } catch (CoreException e) {
            throw new IllegalStateException("Помилка обходу " + templates.getFullPath() + ": " + e.getMessage(), e); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return result;
    }

    /** Єдиний макет або «основний» серед кількох; null — треба вибрати явно. */
    private static String chooseMainTemplate(List<String> dcsTemplates) {
        if (dcsTemplates.size() == 1) {
            return dcsTemplates.get(0);
        }
        for (String name : dcsTemplates) {
            if ("ОсновнаяСхемаКомпоновкиДанных".equalsIgnoreCase(name) //$NON-NLS-1$
                    || "ОсновнаСхемаКомпонуванняДаних".equalsIgnoreCase(name)) { //$NON-NLS-1$
                return name;
            }
        }
        for (String name : dcsTemplates) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.startsWith("основнаясхема") || lower.startsWith("основнасхема")) { //$NON-NLS-1$ //$NON-NLS-2$
                return name;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ набори даних

    /** Набори даних (dataSet або вкладені item у DataSetUnion) з полями і запитом. */
    private static JsonArray dataSets(Element parent, String tagName, boolean withFields, int[] budget) {
        JsonArray result = new JsonArray();
        for (Element element : childElements(parent, tagName)) {
            if (budget[0]-- <= 0) {
                break;
            }
            JsonObject entry = new JsonObject();
            String type = element.getAttribute("xsi:type"); //$NON-NLS-1$
            if (!type.isEmpty()) {
                entry.addProperty("type", type); //$NON-NLS-1$
            }
            addIfPresent(entry, "name", directChildText(element, "name")); //$NON-NLS-1$ //$NON-NLS-2$
            addIfPresent(entry, "objectName", directChildText(element, "objectName")); //$NON-NLS-1$ //$NON-NLS-2$
            String query = directChildRawText(element, "query"); //$NON-NLS-1$
            if (query != null) {
                if (query.length() > MAX_QUERY_CHARS) {
                    query = query.substring(0, MAX_QUERY_CHARS);
                    entry.addProperty("queryTruncated", true); //$NON-NLS-1$
                }
                entry.addProperty("query", query); //$NON-NLS-1$
            }
            if (withFields) {
                JsonArray fields = fields(element, budget);
                if (fields.size() > 0) {
                    entry.add("fields", fields); //$NON-NLS-1$
                }
            }
            // DataSetUnion: вкладені набори серіалізуються як <item xsi:type="DataSet...">
            JsonArray nested = dataSets(element, "item", withFields, budget); //$NON-NLS-1$
            if (nested.size() > 0) {
                entry.add("items", nested); //$NON-NLS-1$
            }
            result.add(entry);
        }
        return result;
    }

    /** Поля набору: dataPath, вихідне поле, заголовок, тип; папки полів — окремим типом. */
    private static JsonArray fields(Element dataSet, int[] budget) {
        JsonArray result = new JsonArray();
        for (Element field : childElements(dataSet, "field")) { //$NON-NLS-1$
            if (budget[0]-- <= 0) {
                break;
            }
            JsonObject entry = new JsonObject();
            String type = field.getAttribute("xsi:type"); //$NON-NLS-1$
            if ("DataSetFieldFolder".equals(type)) { //$NON-NLS-1$
                entry.addProperty("folder", true); //$NON-NLS-1$
            }
            addIfPresent(entry, "dataPath", directChildText(field, "dataPath")); //$NON-NLS-1$ //$NON-NLS-2$
            String sourceField = directChildText(field, "field"); //$NON-NLS-1$
            String dataPath = directChildText(field, "dataPath"); //$NON-NLS-1$
            if (sourceField != null && !sourceField.equals(dataPath)) {
                entry.addProperty("field", sourceField); //$NON-NLS-1$
            }
            addIfPresent(entry, "title", localizedText(field, "title")); //$NON-NLS-1$ //$NON-NLS-2$
            String valueType = valueTypes(field);
            addIfPresent(entry, "type", valueType); //$NON-NLS-1$
            result.add(entry);
        }
        return result;
    }

    private static JsonArray dataSetLinks(Element root, int[] budget) {
        JsonArray result = new JsonArray();
        for (Element link : childElements(root, "dataSetLink")) { //$NON-NLS-1$
            if (budget[0]-- <= 0) {
                break;
            }
            JsonObject entry = new JsonObject();
            addIfPresent(entry, "source", directChildText(link, "sourceDataSet")); //$NON-NLS-1$ //$NON-NLS-2$
            addIfPresent(entry, "destination", directChildText(link, "destinationDataSet")); //$NON-NLS-1$ //$NON-NLS-2$
            addIfPresent(entry, "sourceExpression", directChildText(link, "sourceExpression")); //$NON-NLS-1$ //$NON-NLS-2$
            addIfPresent(entry, "destinationExpression", directChildText(link, "destinationExpression")); //$NON-NLS-1$ //$NON-NLS-2$
            addIfPresent(entry, "parameter", directChildText(link, "parameter")); //$NON-NLS-1$ //$NON-NLS-2$
            result.add(entry);
        }
        return result;
    }

    // ------------------------------------------------------------------ поля схеми

    private static JsonArray calculatedFields(Element root, int[] budget) {
        JsonArray result = new JsonArray();
        for (Element field : childElements(root, "calculatedField")) { //$NON-NLS-1$
            if (budget[0]-- <= 0) {
                break;
            }
            JsonObject entry = new JsonObject();
            addIfPresent(entry, "dataPath", directChildText(field, "dataPath")); //$NON-NLS-1$ //$NON-NLS-2$
            addIfPresent(entry, "expression", directChildRawText(field, "expression")); //$NON-NLS-1$ //$NON-NLS-2$
            addIfPresent(entry, "title", localizedText(field, "title")); //$NON-NLS-1$ //$NON-NLS-2$
            addIfPresent(entry, "type", valueTypes(field)); //$NON-NLS-1$
            result.add(entry);
        }
        return result;
    }

    /** Ресурси схеми (totalField): поле і вираз агрегації. */
    private static JsonArray totalFields(Element root, int[] budget) {
        JsonArray result = new JsonArray();
        for (Element field : childElements(root, "totalField")) { //$NON-NLS-1$
            if (budget[0]-- <= 0) {
                break;
            }
            JsonObject entry = new JsonObject();
            addIfPresent(entry, "dataPath", directChildText(field, "dataPath")); //$NON-NLS-1$ //$NON-NLS-2$
            addIfPresent(entry, "expression", directChildRawText(field, "expression")); //$NON-NLS-1$ //$NON-NLS-2$
            List<String> groups = new ArrayList<>();
            for (Element group : childElements(field, "group")) { //$NON-NLS-1$
                String text = group.getTextContent();
                if (text != null && !text.isBlank()) {
                    groups.add(text.strip());
                }
            }
            if (!groups.isEmpty()) {
                JsonArray array = new JsonArray();
                groups.forEach(array::add);
                entry.add("groups", array); //$NON-NLS-1$
            }
            result.add(entry);
        }
        return result;
    }

    // ------------------------------------------------------------------ параметри

    private static JsonArray parameters(Element root, int[] budget) {
        JsonArray result = new JsonArray();
        for (Element parameter : childElements(root, "parameter")) { //$NON-NLS-1$
            if (budget[0]-- <= 0) {
                break;
            }
            JsonObject entry = new JsonObject();
            addIfPresent(entry, "name", directChildText(parameter, "name")); //$NON-NLS-1$ //$NON-NLS-2$
            addIfPresent(entry, "title", localizedText(parameter, "title")); //$NON-NLS-1$ //$NON-NLS-2$
            addIfPresent(entry, "type", valueTypes(parameter)); //$NON-NLS-1$
            addIfPresent(entry, "expression", directChildRawText(parameter, "expression")); //$NON-NLS-1$ //$NON-NLS-2$
            String use = directChildText(parameter, "useRestriction"); //$NON-NLS-1$
            if ("true".equals(use)) { //$NON-NLS-1$
                // Недоступний користувачу напряму (обмежене використання)
                entry.addProperty("restricted", true); //$NON-NLS-1$
            }
            result.add(entry);
        }
        return result;
    }

    // ------------------------------------------------------------------ варіанти налаштувань

    /**
     * Варіанти налаштувань: ім'я і подання завжди; у режимі variants — ще вибрані поля
     * та поля групування зі структури варіанта.
     */
    private static JsonArray settingsVariants(Element root, boolean detailed, int[] budget) {
        JsonArray result = new JsonArray();
        for (Element variant : childElements(root, "settingsVariant")) { //$NON-NLS-1$
            if (budget[0]-- <= 0) {
                break;
            }
            JsonObject entry = new JsonObject();
            addIfPresent(entry, "name", directChildText(variant, "dcsset:name")); //$NON-NLS-1$ //$NON-NLS-2$
            addIfPresent(entry, "presentation", localizedText(variant, "dcsset:presentation")); //$NON-NLS-1$ //$NON-NLS-2$
            if (detailed) {
                Element settings = firstChild(variant, "dcsset:settings"); //$NON-NLS-1$
                if (settings != null) {
                    JsonArray selection = new JsonArray();
                    collectSettingsFields(settings, "dcsset:selection", "dcsset:field", selection); //$NON-NLS-1$ //$NON-NLS-2$
                    if (selection.size() > 0) {
                        entry.add("selection", selection); //$NON-NLS-1$
                    }
                    JsonArray groupings = new JsonArray();
                    collectSettingsFields(settings, "dcsset:groupItems", "dcsset:field", groupings); //$NON-NLS-1$ //$NON-NLS-2$
                    if (groupings.size() > 0) {
                        entry.add("groupings", groupings); //$NON-NLS-1$
                    }
                }
            }
            result.add(entry);
        }
        return result;
    }

    /** Рекурсивно збирає тексти полів (fieldTag) з усіх секцій sectionTag усередині settings. */
    private static void collectSettingsFields(Element element, String sectionTag, String fieldTag, JsonArray out) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element child)) {
                continue;
            }
            if (sectionTag.equals(child.getTagName())) {
                for (Element item : childElements(child, "dcsset:item")) { //$NON-NLS-1$
                    String field = directChildText(item, fieldTag);
                    if (field != null && out.size() < 100) {
                        out.add(field);
                    }
                }
            } else {
                collectSettingsFields(child, sectionTag, fieldTag, out);
            }
        }
    }

    // ------------------------------------------------------------------ XML-помічники

    /** Прямі дочірні елементи з даною назвою тега. */
    private static List<Element> childElements(Element parent, String tagName) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element && tagName.equals(element.getTagName())) {
                result.add(element);
            }
        }
        return result;
    }

    private static Element firstChild(Element parent, String tagName) {
        List<Element> children = childElements(parent, tagName);
        return children.isEmpty() ? null : children.get(0);
    }

    /** Текст прямого дочірнього елемента, згорнутий в один рядок. */
    private static String directChildText(Element parent, String tagName) {
        String text = directChildRawText(parent, tagName);
        return text == null ? null : text.replaceAll("\\s+", " ").strip(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Текст прямого дочірнього елемента без нормалізації пробілів (тексти запитів, вирази). */
    private static String directChildRawText(Element parent, String tagName) {
        for (Element element : childElements(parent, tagName)) {
            String text = element.getTextContent();
            if (text != null && !text.isBlank()) {
                return text.strip();
            }
        }
        return null;
    }

    /** Локалізований рядок: <tag><v8:item><v8:lang>ru</v8:lang><v8:content>Текст</v8:content>… */
    private static String localizedText(Element parent, String tagName) {
        Element localized = firstChild(parent, tagName);
        if (localized == null) {
            return null;
        }
        for (Element item : childElements(localized, "v8:item")) { //$NON-NLS-1$
            String content = directChildText(item, "v8:content"); //$NON-NLS-1$
            if (content != null) {
                return content;
            }
        }
        String text = localized.getTextContent();
        return text == null || text.isBlank() ? null : text.replaceAll("\\s+", " ").strip(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Типи значення: <valueType><v8:Type>d4p1:CatalogRef.Номенклатура → "CatalogRef.Номенклатура, …". */
    private static String valueTypes(Element parent) {
        Element valueType = firstChild(parent, "valueType"); //$NON-NLS-1$
        if (valueType == null) {
            return null;
        }
        List<String> types = new ArrayList<>();
        for (Element type : childElements(valueType, "v8:Type")) { //$NON-NLS-1$
            String text = type.getTextContent();
            if (text != null && !text.isBlank()) {
                String local = text.strip();
                int colon = local.indexOf(':');
                types.add(colon >= 0 ? local.substring(colon + 1) : local);
            }
        }
        return types.isEmpty() ? null : String.join(", ", types); //$NON-NLS-1$
    }

    private static void addIfPresent(JsonObject target, String key, String value) {
        if (value != null && !value.isEmpty()) {
            target.addProperty(key, value);
        }
    }
}
