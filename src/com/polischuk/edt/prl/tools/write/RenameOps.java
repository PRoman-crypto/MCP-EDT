/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.write;

import java.util.Collection;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.ecore.EObject;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.polischuk.edt.prl.edt.EdtServices;
import com.polischuk.edt.prl.edt.Emf;
import com.polischuk.edt.prl.edt.KindRegistry;
import com.polischuk.edt.prl.edt.V8Access;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.md.refactoring.core.IMdRefactoringService;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.refactoring.core.CleanReferenceProblem;
import com._1c.g5.v8.dt.refactoring.core.IRefactoring;
import com._1c.g5.v8.dt.refactoring.core.IRefactoringItem;
import com._1c.g5.v8.dt.refactoring.core.IRefactoringProblem;

/**
 * Перейменування об'єкта метаданих (або його реквізита/ТЧ) штатним refactoring-сервісом EDT
 * {@link IMdRefactoringService} (OSGi-сервіс бандла com._1c.g5.v8.dt.md.refactoring) —
 * З ОНОВЛЕННЯМ ПОСИЛАНЬ: тими самими контрибуторами extension-point
 * com._1c.g5.v8.dt.refactoring.core.refactoringContributors, що й UI-рефакторинг Rename в EDT
 * (BSL-код — BslConfigurationObjectRenameContributor; data path форм —
 * FormDataPathRenameRefactoringContributor; загальні модулі, права, похідні поля запитів тощо).
 *
 * ВАЖЛИВО: сервіс сам відкриває BM-транзакції (batch session + блокування derived-data pipeline),
 * тому renameObject НЕ МОЖНА викликати зсередини вже відкритої AbstractBmTask EditMetadataTool —
 * викликається до створення таска (як adoptObject). Ланцюжок headless: initiateRename будує план
 * (BmObjectVisitor обходить зворотні посилання BM-індексу), perform() застосовує без жодного UI.
 *
 * dryRun: план рефакторингу будується повністю (initiateRename), але perform() не викликається —
 * у відповіді список запланованих змін (items) і проблем (problems).
 */
public final class RenameOps {

    private RenameOps() {
    }

