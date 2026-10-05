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

import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.polischuk.edt.prl.edt.Emf;
import com.polischuk.edt.prl.edt.KindRegistry;

import com._1c.g5.v8.bm.core.IBmTransaction;

/**
 * Структурні операції над метаданими всередині відкритої BM-транзакції: пошук об'єктів
 * за "Вид.Ім'я", склад підсистем і планів обміну. Для batch і dryRun безпечні — працюють
 * лише з EMF-моделлю, яку відкочує/комітить викликач.
 */
final class StructureOps {

    private StructureOps() {
    }

    /**
     * Об'єкт за видом та іменем. Для Subsystem ім'я може бути шляхом через крапку
     * (Продажи.Отчеты) — вкладені підсистеми шукаються у колекції subsystems.
     */
    static EObject resolveObject(IBmTransaction transaction, String canonicalKind, String name) {
        if ("Subsystem".equals(canonicalKind) && name.indexOf('.') > 0) { //$NON-NLS-1$
            String[] segments = name.split("\\."); //$NON-NLS-1$
            EObject current = transaction.getTopObjectByFqn("Subsystem." + segments[0]); //$NON-NLS-1$
            for (int i = 1; current != null && i < segments.length; i++) {
                current = EditMetadataTool.findChild(current, "subsystems", segments[i]); //$NON-NLS-1$
            }
            return current;
        }
        return transaction.getTopObjectByFqn(canonicalKind + "." + name); //$NON-NLS-1$
    }

