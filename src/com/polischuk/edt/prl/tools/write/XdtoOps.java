/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.write;

import java.util.List;

import org.eclipse.emf.ecore.EObject;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.QName;
import com._1c.g5.v8.dt.metadata.mdclass.XDTOPackage;
import com._1c.g5.v8.dt.xdto.model.Enumeration;
import com._1c.g5.v8.dt.xdto.model.Form;
import com._1c.g5.v8.dt.xdto.model.Import;
import com._1c.g5.v8.dt.xdto.model.ObjectType;
import com._1c.g5.v8.dt.xdto.model.Package;
import com._1c.g5.v8.dt.xdto.model.Property;
import com._1c.g5.v8.dt.xdto.model.ValueType;
import com._1c.g5.v8.dt.xdto.model.XdtoFactory;

/**
 * XDTO-пакети: пространство імен, типи об'єктів (ObjectType) зі властивостями, типи значень
 * (ValueType) і видалення. Працює з моделлю Package (Package.xdto) у відкритій BM-транзакції.
 * Типи властивостей: "xs:string", "xs:dateTime", "v8:UUID" (http://v8.1c.ru/8.1/data/core),
 * "{uri}ім'я", або просте ім'я — тип цього ж пакета.
 */
final class XdtoOps {

    private static final String XSD_NS = "http://www.w3.org/2001/XMLSchema"; //$NON-NLS-1$
    private static final String CORE_NS = "http://v8.1c.ru/8.1/data/core"; //$NON-NLS-1$

    private XdtoOps() {
    }