    /**
     * Перейменування top-об'єкта метаданих або дочірнього елемента (реквізит/ТЧ).
     * Параметри arguments: project (необов'язковий), kind + name (об'єкт), newName,
     * необов'язково tabularSection/attribute (перейменувати дочірній елемент),
     * dryRun, force (виконати попри знайдені проблеми, напр. об'єкти «на замку»).
     */
    public static JsonObject renameObject(JsonObject arguments) {
        String projectName = optional(arguments, "project"); //$NON-NLS-1$
        String kind = required(arguments, "kind"); //$NON-NLS-1$
        String name = required(arguments, "name"); //$NON-NLS-1$
        String newName = required(arguments, "newName"); //$NON-NLS-1$
        String section = optional(arguments, "tabularSection"); //$NON-NLS-1$
        String attribute = optional(arguments, "attribute"); //$NON-NLS-1$
        boolean dryRun = arguments.has("dryRun") && arguments.get("dryRun").getAsBoolean(); //$NON-NLS-1$ //$NON-NLS-2$
        boolean force = arguments.has("force") && arguments.get("force").getAsBoolean(); //$NON-NLS-1$ //$NON-NLS-2$

        checkIdentifier(newName);

        IProject project = V8Access.resolveEclipseProject(projectName);
        IBmModel model = EdtServices.require(IBmModelManager.class).getModel(project);
        String canonicalKind = KindRegistry.canonical(kind);
        String fqn = canonicalKind + "." + name; //$NON-NLS-1$

        // читаємо ціль readonly-таском: BM-хендл лишається валідним після завершення таска —
        // так робить сам EDT (MdRefactoringService.getAdoptedCounterpart)
        MdObject target = model.executeReadonlyTask(
                new AbstractBmTask<>("MCP:PRL renameObject lookup") { //$NON-NLS-1$
                    @Override
                    public MdObject execute(IBmTransaction transaction, IProgressMonitor monitor) {
                        return resolveTarget(transaction, canonicalKind, fqn, section, attribute, newName);
                    }
                });

        String targetLabel = target.eClass().getName() + "." + Emf.name(target); //$NON-NLS-1$

        IMdRefactoringService service = EdtServices.require(IMdRefactoringService.class);
        // повертає рефакторинг основного проєкту + окремі для заимствованных двійників у розширеннях
        Collection<IRefactoring> refactorings = service.createMdObjectRenameRefactoring(target, newName);

        JsonArray plan = new JsonArray();
        JsonArray problems = new JsonArray();
        for (IRefactoring refactoring : refactorings) {
            plan.add(describeRefactoring(refactoring));
            for (IRefactoringProblem problem : refactoring.getStatus().getProblems()) {
                problems.add(describeProblem(problem));
            }
        }

        JsonObject result = new JsonObject();
        result.addProperty("operation", "renameObject"); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("object", targetLabel); //$NON-NLS-1$
        result.addProperty("newName", newName); //$NON-NLS-1$
        result.addProperty("dryRun", dryRun); //$NON-NLS-1$
        result.add("plan", plan); //$NON-NLS-1$
        if (problems.size() > 0) {
            result.add("problems", problems); //$NON-NLS-1$
        }

        if (dryRun) {
            result.addProperty("applied", false); //$NON-NLS-1$
            result.addProperty("note", //$NON-NLS-1$
                    "План рефакторингу побудовано (без застосування). Повторіть без dryRun для виконання."); //$NON-NLS-1$
            return result;
        }
        if (problems.size() > 0 && !force) {
            result.addProperty("applied", false); //$NON-NLS-1$
            result.addProperty("note", //$NON-NLS-1$
                    "Знайдено проблеми (див. problems) — рефакторинг НЕ виконано. " //$NON-NLS-1$
                    + "Повторіть із force:true, щоб виконати попри проблеми (як «Продовжити» в UI EDT)."); //$NON-NLS-1$
            return result;
        }

        for (IRefactoring refactoring : refactorings) {
            try {
                refactoring.perform();
            } catch (RuntimeException e) {
                throw new IllegalStateException("Помилка виконання рефакторингу '" //$NON-NLS-1$
                        + refactoring.getTitle() + "': " + e.getMessage(), e); //$NON-NLS-1$
            }
        }
        result.addProperty("applied", true); //$NON-NLS-1$
        result.addProperty("note", //$NON-NLS-1$
                "Перейменовано з оновленням посилань (BSL-код, форми, права, запити СКД/dbview) — " //$NON-NLS-1$
                + "штатними контрибуторами EDT; серіалізація у файли відбувається автоматично. " //$NON-NLS-1$
                + "Рядкові літерали (тексти запитів у коді, програмні звернення за іменем) можуть " //$NON-NLS-1$
                + "лишитися старими — перевірте code_search і get_validation_errors."); //$NON-NLS-1$
        return result;
    }

    /** Ціль перейменування: top-об'єкт або дочірній елемент (ТЧ і/або реквізит) + перевірка колізій імен. */
    private static MdObject resolveTarget(IBmTransaction transaction, String canonicalKind,
            String fqn, String section, String attribute, String newName) {
        IBmObject top = transaction.getTopObjectByFqn(fqn);
        if (top == null) {
            throw new IllegalArgumentException("Об'єкт не знайдено за FQN: " + fqn); //$NON-NLS-1$
        }
        EObject object = top;
        if (section != null) {
            object = findByName(object, "tabularSections", section); //$NON-NLS-1$
        }
        if (attribute != null) {
            object = findByName(object, "attributes", attribute); //$NON-NLS-1$
        }
        if (object == top) {
            // top-об'єкт: новий FQN не має бути зайнятий
            String newFqn = canonicalKind + "." + newName; //$NON-NLS-1$
            if (!newFqn.equalsIgnoreCase(fqn) && transaction.getTopObjectByFqn(newFqn) != null) {
                throw new IllegalArgumentException("Об'єкт уже існує: " + newFqn); //$NON-NLS-1$
            }
        } else if (object.eContainer() != null) {
            // дочірній: ім'я не має збігатися з сусідом у тій самій колекції
            for (EObject sibling : object.eContainer().eContents()) {
                if (sibling != object && sibling.eClass() == object.eClass()
                        && newName.equalsIgnoreCase(Emf.name(sibling))) {
                    throw new IllegalArgumentException("Елемент уже існує: " + newName //$NON-NLS-1$
                            + " у " + Emf.name(object.eContainer())); //$NON-NLS-1$
                }
            }
        }
        if (!(object instanceof MdObject mdObject)) {
            throw new IllegalArgumentException("Елемент " + object.eClass().getName() //$NON-NLS-1$
                    + " не є об'єктом метаданих (MdObject) — рефакторинг незастосовний"); //$NON-NLS-1$
        }
        return mdObject;
    }