    /** "Справочник.Номенклатура" / "Catalog.Номенклатура" → об'єкт моделі. */
    static EObject resolveReference(IBmTransaction transaction, String reference) {
        int dot = reference.indexOf('.');
        if (dot <= 0 || dot == reference.length() - 1) {
            throw new IllegalArgumentException("Очікується 'Вид.Ім'я' (напр. Справочник.Номенклатура): " //$NON-NLS-1$
                    + reference);
        }
        String kind = KindRegistry.canonical(reference.substring(0, dot));
        EObject object = resolveObject(transaction, kind, reference.substring(dot + 1));
        if (object == null) {
            throw new IllegalArgumentException("Об'єкт не знайдено: " + kind + "." + reference.substring(dot + 1)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return object;
    }

    /** Масив рядків параметра (objects) або одиночне значення параметра singleName. */
    static List<String> stringList(JsonObject arguments, String arrayName, String singleName) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        if (arguments.has(arrayName) && arguments.get(arrayName).isJsonArray()) {
            arguments.getAsJsonArray(arrayName).forEach(element -> result.add(element.getAsString()));
        }
        if (singleName != null && arguments.has(singleName) && !arguments.get(singleName).isJsonNull()
                && !arguments.get(singleName).getAsString().isBlank()) {
            result.add(arguments.get(singleName).getAsString());
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("Обов'язковий параметр: " + arrayName //$NON-NLS-1$
                    + (singleName == null ? "" : " (або " + singleName + ")")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return result;
    }

    private static boolean same(EObject first, EObject second) {
        return first == second || (first != null && second != null && first.eClass() == second.eClass()
                && Emf.name(first) != null && Emf.name(first).equals(Emf.name(second))
                && first.eContainer() == second.eContainer());
    }

    /** Додає/прибирає об'єкти у складі підсистеми (Subsystem.content). */
    @SuppressWarnings("unchecked")
    static JsonObject subsystemContent(IBmTransaction transaction, EObject subsystem, JsonObject arguments,
            boolean add) {
        if (!"Subsystem".equals(subsystem.eClass().getName())) { //$NON-NLS-1$
            throw new IllegalArgumentException("Операція призначена для kind=Subsystem, а не " //$NON-NLS-1$
                    + subsystem.eClass().getName());
        }
        List<EObject> content = (List<EObject>) Emf.get(subsystem, "content"); //$NON-NLS-1$
        JsonArray changed = new JsonArray();
        JsonArray skipped = new JsonArray();
        for (String reference : stringList(arguments, "objects", "object")) { //$NON-NLS-1$ //$NON-NLS-2$
            EObject target = resolveReference(transaction, reference);
            EObject existing = null;
            for (EObject member : content) {
                if (same(member, target)) {
                    existing = member;
                    break;
                }
            }
            if (add) {
                if (existing != null) {
                    skipped.add(reference);
                } else {
                    content.add(target);
                    changed.add(reference);
                }
            } else if (existing == null) {
                skipped.add(reference);
            } else {
                content.remove(existing);
                changed.add(reference);
            }
        }
        JsonObject change = new JsonObject();
        change.addProperty("subsystem", Emf.name(subsystem)); //$NON-NLS-1$
        change.add(add ? "added" : "removed", changed); //$NON-NLS-1$ //$NON-NLS-2$
        if (skipped.size() > 0) {
            change.add("skipped", skipped); //$NON-NLS-1$
            change.addProperty("skippedReason", add ? "вже у складі" : "відсутні у складі"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        change.addProperty("contentSize", content.size()); //$NON-NLS-1$
        return change;
    }

    /** Додає/прибирає об'єкти у складі плану обміну (ExchangePlan.content, елементи з autoRecord). */
    @SuppressWarnings("unchecked")
    static JsonObject exchangePlanContent(IBmTransaction transaction, EObject plan, JsonObject arguments,
            boolean add) {
        if (!"ExchangePlan".equals(plan.eClass().getName())) { //$NON-NLS-1$
            throw new IllegalArgumentException("Операція призначена для kind=ExchangePlan, а не " //$NON-NLS-1$
                    + plan.eClass().getName());
        }
        EStructuralFeature feature = plan.eClass().getEStructuralFeature("content"); //$NON-NLS-1$
        if (!(feature instanceof EReference contentRef)) {
            throw new IllegalStateException("У плану обміну немає колекції content"); //$NON-NLS-1$
        }
        List<EObject> content = (List<EObject>) plan.eGet(contentRef);
        String autoRecord = arguments.has("autoRecord") && !arguments.get("autoRecord").isJsonNull() //$NON-NLS-1$ //$NON-NLS-2$
                ? arguments.get("autoRecord").getAsString() : null; //$NON-NLS-1$
        JsonArray changed = new JsonArray();
        JsonArray skipped = new JsonArray();
        for (String reference : stringList(arguments, "objects", "object")) { //$NON-NLS-1$ //$NON-NLS-2$
            EObject target = resolveReference(transaction, reference);
            EObject existing = null;
            for (EObject item : content) {
                if (Emf.get(item, "mdObject") instanceof EObject member && same(member, target)) { //$NON-NLS-1$
                    existing = item;
                    break;
                }
            }
            if (add) {
                if (existing != null) {
                    if (autoRecord != null) { // вже у складі — лише оновлюємо autoRecord
                        setAutoRecord(existing, autoRecord);
                        changed.add(reference);
                    } else {
                        skipped.add(reference);
                    }
                    continue;
                }
                EObject item = EcoreUtil.create(contentRef.getEReferenceType());
                EStructuralFeature mdObject = item.eClass().getEStructuralFeature("mdObject"); //$NON-NLS-1$
                if (mdObject == null) {
                    throw new IllegalStateException("Елемент складу плану обміну не має mdObject"); //$NON-NLS-1$
                }
                item.eSet(mdObject, target);
                if (autoRecord != null) {
                    setAutoRecord(item, autoRecord);
                }
                content.add(item);
                changed.add(reference);
            } else if (existing == null) {
                skipped.add(reference);
            } else {
                content.remove(existing);
                changed.add(reference);
            }
        }
        JsonObject change = new JsonObject();
        change.addProperty("exchangePlan", Emf.name(plan)); //$NON-NLS-1$
        change.add(add ? "added" : "removed", changed); //$NON-NLS-1$ //$NON-NLS-2$
        if (skipped.size() > 0) {
            change.add("skipped", skipped); //$NON-NLS-1$
            change.addProperty("skippedReason", add ? "вже у складі" : "відсутні у складі"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if (autoRecord != null) {
            change.addProperty("autoRecord", autoRecord); //$NON-NLS-1$
        }
        change.addProperty("contentSize", content.size()); //$NON-NLS-1$
        return change;
    }

    private static void setAutoRecord(EObject item, String value) {
        EStructuralFeature feature = item.eClass().getEStructuralFeature("autoRecord"); //$NON-NLS-1$
        if (!(feature instanceof EAttribute attribute)) {
            throw new IllegalArgumentException("Елемент складу не має autoRecord"); //$NON-NLS-1$
        }
        item.eSet(attribute, EditMetadataTool.convertScalar(attribute, value));
    }
}
