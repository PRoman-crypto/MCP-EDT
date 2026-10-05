/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.edt;

import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Рефлексивне читання EMF-моделі метаданих EDT. Працюємо через EStructuralFeature,
 * а не типізовані класи mdclass — щоб не залежати від дрейфу API між версіями EDT.
 */
public final class Emf {

    private Emf() {
    }

    public static Object get(EObject object, String featureName) {
        if (object == null) {
            return null;
        }
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        return feature == null ? null : object.eGet(feature);
    }

    public static String str(EObject object, String featureName) {
        Object value = get(object, featureName);
        return value == null ? null : String.valueOf(value);
    }

    /** name об'єкта метаданих. */
    public static String name(EObject object) {
        return str(object, "name"); //$NON-NLS-1$
    }

    /** synonym (локалізований EMap; в EMF це список Map.Entry) → JSON-об'єкт {lang: text}. */
    public static JsonObject synonym(EObject object) {
        Object value = get(object, "synonym"); //$NON-NLS-1$
        JsonObject result = new JsonObject();
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.addProperty(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map.Entry<?, ?> entry) {
                    result.addProperty(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
        }
        return result.size() == 0 ? null : result;
    }

    /** Імена типів із TypeDescription (feature "type" → "types" → TypeItem.name). */
    public static JsonArray typeNames(EObject object) {
        Object type = get(object, "type"); //$NON-NLS-1$
        if (!(type instanceof EObject typeDescription)) {
            return null;
        }
        Object types = get(typeDescription, "types"); //$NON-NLS-1$
        if (!(types instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        JsonArray result = new JsonArray();
        for (Object item : list) {
            if (item instanceof EObject typeItem) {
                String nameRu = str(typeItem, "nameRu"); //$NON-NLS-1$
                String name = nameRu != null && !nameRu.isBlank() ? nameRu : str(typeItem, "name"); //$NON-NLS-1$
                if (name != null) {
                    result.add(name);
                }
            }
        }
        return result.size() == 0 ? null : result;
    }

    /** Усі встановлені скалярні атрибути об'єкта → JSON (onlySet=false — включно з дефолтами). */
    public static JsonObject attributes(EObject object, boolean onlySet) {
        JsonObject result = new JsonObject();
        for (EAttribute attribute : object.eClass().getEAllAttributes()) {
            if (onlySet && !object.eIsSet(attribute)) {
                continue;
            }
            Object value = object.eGet(attribute);
            if (value == null || String.valueOf(value).isEmpty()) {
                continue;
            }
            if (value instanceof List<?> list) {
                if (list.isEmpty()) {
                    continue;
                }
                JsonArray array = new JsonArray();
                list.forEach(v -> array.add(String.valueOf(v)));
                result.add(attribute.getName(), array);
            } else {
                result.addProperty(attribute.getName(), String.valueOf(value));
            }
        }
        return result;
    }
}