    /** Створює Package.xdto нового пакета (із nsUri = namespace метаданих), якщо його ще немає. */
    static Package ensurePackage(IBmTransaction transaction, XDTOPackage metadata) {
        if (metadata.getPackage() != null) {
            return metadata.getPackage();
        }
        Package created = XdtoFactory.eINSTANCE.createPackage();
        created.setNsUri(metadata.getNamespace() == null ? "" : metadata.getNamespace()); //$NON-NLS-1$
        if (created instanceof IBmObject bmPackage) {
            transaction.attachTopObject(bmPackage, "XDTOPackage." + metadata.getName() + ".Package"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        metadata.setPackage(created);
        return created;
    }

    private static Package packageOf(IBmTransaction transaction, EObject object) {
        if (!(object instanceof XDTOPackage metadata)) {
            throw new IllegalArgumentException("Операція призначена для kind=XDTOPackage, а не " //$NON-NLS-1$
                    + object.eClass().getName());
        }
        return ensurePackage(transaction, metadata);
    }

    static JsonObject apply(IBmTransaction transaction, EObject object, String operation, JsonObject args) {
        Package xdto = packageOf(transaction, object);
        XDTOPackage metadata = (XDTOPackage) object;
        return switch (operation) {
        case "setXdtoNamespace" -> setNamespace(metadata, xdto, text(args, "namespace")); //$NON-NLS-1$ //$NON-NLS-2$
        case "addXdtoObjectType" -> addObjectType(xdto, args); //$NON-NLS-1$
        case "addXdtoValueType" -> addValueType(xdto, args); //$NON-NLS-1$
        case "addXdtoProperty" -> addProperty(xdto, text(args, "xdtoType"), args); //$NON-NLS-1$ //$NON-NLS-2$
        case "removeXdtoType" -> removeType(xdto, text(args, "item")); //$NON-NLS-1$ //$NON-NLS-2$
        case "removeXdtoProperty" -> removeProperty(xdto, text(args, "xdtoType"), text(args, "property")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        default -> throw new IllegalArgumentException("Невідома XDTO-операція: " + operation); //$NON-NLS-1$
        };
    }

    private static JsonObject setNamespace(XDTOPackage metadata, Package xdto, String namespace) {
        JsonObject change = new JsonObject();
        change.addProperty("old", metadata.getNamespace()); //$NON-NLS-1$
        metadata.setNamespace(namespace);
        xdto.setNsUri(namespace);
        change.addProperty("new", namespace); //$NON-NLS-1$
        return change;
    }

    private static JsonObject addObjectType(Package xdto, JsonObject args) {
        String name = text(args, "item"); //$NON-NLS-1$
        JsonObject change = new JsonObject();
        change.addProperty("type", name); //$NON-NLS-1$
        if (findObjectType(xdto, name) != null || findValueType(xdto, name) != null) {
            change.addProperty("alreadyExists", true); //$NON-NLS-1$
            return change;
        }
        ObjectType type = XdtoFactory.eINSTANCE.createObjectType();
        type.setName(name);
        if (args.has("open")) { //$NON-NLS-1$
            type.setOpen(args.get("open").getAsBoolean()); //$NON-NLS-1$
        }
        if (args.has("abstract")) { //$NON-NLS-1$
            type.setAbstract(args.get("abstract").getAsBoolean()); //$NON-NLS-1$
        }
        if (args.has("baseType")) { //$NON-NLS-1$
            type.setBaseType(qname(xdto, args.get("baseType").getAsString())); //$NON-NLS-1$
        }
        xdto.getObjects().add(type);
        change.addProperty("added", "ObjectType"); //$NON-NLS-1$ //$NON-NLS-2$
        if (args.has("xdtoProperties") && args.get("xdtoProperties").isJsonArray()) { //$NON-NLS-1$ //$NON-NLS-2$
            JsonArray created = new JsonArray();
            for (JsonElement element : args.getAsJsonArray("xdtoProperties")) { //$NON-NLS-1$
                JsonObject spec = element.getAsJsonObject();
                if (!spec.has("property") && spec.has("name")) { //$NON-NLS-1$ //$NON-NLS-2$
                    spec.add("property", spec.get("name")); //$NON-NLS-1$ //$NON-NLS-2$
                }
                if (!spec.has("propertyType") && spec.has("type")) { //$NON-NLS-1$ //$NON-NLS-2$
                    spec.add("propertyType", spec.get("type")); //$NON-NLS-1$ //$NON-NLS-2$
                }
                addProperty(xdto, name, spec);
                created.add(spec.get("property").getAsString()); //$NON-NLS-1$
            }
            change.add("properties", created); //$NON-NLS-1$
        }
        return change;
    }

    private static JsonObject addValueType(Package xdto, JsonObject args) {
        String name = text(args, "item"); //$NON-NLS-1$
        JsonObject change = new JsonObject();
        change.addProperty("type", name); //$NON-NLS-1$
        if (findObjectType(xdto, name) != null || findValueType(xdto, name) != null) {
            change.addProperty("alreadyExists", true); //$NON-NLS-1$
            return change;
        }
        ValueType type = XdtoFactory.eINSTANCE.createValueType();
        type.setName(name);
        type.setBaseType(qname(xdto, args.has("baseType") ? args.get("baseType").getAsString() : "xs:string")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (args.has("length")) { //$NON-NLS-1$
            type.setLength(args.get("length").getAsInt()); //$NON-NLS-1$
        }
        if (args.has("minLength")) { //$NON-NLS-1$
            type.setMinLength(args.get("minLength").getAsInt()); //$NON-NLS-1$
        }
        if (args.has("maxLength")) { //$NON-NLS-1$
            type.setMaxLength(args.get("maxLength").getAsInt()); //$NON-NLS-1$
        }
        if (args.has("enumerations") && args.get("enumerations").isJsonArray()) { //$NON-NLS-1$ //$NON-NLS-2$
            for (JsonElement value : args.getAsJsonArray("enumerations")) { //$NON-NLS-1$
                Enumeration enumeration = XdtoFactory.eINSTANCE.createEnumeration();
                enumeration.setType(type.getBaseType());
                enumeration.setContent(value.getAsString());
                type.getEnumerations().add(enumeration);
            }
        }
        xdto.getTypes().add(type);
        change.addProperty("added", "ValueType"); //$NON-NLS-1$ //$NON-NLS-2$
        return change;
    }

    /** Властивість об'єктного типу: property (ім'я), propertyType, lowerBound, upperBound, nillable, form. */
    private static JsonObject addProperty(Package xdto, String typeName, JsonObject args) {
        ObjectType owner = findObjectType(xdto, typeName);
        if (owner == null) {
            throw new IllegalArgumentException("Об'єктний тип не знайдено у пакеті: " + typeName); //$NON-NLS-1$
        }
        String propertyName = text(args, "property"); //$NON-NLS-1$
        JsonObject change = new JsonObject();
        change.addProperty("type", typeName); //$NON-NLS-1$
        change.addProperty("property", propertyName); //$NON-NLS-1$
        for (Property existing : owner.getProperties()) {
            if (propertyName.equalsIgnoreCase(existing.getName())) {
                change.addProperty("alreadyExists", true); //$NON-NLS-1$
                return change;
            }
        }
        Property property = XdtoFactory.eINSTANCE.createProperty();
        property.setName(propertyName);
        property.setType(qname(xdto, args.has("propertyType") ? args.get("propertyType").getAsString() : "xs:string")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (args.has("lowerBound")) { //$NON-NLS-1$
            property.setLowerBound(args.get("lowerBound").getAsInt()); //$NON-NLS-1$
        }
        if (args.has("upperBound")) { //$NON-NLS-1$
            property.setUpperBound(args.get("upperBound").getAsInt()); //$NON-NLS-1$
        }
        if (args.has("nillable")) { //$NON-NLS-1$
            property.setNillable(args.get("nillable").getAsBoolean()); //$NON-NLS-1$
        }
        if (args.has("form")) { //$NON-NLS-1$
            property.setForm(parseForm(args.get("form").getAsString())); //$NON-NLS-1$
        }
        owner.getProperties().add(property);
        change.addProperty("added", true); //$NON-NLS-1$
        change.addProperty("propertyType", args.has("propertyType") ? args.get("propertyType").getAsString() : "xs:string"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        return change;
    }

    private static JsonObject removeType(Package xdto, String name) {
        JsonObject change = new JsonObject();
        change.addProperty("type", name); //$NON-NLS-1$
        ObjectType objectType = findObjectType(xdto, name);
        if (objectType != null) {
            xdto.getObjects().remove(objectType);
            change.addProperty("removed", "ObjectType"); //$NON-NLS-1$ //$NON-NLS-2$
            return change;
        }
        ValueType valueType = findValueType(xdto, name);
        if (valueType != null) {
            xdto.getTypes().remove(valueType);
            change.addProperty("removed", "ValueType"); //$NON-NLS-1$ //$NON-NLS-2$
            return change;
        }
        change.addProperty("alreadyAbsent", true); //$NON-NLS-1$
        return change;
    }

    private static JsonObject removeProperty(Package xdto, String typeName, String propertyName) {
        ObjectType owner = findObjectType(xdto, typeName);
        if (owner == null) {
            throw new IllegalArgumentException("Об'єктний тип не знайдено у пакеті: " + typeName); //$NON-NLS-1$
        }
        JsonObject change = new JsonObject();
        change.addProperty("type", typeName); //$NON-NLS-1$
        change.addProperty("property", propertyName); //$NON-NLS-1$
        for (Property property : owner.getProperties()) {
            if (propertyName.equalsIgnoreCase(property.getName())) {
                owner.getProperties().remove(property);
                change.addProperty("removed", true); //$NON-NLS-1$
                return change;
            }
        }
        change.addProperty("alreadyAbsent", true); //$NON-NLS-1$
        return change;
    }

    // ------------------------------------------------------------------ допоміжне

    private static ObjectType findObjectType(Package xdto, String name) {
        for (ObjectType type : xdto.getObjects()) {
            if (name.equalsIgnoreCase(type.getName())) {
                return type;
            }
        }
        return null;
    }

    private static ValueType findValueType(Package xdto, String name) {
        for (ValueType type : xdto.getTypes()) {
            if (name.equalsIgnoreCase(type.getName())) {
                return type;
            }
        }
        return null;
    }

    private static Form parseForm(String raw) {
        return switch (raw.toLowerCase(java.util.Locale.ROOT)) {
        case "element", "элемент", "елемент" -> Form.ELEMENT; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        case "attribute", "атрибут" -> Form.ATTRIBUTE; //$NON-NLS-1$ //$NON-NLS-2$
        case "text", "текст" -> Form.TEXT; //$NON-NLS-1$ //$NON-NLS-2$
        default -> throw new IllegalArgumentException("form: Element | Attribute | Text, отримано " + raw); //$NON-NLS-1$
        };
    }

    /** "xs:string" | "v8:UUID" | "{uri}name" | "name" (тип цього пакета); чужі простори імен додає в imports. */
    private static QName qname(Package xdto, String raw) {
        String namespace;
        String local;
        String value = raw.strip();
        if (value.startsWith("{") && value.indexOf('}') > 0) { //$NON-NLS-1$
            namespace = value.substring(1, value.indexOf('}'));
            local = value.substring(value.indexOf('}') + 1);
        } else if (value.contains(":")) { //$NON-NLS-1$
            String prefix = value.substring(0, value.indexOf(':'));
            local = value.substring(value.indexOf(':') + 1);
            namespace = switch (prefix.toLowerCase(java.util.Locale.ROOT)) {
            case "xs", "xsd" -> XSD_NS; //$NON-NLS-1$ //$NON-NLS-2$
            case "v8", "core", "d3p1" -> CORE_NS; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            default -> throw new IllegalArgumentException("Невідомий префікс простору імен '" + prefix //$NON-NLS-1$
                    + "' (xs, v8) — використайте форму {uri}ім'я"); //$NON-NLS-1$
            };
        } else {
            namespace = xdto.getNsUri();
            local = value;
        }
        if (!XSD_NS.equals(namespace) && namespace != null && !namespace.equals(xdto.getNsUri())) {
            boolean imported = false;
            List<Import> dependencies = xdto.getDependencies();
            for (Import dependency : dependencies) {
                if (namespace.equals(dependency.getNamespace())) {
                    imported = true;
                    break;
                }
            }
            if (!imported) {
                Import dependency = XdtoFactory.eINSTANCE.createImport();
                dependency.setNamespace(namespace);
                dependencies.add(dependency);
            }
        }
        QName qname = McoreFactory.eINSTANCE.createQName();
        qname.setNsUri(namespace);
        qname.setName(local);
        return qname;
    }

    private static String text(JsonObject args, String name) {
        if (!args.has(name) || args.get(name).isJsonNull() || args.get(name).getAsString().isBlank()) {
            throw new IllegalArgumentException("Обов'язковий параметр відсутній: " + name); //$NON-NLS-1$
        }
        return args.get(name).getAsString();
    }
}
