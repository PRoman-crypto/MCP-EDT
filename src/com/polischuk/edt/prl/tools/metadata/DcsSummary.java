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

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.polischuk.edt.prl.edt.WorkspaceFiles;

/**
 * Компактна карта схем компоновки даних об'єкта — для {@code get_object_details}.
 *
 * <p>Навмисно без текстів запитів і без списків полів: завдання — щоб агент, який питає
 * «що це за звіт», одразу бачив, скільки в схемі наборів, полів і параметрів, і вирішував,
 * чи потрібен важкий {@code get_skd}. Раніше в деталях звіту були самі імена макетів, тож
 * дізнатись бодай щось про схему можна було лише витягнувши її цілком (~12 КБ).
 */
final class DcsSummary {

    /** Скільки макетів-СКД описувати; більше на об'єкті практично не буває. */
    private static final int MAX_TEMPLATES = 10;

    private DcsSummary() {
    }

    /**
     * Карти всіх макетів-СКД об'єкта; null — макетів-СКД немає (звичайний об'єкт,
     * або макети лише табличні).
     */
    static JsonArray forObject(IProject project, String objectFolder) {
        List<String> names = listDcsTemplates(project, objectFolder);
        if (names.isEmpty()) {
            return null;
        }
        JsonArray result = new JsonArray();
        for (String name : names) {
            if (result.size() >= MAX_TEMPLATES) {
                break;
            }
            String path = objectFolder + "/Templates/" + name + "/Template.dcs"; //$NON-NLS-1$ //$NON-NLS-2$
            JsonObject entry = describe(project, path, name, names.size() == 1);
            if (entry != null) {
                result.add(entry);
            }
        }
        return result.size() > 0 ? result : null;
    }

    private static JsonObject describe(IProject project, String path, String name, boolean single) {
        Element root;
        try {
            String xml = WorkspaceFiles.read(project, path);
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false); //$NON-NLS-1$
            DocumentBuilder builder = factory.newDocumentBuilder();
            root = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                    .getDocumentElement();
        } catch (Exception e) { // зіпсований або нечитний макет не має ламати деталі об'єкта
            JsonObject broken = new JsonObject();
            broken.addProperty("name", name); //$NON-NLS-1$
            broken.addProperty("error", e.getClass().getSimpleName() + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            return broken;
        }

        JsonObject entry = new JsonObject();
        entry.addProperty("name", name); //$NON-NLS-1$
        entry.addProperty("isMain", single || isMainName(name)); //$NON-NLS-1$

        JsonArray dataSets = new JsonArray();
        for (Element dataSet : children(root, "dataSet")) { //$NON-NLS-1$
            JsonObject item = new JsonObject();
            String type = dataSet.getAttribute("xsi:type"); //$NON-NLS-1$
            if (!type.isEmpty()) {
                item.addProperty("type", type); //$NON-NLS-1$
            }
            String dataSetName = childText(dataSet, "name"); //$NON-NLS-1$
            if (dataSetName != null) {
                item.addProperty("name", dataSetName); //$NON-NLS-1$
            }
            item.addProperty("fieldCount", children(dataSet, "field").size()); //$NON-NLS-1$ //$NON-NLS-2$
            String query = childText(dataSet, "query"); //$NON-NLS-1$
            if (query != null) {
                item.addProperty("queryLength", query.length()); //$NON-NLS-1$
            }
            dataSets.add(item);
        }
        if (dataSets.size() > 0) {
            entry.add("dataSets", dataSets); //$NON-NLS-1$
        }

        JsonArray parameters = new JsonArray();
        for (Element parameter : children(root, "parameter")) { //$NON-NLS-1$
            JsonObject item = new JsonObject();
            String parameterName = childText(parameter, "name"); //$NON-NLS-1$
            if (parameterName != null) {
                item.addProperty("name", parameterName); //$NON-NLS-1$
            }
            String type = valueType(parameter);
            if (type != null) {
                item.addProperty("type", type); //$NON-NLS-1$
            }
            parameters.add(item);
        }
        if (parameters.size() > 0) {
            entry.add("parameters", parameters); //$NON-NLS-1$
        }

        int totalFields = children(root, "totalField").size(); //$NON-NLS-1$
        if (totalFields > 0) {
            entry.addProperty("totalFieldCount", totalFields); //$NON-NLS-1$
        }
        int calculated = children(root, "calculatedField").size(); //$NON-NLS-1$
        if (calculated > 0) {
            entry.addProperty("calculatedFieldCount", calculated); //$NON-NLS-1$
        }

        JsonArray variants = new JsonArray();
        for (Element variant : children(root, "settingsVariant")) { //$NON-NLS-1$
            String variantName = childText(variant, "dcsset:name"); //$NON-NLS-1$
            if (variantName != null) {
                variants.add(variantName);
            }
        }
        if (variants.size() > 0) {
            entry.add("settingsVariants", variants); //$NON-NLS-1$
        }
        return entry;
    }

    private static boolean isMainName(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("основнаясхема") || lower.startsWith("основнасхема"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Підкаталоги Templates, що містять Template.dcs. */
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
        } catch (Exception e) { // теку могли закрити під час обходу — не ламаємо деталі об'єкта
            return result;
        }
        return result;
    }

    private static List<Element> children(Element parent, String tagName) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element && tagName.equals(element.getTagName())) {
                result.add(element);
            }
        }
        return result;
    }

    private static String childText(Element parent, String tagName) {
        List<Element> found = children(parent, tagName);
        if (found.isEmpty()) {
            return null;
        }
        String text = found.get(0).getTextContent();
        return text == null || text.isBlank() ? null : text.strip();
    }

    /** Тип значення параметра: {@code <valueType><Type>xs:dateTime</Type></valueType>}. */
    private static String valueType(Element parent) {
        List<Element> valueTypes = children(parent, "valueType"); //$NON-NLS-1$
        if (valueTypes.isEmpty()) {
            return null;
        }
        String type = childText(valueTypes.get(0), "v8:Type"); //$NON-NLS-1$
        if (type == null) {
            type = childText(valueTypes.get(0), "Type"); //$NON-NLS-1$
        }
        return type == null ? null : type.replaceFirst("^(?:xs|v8|cfg):", ""); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
