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

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.polischuk.edt.prl.edt.MetadataIndex;
import com.polischuk.edt.prl.edt.V8Access;
import com.polischuk.edt.prl.edt.WorkspaceFiles;
import com.polischuk.edt.prl.tools.McpTool;

import com._1c.g5.v8.dt.core.platform.IV8Project;

/**
 * Структура керованої форми (дерево елементів із Form.form).
 * PNG-рендер форми — окрема задача (backlog); формат structure покриває аналіз перед правкою.
 */
public final class GetFormImageTool implements McpTool {

    private static final int MAX_NODES = 800;

    @Override
    public String name() {
        return "get_form_image"; //$NON-NLS-1$
    }

    @Override
    public String description() {
        return "Структура керованої форми: дерево елементів (тип, ім'я, dataPath, заголовок), реквізити, команди. " //$NON-NLS-1$
                + "Вкажіть kind+name+form або одразу path до Form.form. format=structure — дерево елементів, image — PNG-скетч компоновки. "
                + "Великі форми: subtree (обхід однієї групи), depth (глибина), maxElements; у відповіді totalElements/returnedElements."; //$NON-NLS-1$
    }

    @Override
    public JsonObject inputSchema() {
        return JsonParser.parseString("""
                {"type":"object","properties":{
                  "project":{"type":"string","description":"Ім'я проєкту EDT (необов'язково, якщо проєкт один)"},
                  "kind":{"type":"string","description":"Вид власника (Catalog, Document, CommonForm…)"},
                  "name":{"type":"string","description":"Ім'я об'єкта-власника (для CommonForm — ім'я форми)"},
                  "form":{"type":"string","description":"Ім'я форми об'єкта, напр. ФормаЭлемента"},
                  "path":{"type":"string","description":"Або прямий шлях до Form.form відносно проєкту"},
                  "format":{"type":"string","enum":["structure","image"],"default":"structure","description":"structure — дерево елементів; image — PNG-скетч компоновки форми"},
                  "subtree":{"type":"string","description":"Ім'я групи/таблиці — обхід лише всередині неї замість усієї форми"},
                  "depth":{"type":"integer","description":"Максимальна глибина вкладеності елементів (без нього — уся глибина)"},
                  "maxElements":{"type":"integer","default":800,"description":"Ліміт елементів у відповіді; totalElements показує, скільки їх насправді"}
                }}""").getAsJsonObject(); //$NON-NLS-1$
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
        String projectName = arguments.has("project") ? arguments.get("project").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$
        IV8Project v8Project = V8Access.resolveProject(projectName);
        IProject project = v8Project.getProject();

        String path;
        if (arguments.has("path")) { //$NON-NLS-1$
            path = arguments.get("path").getAsString(); //$NON-NLS-1$
        } else {
            if (!arguments.has("kind") || !arguments.has("name")) { //$NON-NLS-1$ //$NON-NLS-2$
                throw new IllegalArgumentException("Вкажіть path або kind+name (+form)"); //$NON-NLS-1$
            }
            EObject configuration = V8Access.configuration(v8Project);
            String folder = MetadataIndex.objectFolder(configuration,
                    arguments.get("kind").getAsString(), arguments.get("name").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
            path = arguments.has("form") //$NON-NLS-1$
                    ? folder + "/Forms/" + arguments.get("form").getAsString() + "/Form.form" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    : folder + "/Form.form"; //$NON-NLS-1$
        }

        String xml = WorkspaceFiles.read(project, path);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false); //$NON-NLS-1$
        DocumentBuilder builder = factory.newDocumentBuilder();
        Element root = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                .getDocumentElement();

        if (arguments.has("format") && "image".equals(arguments.get("format").getAsString())) { //$NON-NLS-1$ //$NON-NLS-2$
            return FormImageRenderer.render(root, path);
        }

        Element treeRoot = root;
        String subtree = arguments.has("subtree") && !arguments.get("subtree").getAsString().isBlank() //$NON-NLS-1$ //$NON-NLS-2$
                ? arguments.get("subtree").getAsString() : null; //$NON-NLS-1$
        if (subtree != null) {
            treeRoot = findByName(root, subtree);
            if (treeRoot == null) {
                throw new IllegalArgumentException("Елемент не знайдено у формі: " + subtree //$NON-NLS-1$
                        + ". Викличте без subtree, щоб побачити дерево."); //$NON-NLS-1$
            }
        }
        int maxElements = arguments.has("maxElements") //$NON-NLS-1$
                ? Math.max(1, arguments.get("maxElements").getAsInt()) : MAX_NODES; //$NON-NLS-1$
        int maxDepth = arguments.has("depth") //$NON-NLS-1$
                ? Math.max(1, arguments.get("depth").getAsInt()) : Integer.MAX_VALUE; //$NON-NLS-1$

        Walk walk = new Walk(maxElements, maxDepth);
        JsonObject result = new JsonObject();
        result.addProperty("path", path); //$NON-NLS-1$
        if (subtree != null) {
            result.addProperty("subtree", subtree); //$NON-NLS-1$
        }
        result.add("items", childrenOf(treeRoot, "items", walk, 1)); //$NON-NLS-1$ //$NON-NLS-2$
        JsonArray attributes = childrenOf(treeRoot, "attributes", walk, 1); //$NON-NLS-1$
        if (attributes.size() > 0) {
            result.add("attributes", attributes); //$NON-NLS-1$
        }
        JsonArray commands = childrenOf(treeRoot, "formCommands", walk, 1); //$NON-NLS-1$
        if (commands.size() > 0) {
            result.add("commands", commands); //$NON-NLS-1$
        }
        // повний обхід без обмежень — щоб агент бачив, скільки елементів пропущено
        result.addProperty("totalElements", countElements(treeRoot)); //$NON-NLS-1$
        result.addProperty("returnedElements", walk.emitted); //$NON-NLS-1$
        if (walk.truncated) {
            result.addProperty("truncated", true); //$NON-NLS-1$
            result.addProperty("hint", "Показано " + walk.emitted + " із " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + countElements(treeRoot) + " елементів. Звузьте обхід: subtree=<ім'я групи> " //$NON-NLS-1$
                    + "або depth=<рівнів>, чи підніміть maxElements."); //$NON-NLS-1$
        }
        return result;
    }