    private static EObject findByName(EObject owner, String collection, String childName) {
        Object value = Emf.get(owner, collection);
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof EObject child && childName.equalsIgnoreCase(Emf.name(child))) {
                    return child;
                }
            }
        }
        throw new IllegalArgumentException("Елемент не знайдено: " + childName + " у " + collection //$NON-NLS-1$ //$NON-NLS-2$
                + " об'єкта " + Emf.name(owner)); //$NON-NLS-1$
    }

    /** План одного рефакторингу: заголовок і перелік запланованих змін (items). */
    private static JsonObject describeRefactoring(IRefactoring refactoring) {
        JsonObject entry = new JsonObject();
        entry.addProperty("title", refactoring.getTitle()); //$NON-NLS-1$
        JsonArray items = new JsonArray();
        for (IRefactoringItem item : refactoring.getItems()) {
            items.add(item.getName());
        }
        entry.add("items", items); //$NON-NLS-1$
        return entry;
    }

    /** Опис проблеми рефакторингу: тип + об'єкт (напр. «на замку» постачальника). */
    private static JsonObject describeProblem(IRefactoringProblem problem) {
        JsonObject entry = new JsonObject();
        entry.addProperty("type", problem.getClass().getSimpleName()); //$NON-NLS-1$
        entry.addProperty("object", describeObject(problem.getObject())); //$NON-NLS-1$
        if (problem instanceof CleanReferenceProblem cleanReference) {
            entry.addProperty("referencedBy", describeObject(cleanReference.getReferencingObject())); //$NON-NLS-1$
            if (cleanReference.getReference() != null) {
                entry.addProperty("reference", cleanReference.getReference().getName()); //$NON-NLS-1$
            }
        }
        return entry;
    }

    private static String describeObject(EObject object) {
        if (object == null) {
            return null;
        }
        if (object instanceof IBmObject bmObject && bmObject.bmIsTop()) {
            return bmObject.bmGetFqn();
        }
        String name = Emf.name(object);
        return object.eClass().getName() + (name != null ? "." + name : ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Легка перевірка імені: літери/цифри/підкреслення, не починається з цифри. */
    private static void checkIdentifier(String newName) {
        if (!newName.matches("[\\p{L}_][\\p{L}\\p{Nd}_]*")) { //$NON-NLS-1$
            throw new IllegalArgumentException("Неприпустиме ім'я '" + newName //$NON-NLS-1$
                    + "': дозволені літери, цифри та підкреслення; перший символ — не цифра."); //$NON-NLS-1$
        }
    }

    private static String required(JsonObject arguments, String name) {
        if (!arguments.has(name) || arguments.get(name).getAsString().isBlank()) {
            throw new IllegalArgumentException("Обов'язковий параметр відсутній: " + name); //$NON-NLS-1$
        }
        return arguments.get(name).getAsString();
    }

    private static String optional(JsonObject arguments, String name) {
        return arguments.has(name) && !arguments.get(name).isJsonNull()
                && !arguments.get(name).getAsString().isBlank() ? arguments.get(name).getAsString() : null;
    }
}
