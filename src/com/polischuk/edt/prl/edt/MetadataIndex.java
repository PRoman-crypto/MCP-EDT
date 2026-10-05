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
import java.util.Locale;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;

/** Пошук колекцій і об'єктів у конфігурації та відповідність шляхам у src/. */
public final class MetadataIndex {

    private MetadataIndex() {
    }

    /** Колекція конфігурації за канонічним видом (за EClass елементів або ім'ям колекції). */
    public static EReference findCollectionRef(EObject configuration, String canonicalKind) {
        String wanted = canonicalKind.toLowerCase(Locale.ROOT);
        for (EReference reference : configuration.eClass().getEAllReferences()) {
            // лише колекції-власники: звичайні посилання (напр. defaultRoles, subsystems-посилання) не підходять
            if (!reference.isMany() || !reference.isContainment()) {
                continue;
            }
            if (reference.getName().toLowerCase(Locale.ROOT).equals(wanted)
                    || reference.getEReferenceType().getName().toLowerCase(Locale.ROOT).equals(wanted)) {
                return reference;
            }
            Object value = configuration.eGet(reference);
            if (value instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof EObject first
                    && first.eClass().getName().toLowerCase(Locale.ROOT).equals(wanted)) {
                return reference;
            }
        }
        return null;
    }

    public static List<?> findCollection(EObject configuration, String canonicalKind) {
        EReference reference = findCollectionRef(configuration, canonicalKind);
        if (reference == null) {
            return null;
        }
        Object value = configuration.eGet(reference);
        return value instanceof List<?> list ? list : null;
    }

    public static EObject findObject(EObject configuration, String kind, String name) {
        String canonical = KindRegistry.canonical(kind);
        List<?> collection = findCollection(configuration, canonical);
        if (collection == null) {
            throw new IllegalArgumentException("Вид метаданих не знайдено: " + kind //$NON-NLS-1$
                    + " (канонічно: " + canonical + "). list_metadata_objects без kind покаже доступні види."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        for (Object item : collection) {
            if (item instanceof EObject object && name.equalsIgnoreCase(Emf.name(object))) {
                return object;
            }
        }
        throw new IllegalArgumentException("Об'єкт не знайдено: " + canonical + "." + name //$NON-NLS-1$ //$NON-NLS-2$
                + ". Використайте list_metadata_objects із nameFilter для пошуку імені."); //$NON-NLS-1$
    }

    /** Каталог об'єкта у вихідниках EDT: src/<Collection>/<Name>, напр. src/Catalogs/Номенклатура. */
    public static String objectFolder(EObject configuration, String kind, String objectName) {
        String canonical = KindRegistry.canonical(kind);
        EReference reference = findCollectionRef(configuration, canonical);
        if (reference == null) {
            throw new IllegalArgumentException("Вид метаданих не знайдено: " + kind); //$NON-NLS-1$
        }
        String folder = Character.toUpperCase(reference.getName().charAt(0)) + reference.getName().substring(1);
        return "src/" + folder + "/" + objectName; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