    /** Стан обходу дерева: скільки вузлів віддано, чи вперлись у ліміт. */
    private static final class Walk {
        private final int maxElements;
        private final int maxDepth;
        private int emitted;
        private boolean truncated;

        Walk(int maxElements, int maxDepth) {
            this.maxElements = maxElements;
            this.maxDepth = maxDepth;
        }

        boolean take() {
            if (emitted >= maxElements) {
                truncated = true;
                return false;
            }
            emitted++;
            return true;
        }

        boolean deeper(int depth) {
            if (depth > maxDepth) {
                truncated = true;
                return false;
            }
            return true;
        }
    }

    /** Елемент дерева за іменем — точка входу для subtree. */
    private static Element findByName(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element element)) {
                continue;
            }
            String elementName = element.getAttribute("name"); //$NON-NLS-1$
            if (elementName.isEmpty()) {
                String childName = directChildText(element, "name"); //$NON-NLS-1$
                elementName = childName == null ? "" : childName; //$NON-NLS-1$
            }
            if (name.equalsIgnoreCase(elementName)) {
                return element;
            }
            Element found = findByName(element, name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** Скільки елементів дерева форми всього — без урахування лімітів. */
    private static int countElements(Element parent) {
        int count = 0;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element
                    && ("items".equals(element.getTagName()) //$NON-NLS-1$
                            || "attributes".equals(element.getTagName()) //$NON-NLS-1$
                            || "formCommands".equals(element.getTagName()))) { //$NON-NLS-1$
                count += 1 + countElements(element);
            }
        }
        return count;
    }

    /** Прямі дочірні елементи root з даною назвою тега → JSON-дерево. */
    private static JsonArray childrenOf(Element parent, String tagName, Walk walk, int depth) {
        JsonArray result = new JsonArray();
        if (!walk.deeper(depth)) {
            return result;
        }
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element && tagName.equals(element.getTagName())) {
                if (!walk.take()) {
                    break;
                }
                result.add(describe(element, walk, depth));
            }
        }
        return result;
    }

    private static JsonObject describe(Element element, Walk walk, int depth) {
        JsonObject entry = new JsonObject();
        String type = element.getAttribute("xsi:type"); //$NON-NLS-1$
        if (!type.isEmpty()) {
            entry.addProperty("type", type.replace("form:", "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        String name = element.getAttribute("name"); //$NON-NLS-1$
        if (name.isEmpty()) {
            String childName = directChildText(element, "name"); //$NON-NLS-1$
            if (childName != null) {
                name = childName;
            }
        }
        if (!name.isEmpty()) {
            entry.addProperty("name", name); //$NON-NLS-1$
        }
        String dataPath = directChildText(element, "dataPath"); //$NON-NLS-1$
        if (dataPath != null) {
            entry.addProperty("dataPath", dataPath); //$NON-NLS-1$
        }
        String title = localizedTitle(element);
        if (title != null) {
            entry.addProperty("title", title); //$NON-NLS-1$
        }
        JsonArray children = childrenOf(element, "items", walk, depth + 1); //$NON-NLS-1$
        if (children.size() > 0) {
            entry.add("items", children); //$NON-NLS-1$
        }
        return entry;
    }

    /** Локалізований заголовок: <title><key>ru</key><value>Текст</value></title> → "Текст". */
    private static String localizedTitle(Element parent) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element && "title".equals(element.getTagName())) { //$NON-NLS-1$
                String value = directChildText(element, "value"); //$NON-NLS-1$
                if (value != null) {
                    return value;
                }
                String text = element.getTextContent();
                if (text != null && !text.isBlank()) {
                    return text.strip().replaceAll("\\s+", " "); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
        return null;
    }

    /** Текст прямого дочірнього елемента. */
    private static String directChildText(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && tagName.equals(element.getTagName())) {
                String text = element.getTextContent();
                if (text != null && !text.isBlank()) {
                    return text.strip().replaceAll("\\s+", " "); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
        return null;
    }
}
