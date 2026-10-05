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
import java.util.Locale;

import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.core.resources.IProject;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.polischuk.edt.prl.edt.V8Access;
import com.polischuk.edt.prl.edt.WorkspaceFiles;
import com.polischuk.edt.prl.tools.McpTool;

/** Права ролі з Rights.rights: об'єкти, надані права, RLS-умови. */
public final class GetRoleRightsTool implements McpTool {

    private static final int DEFAULT_LIMIT = 200;

    @Override
    public String name() {
        return "get_role_rights"; //$NON-NLS-1$
    }

    @Override
    public String description() {
        return "Права ролі на об'єкти метаданих (із Rights.rights): які права надані, RLS-умови. " //$NON-NLS-1$
                + "objectFilter — підрядок імені об'єкта (напр. Catalog.Номенклатура); " //$NON-NLS-1$
                + "onlyRls:true — лише об'єкти з обмеженнями на рівні записів."; //$NON-NLS-1$
    }

    @Override
    public JsonObject inputSchema() {
        return JsonParser.parseString("""
                {"type":"object","properties":{
                  "project":{"type":"string","description":"Ім'я проєкту EDT (необов'язково, якщо проєкт один)"},
                  "role":{"type":"string","description":"Ім'я ролі (list_metadata_objects kind=Role)"},
                  "objectFilter":{"type":"string","description":"Підрядок імені об'єкта (без регістру)"},
                  "onlyRls":{"type":"boolean","default":false},
                  "offset":{"type":"integer","default":0},
                  "limit":{"type":"integer","default":200}
                },"required":["role"]}""").getAsJsonObject(); //$NON-NLS-1$
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
        String projectName = arguments.has("project") ? arguments.get("project").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$
        String role = arguments.get("role").getAsString(); //$NON-NLS-1$
        String objectFilter = arguments.has("objectFilter") //$NON-NLS-1$
                ? arguments.get("objectFilter").getAsString().toLowerCase(Locale.ROOT) : null; //$NON-NLS-1$
        boolean onlyRls = arguments.has("onlyRls") && arguments.get("onlyRls").getAsBoolean(); //$NON-NLS-1$ //$NON-NLS-2$
        int offset = arguments.has("offset") ? arguments.get("offset").getAsInt() : 0; //$NON-NLS-1$ //$NON-NLS-2$
        int limit = arguments.has("limit") ? arguments.get("limit").getAsInt() : DEFAULT_LIMIT; //$NON-NLS-1$ //$NON-NLS-2$

        IProject project = V8Access.resolveEclipseProject(projectName);
        String xml = WorkspaceFiles.read(project, "src/Roles/" + role + "/Rights.rights"); //$NON-NLS-1$ //$NON-NLS-2$

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        Element root = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                .getDocumentElement();

        JsonObject result = new JsonObject();
        result.addProperty("role", role); //$NON-NLS-1$
        result.addProperty("setForNewObjects", childText(root, "setForNewObjects")); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("setForAttributesByDefault", childText(root, "setForAttributesByDefault")); //$NON-NLS-1$ //$NON-NLS-2$

        JsonArray objects = new JsonArray();
        int matched = 0;
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element object) || !"object".equals(object.getTagName())) { //$NON-NLS-1$
                continue;
            }
            String objectName = childText(object, "name"); //$NON-NLS-1$
            if (objectName == null
                    || (objectFilter != null && !objectName.toLowerCase(Locale.ROOT).contains(objectFilter))) {
                continue;
            }
            JsonArray rights = new JsonArray();
            JsonArray rls = new JsonArray();
            NodeList parts = object.getChildNodes();
            for (int j = 0; j < parts.getLength(); j++) {
                if (!(parts.item(j) instanceof Element right) || !"right".equals(right.getTagName())) { //$NON-NLS-1$
                    continue;
                }
                String rightName = childText(right, "name"); //$NON-NLS-1$
                if (!"false".equals(childText(right, "value"))) { //$NON-NLS-1$ //$NON-NLS-2$
                    rights.add(rightName);
                }
                NodeList restrictions = right.getElementsByTagName("restrictionByCondition"); //$NON-NLS-1$
                for (int k = 0; k < restrictions.getLength(); k++) {
                    if (restrictions.item(k) instanceof Element restriction) {
                        String condition = childText(restriction, "condition"); //$NON-NLS-1$
                        if (condition != null && !condition.isBlank()) {
                            JsonObject entry = new JsonObject();
                            entry.addProperty("right", rightName); //$NON-NLS-1$
                            entry.addProperty("condition", condition.strip()); //$NON-NLS-1$
                            rls.add(entry);
                        }
                    }
                }
            }
            if (onlyRls && rls.size() == 0) {
                continue;
            }
            matched++;
            if (matched <= offset || objects.size() >= limit) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("object", objectName); //$NON-NLS-1$
            entry.add("rights", rights); //$NON-NLS-1$
            if (rls.size() > 0) {
                entry.add("rls", rls); //$NON-NLS-1$
            }
            objects.add(entry);
        }
        result.addProperty("totalMatched", matched); //$NON-NLS-1$
        result.addProperty("returned", objects.size()); //$NON-NLS-1$
        result.add("objects", objects); //$NON-NLS-1$
        return result;
    }

    private static String childText(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element && tagName.equals(element.getTagName())) {
                return element.getTextContent();
            }
        }
        return null;
    }
}
