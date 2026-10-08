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
import com.google.gson.JsonElement;
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
     * Об'єкт за видом та іменем. Для Subsystem ім'я може бути шляхом через крапку або слеш
     * (Продажи.Отчеты, Продажи/Отчеты), FQN моделі (Продажи.Subsystem.Отчеты) або унікальним
     * коротким іменем вкладеної підсистеми.
     */
    static EObject resolveObject(IBmTransaction transaction, String canonicalKind, String name) {
        if ("Subsystem".equals(canonicalKind)) { //$NON-NLS-1$
            return resolveSubsystem(transaction, name);
        }
        return transaction.getTopObjectByFqn(canonicalKind + "." + name); //$NON-NLS-1$
    }

    /**
     * Вкладена підсистема в EDT не є containment-елементом батька (у колекції containment
     * батька лише synonym/explanation), тому шукаємо двома шляхами: у списку {@code subsystems}
     * батька і як окремий top-об'єкт BM з FQN {@code Subsystem.Батько.Subsystem.Дитина}.
     */
    private static EObject resolveSubsystem(IBmTransaction transaction, String path) {
        List<String> segments = subsystemSegments(path);
        if (segments.isEmpty()) {
            return null;
        }
        StringBuilder fqn = new StringBuilder("Subsystem.").append(segments.get(0)); //$NON-NLS-1$
        EObject current = transaction.getTopObjectByFqn(fqn.toString());
        if (current == null && segments.size() == 1) {
            return uniqueSubsystemByName(transaction, segments.get(0));
        }
        for (int i = 1; current != null && i < segments.size(); i++) {
            EObject child = namedChild(current, "subsystems", segments.get(i)); //$NON-NLS-1$
            fqn.append(".Subsystem.").append(child != null ? Emf.name(child) : segments.get(i)); //$NON-NLS-1$
            current = child != null ? child : transaction.getTopObjectByFqn(fqn.toString());
        }
        return current;
    }

    /** Шлях підсистеми → сегменти імен; роздільники '.' і '/', службові "Subsystem" між іменами відкидаються. */
    private static List<String> subsystemSegments(String path) {
        java.util.ArrayList<String> segments = new java.util.ArrayList<>();
        for (String token : path.split("[./\\\\]")) { //$NON-NLS-1$
            String clean = token.strip();
            if (clean.isEmpty()) {
                continue;
            }
            boolean marker = !segments.isEmpty() && "Subsystem".equalsIgnoreCase(clean); //$NON-NLS-1$
            if (!marker) {
                segments.add(clean);
            }
        }
        return segments;
    }

    private static EObject namedChild(EObject owner, String collection, String name) {
        if (Emf.get(owner, collection) instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof EObject child && name.equalsIgnoreCase(Emf.name(child))) {
                    return child;
                }
            }
        }
        return null;
    }

    /** Коротке ім'я вкладеної підсистеми: єдиний збіг у всій конфігурації; кілька збігів — помилка зі шляхами. */
    private static EObject uniqueSubsystemByName(IBmTransaction transaction, String name) {
        java.util.LinkedHashSet<EObject> matches = new java.util.LinkedHashSet<>();
        java.util.Iterator<com._1c.g5.v8.bm.core.IBmObject> tops = transaction.getTopObjectIterator(
                com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage.Literals.SUBSYSTEM);
        while (tops.hasNext()) {
            collectSubsystems(tops.next(), name, matches);
        }
        if (matches.size() > 1) {
            java.util.ArrayList<String> paths = new java.util.ArrayList<>();
            matches.forEach(match -> paths.add(subsystemPath(match)));
            throw new IllegalArgumentException("Підсистема '" + name + "' неоднозначна, вкажіть повний шлях: " //$NON-NLS-1$ //$NON-NLS-2$
                    + String.join("; ", paths)); //$NON-NLS-1$
        }
        return matches.isEmpty() ? null : matches.iterator().next();
    }

    private static void collectSubsystems(EObject subsystem, String name, java.util.Set<EObject> matches) {
        if (name.equalsIgnoreCase(Emf.name(subsystem))) {
            matches.add(subsystem);
        }
        if (Emf.get(subsystem, "subsystems") instanceof List<?> children) { //$NON-NLS-1$
            for (Object child : children) {
                if (child instanceof EObject nested) {
                    collectSubsystems(nested, name, matches);
                }
            }
        }
    }

    private static String subsystemPath(EObject subsystem) {
        StringBuilder path = new StringBuilder(String.valueOf(Emf.name(subsystem)));
        EObject parent = parentSubsystem(subsystem);
        while (parent != null) {
            path.insert(0, Emf.name(parent) + "."); //$NON-NLS-1$
            parent = parentSubsystem(parent);
        }
        return path.toString();
    }

    private static EObject parentSubsystem(EObject subsystem) {
        return Emf.get(subsystem, "parentSubsystem") instanceof EObject parent ? parent : null; //$NON-NLS-1$
    }

    /** "Справочник.Номенклатура" / "Catalog.Номенклатура" → об'єкт моделі. */
    static EObject resolveReference(IBmTransaction transaction, String reference) {
        int dot = reference.indexOf('.');
        if (dot <= 0 || dot == reference.length() - 1) {
            throw new IllegalArgumentException("Очікується 'Вид.Ім'я' (напр. Справочник.Номенклатура): " //$NON-NLS-1$
                    + reference);
        }
        String kind = KindRegistry.canonical(reference.substring(0, dot));
        String rest = reference.substring(dot + 1);
        if (!"Subsystem".equals(kind) && rest.indexOf('.') > 0) { //$NON-NLS-1$
            // вкладений елемент: Вид.Ім'я.Attribute.Ім'я / .TabularSection.Ім'я[.Attribute.Ім'я] / .Command.Ім'я
            String[] parts = rest.split("\\."); //$NON-NLS-1$
            if (parts.length % 2 == 0) {
                throw new IllegalArgumentException("Шлях " + reference //$NON-NLS-1$
                        + " має вигляд Вид.Ім'я[.Attribute|TabularSection|Command|Dimension|Resource.Ім'я]..."); //$NON-NLS-1$
            }
            EObject current = resolveObject(transaction, kind, parts[0]);
            if (current == null) {
                throw new IllegalArgumentException("Об'єкт не знайдено: " + kind + "." + parts[0]); //$NON-NLS-1$ //$NON-NLS-2$
            }
            for (int i = 1; i < parts.length; i += 2) {
                current = EditMetadataTool.findChild(current, childCollection(parts[i]), parts[i + 1]);
            }
            return current;
        }
        EObject object = resolveObject(transaction, kind, rest);
        if (object == null) {
            throw new IllegalArgumentException("Об'єкт не знайдено: " + kind + "." + rest); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return object;
    }

    private static String childCollection(String token) {
        return switch (token.toLowerCase(java.util.Locale.ROOT)) {
        case "attribute", "реквизит", "реквізит" -> "attributes"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        case "tabularsection", "табличнаячасть", "табличначастина" -> "tabularSections"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        case "command", "команда" -> "commands"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        case "dimension", "измерение", "вимір" -> "dimensions"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        case "resource", "ресурс" -> "resources"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        default -> throw new IllegalArgumentException("Невідомий елемент шляху: " + token //$NON-NLS-1$
                + " (Attribute | TabularSection | Command | Dimension | Resource)"); //$NON-NLS-1$
        };
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
        java.util.LinkedHashMap<String, String> requested = new java.util.LinkedHashMap<>();
        if (arguments.has("items") && arguments.get("items").isJsonArray()) { //$NON-NLS-1$ //$NON-NLS-2$
            for (com.google.gson.JsonElement element : arguments.getAsJsonArray("items")) { //$NON-NLS-1$
                JsonObject entry = element.getAsJsonObject();
                String objectName = entry.has("object") ? entry.get("object").getAsString() //$NON-NLS-1$ //$NON-NLS-2$
                        : entry.get("content").getAsString(); //$NON-NLS-1$
                requested.put(objectName, entry.has("autoRecord") ? entry.get("autoRecord").getAsString() : autoRecord); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        if (arguments.has("objects") || arguments.has("object") || requested.isEmpty()) { //$NON-NLS-1$ //$NON-NLS-2$
            for (String reference : stringList(arguments, "objects", "object")) { //$NON-NLS-1$ //$NON-NLS-2$
                requested.putIfAbsent(reference, autoRecord);
            }
        }
        for (java.util.Map.Entry<String, String> request : requested.entrySet()) {
            String reference = request.getKey();
            String itemAutoRecord = request.getValue();
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
                    if (itemAutoRecord != null) { // вже у складі — лише оновлюємо autoRecord
                        setAutoRecord(existing, itemAutoRecord);
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
                if (itemAutoRecord != null) {
                    setAutoRecord(item, itemAutoRecord);
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

    /** Документи-регістратори: регістр додається до/прибирається зі списку registerRecords документа. */
    @SuppressWarnings("unchecked")
    static JsonObject recorders(IBmTransaction transaction, EObject register, JsonObject arguments, boolean add) {
        JsonArray changed = new JsonArray();
        JsonArray skipped = new JsonArray();
        for (String reference : stringList(arguments, "objects", "object")) { //$NON-NLS-1$ //$NON-NLS-2$
            String full = reference.indexOf('.') > 0 ? reference : "Document." + reference; //$NON-NLS-1$
            EObject document = resolveReference(transaction, full);
            if (!(Emf.get(document, "registerRecords") instanceof List<?> raw)) { //$NON-NLS-1$
                throw new IllegalArgumentException(full + " не має списку Движения (registerRecords)"); //$NON-NLS-1$
            }
            List<EObject> records = (List<EObject>) raw;
            EObject existing = null;
            for (EObject record : records) {
                if (same(record, register)) {
                    existing = record;
                    break;
                }
            }
            if (add && existing == null) {
                records.add(register);
                changed.add(full);
            } else if (!add && existing != null) {
                records.remove(existing);
                changed.add(full);
            } else {
                skipped.add(full);
            }
        }
        JsonObject change = new JsonObject();
        change.addProperty("register", register.eClass().getName() + "." + Emf.name(register)); //$NON-NLS-1$ //$NON-NLS-2$
        change.add(add ? "added" : "removed", changed); //$NON-NLS-1$ //$NON-NLS-2$
        if (skipped.size() > 0) {
            change.add("skipped", skipped); //$NON-NLS-1$
        }
        return change;
    }

    /**
     * Субконто рахунку плану рахунків: addAccountExtDimensionType / removeAccountExtDimensionType.
     * Параметри: name = план рахунків, account = ім'я предвизначеного рахунку (чи підрахунку),
     * extDimension = ім'я виду субконто (предвизначений елемент ПВХ, пов'язаного властивістю
     * extDimensionTypes), [turnover, flags = імена ознак обліку субконто]. Ідемпотентно.
     */
    @SuppressWarnings("unchecked")
    static JsonObject accountExtDimension(IBmTransaction transaction, EObject chart, JsonObject arguments,
            boolean add) {
        if (!"ChartOfAccounts".equals(chart.eClass().getName())) { //$NON-NLS-1$
            throw new IllegalArgumentException("Операція призначена для kind=ChartOfAccounts, а не " //$NON-NLS-1$
                    + chart.eClass().getName());
        }
        String accountName = text(arguments, "account"); //$NON-NLS-1$
        String dimensionName = text(arguments, "extDimension"); //$NON-NLS-1$
        Object planRef = Emf.get(chart, "extDimensionTypes"); //$NON-NLS-1$
        if (!(planRef instanceof EObject plan)) {
            throw new IllegalStateException("У плану рахунків " + Emf.name(chart) //$NON-NLS-1$
                    + " не задано план видів характеристик для субконто. Спершу: setProperty property=extDimensionTypes"); //$NON-NLS-1$
        }
        EObject planTop = transaction.getTopObjectByFqn("ChartOfCharacteristicTypes." + Emf.name(plan)); //$NON-NLS-1$
        EObject characteristic = findPredefined(planTop == null ? plan : planTop, dimensionName);
        if (characteristic == null) {
            throw new IllegalArgumentException("Вид субконто '" + dimensionName + "' не знайдено серед предвизначених ПВХ " //$NON-NLS-1$ //$NON-NLS-2$
                    + Emf.name(plan)); //$NON-NLS-1$
        }
        EObject account = findPredefined(chart, accountName);
        if (account == null) {
            throw new IllegalArgumentException("Предвизначений рахунок '" + accountName + "' не знайдено у плані рахунків " //$NON-NLS-1$ //$NON-NLS-2$
                    + Emf.name(chart)); //$NON-NLS-1$
        }
        List<EObject> dimensions = (List<EObject>) Emf.get(account, "extDimensionTypes"); //$NON-NLS-1$
        EObject existing = null;
        for (EObject item : dimensions) {
            if (Emf.get(item, "characteristicType") instanceof EObject type && same(type, characteristic)) { //$NON-NLS-1$
                existing = item;
                break;
            }
        }
        JsonObject change = new JsonObject();
        change.addProperty("account", accountName); //$NON-NLS-1$
        change.addProperty("extDimension", dimensionName); //$NON-NLS-1$
        if (!add) {
            if (existing == null) {
                change.addProperty("alreadyAbsent", true); //$NON-NLS-1$
            } else {
                dimensions.remove(existing);
                change.addProperty("removed", true); //$NON-NLS-1$
            }
            return change;
        }
        boolean turnover = arguments.has("turnover") && arguments.get("turnover").getAsBoolean(); //$NON-NLS-1$ //$NON-NLS-2$
        if (existing == null) {
            Object max = Emf.get(chart, "maxExtDimensionCount"); //$NON-NLS-1$
            if (max instanceof Integer limit && limit.intValue() > 0 && dimensions.size() >= limit.intValue()) {
                throw new IllegalArgumentException("Ліміт субконто на рахунок вичерпано: maxExtDimensionCount=" + limit //$NON-NLS-1$
                        + ". Збільште його: setProperty property=maxExtDimensionCount."); //$NON-NLS-1$
            }
            EReference reference = (EReference) account.eClass().getEStructuralFeature("extDimensionTypes"); //$NON-NLS-1$
            existing = EcoreUtil.create(reference.getEReferenceType());
            existing.eSet(existing.eClass().getEStructuralFeature("characteristicType"), characteristic); //$NON-NLS-1$
            dimensions.add(existing);
            change.addProperty("added", true); //$NON-NLS-1$
        } else {
            change.addProperty("alreadyExists", true); //$NON-NLS-1$
        }
        EStructuralFeature turnoverFeature = existing.eClass().getEStructuralFeature("turnover"); //$NON-NLS-1$
        if (turnoverFeature != null && arguments.has("turnover")) { //$NON-NLS-1$
            existing.eSet(turnoverFeature, Boolean.valueOf(turnover));
            change.addProperty("turnover", turnover); //$NON-NLS-1$
        }
        if (arguments.has("flags") && arguments.get("flags").isJsonArray()) { //$NON-NLS-1$ //$NON-NLS-2$
            List<EObject> chartFlags = (List<EObject>) Emf.get(chart, "extDimensionAccountingFlags"); //$NON-NLS-1$
            List<EObject> itemFlags = (List<EObject>) Emf.get(existing, "extDimensionAccountingFlags"); //$NON-NLS-1$
            JsonArray applied = new JsonArray();
            for (JsonElement flagName : arguments.getAsJsonArray("flags")) { //$NON-NLS-1$
                EObject flag = null;
                for (EObject candidate : chartFlags) {
                    if (flagName.getAsString().equalsIgnoreCase(Emf.name(candidate))) {
                        flag = candidate;
                        break;
                    }
                }
                if (flag == null) {
                    throw new IllegalArgumentException("Ознаку обліку субконто не знайдено: " + flagName.getAsString()); //$NON-NLS-1$
                }
                if (!itemFlags.contains(flag)) {
                    itemFlags.add(flag);
                }
                applied.add(Emf.name(flag));
            }
            change.add("flags", applied); //$NON-NLS-1$
        }
        return change;
    }

    /** Предвизначений елемент за іменем (рекурсивно по childItems) у ПВХ/плані рахунків. */
    @SuppressWarnings("unchecked")
    private static EObject findPredefined(EObject owner, String name) {
        Object predefined = Emf.get(owner, "predefined"); //$NON-NLS-1$
        if (!(predefined instanceof EObject container) || !(Emf.get(container, "items") instanceof List<?> items)) { //$NON-NLS-1$
            return null;
        }
        java.util.ArrayDeque<EObject> queue = new java.util.ArrayDeque<>();
        for (Object item : items) {
            queue.add((EObject) item);
        }
        while (!queue.isEmpty()) {
            EObject item = queue.poll();
            if (name.equalsIgnoreCase(Emf.name(item))) {
                return item;
            }
            if (Emf.get(item, "childItems") instanceof List<?> children) { //$NON-NLS-1$
                for (Object child : children) {
                    queue.add((EObject) child);
                }
            }
        }
        return null;
    }

    private static String text(JsonObject arguments, String name) {
        if (!arguments.has(name) || arguments.get(name).isJsonNull() || arguments.get(name).getAsString().isBlank()) {
            throw new IllegalArgumentException("Обов'язковий параметр відсутній: " + name); //$NON-NLS-1$
        }
        return arguments.get(name).getAsString();
    }
}
