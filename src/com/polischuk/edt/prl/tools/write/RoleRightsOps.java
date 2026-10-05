/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.write;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.eclipse.emf.ecore.EObject;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.polischuk.edt.prl.edt.EdtServices;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.metadata.mdclass.Role;
import com._1c.g5.v8.dt.rights.IRightInfosService;
import com._1c.g5.v8.dt.rights.model.ObjectRight;
import com._1c.g5.v8.dt.rights.model.ObjectRights;
import com._1c.g5.v8.dt.rights.model.Right;
import com._1c.g5.v8.dt.rights.model.RightValue;
import com._1c.g5.v8.dt.rights.model.RoleDescription;
import com._1c.g5.v8.dt.rights.model.util.RightsModelUtil;

/**
 * Права ролі через модель EDT (RoleDescription / ObjectRights / ObjectRight) у відкритій
 * BM-транзакції — ті самі утиліти RightsModelUtil, що й у штатних задачах редактора прав.
 * Rls (обмеження доступу до даних) цією операцією не змінюються.
 */
final class RoleRightsOps {

    private RoleRightsOps() {
    }

    /**
     * setRoleRights: name = роль; objects/object = "Вид.Ім'я"; rights = імена прав
     * (англ. або рос.: Read/Чтение); grant (true за замовч.) — надати чи забрати.
     * Також необов'язкові прапорці ролі setForNewObjects / setForAttributesByDefault.
     */
    static JsonObject setRoleRights(IBmTransaction transaction, EObject roleObject, JsonObject arguments) {
        if (!(roleObject instanceof Role role)) {
            throw new IllegalArgumentException("Операція призначена для kind=Role, а не " //$NON-NLS-1$
                    + roleObject.eClass().getName());
        }
        RoleDescription description = ensureDescription(transaction, role);
        JsonObject change = new JsonObject();
        change.addProperty("role", role.getName()); //$NON-NLS-1$

        if (arguments.has("setForNewObjects") && !arguments.get("setForNewObjects").isJsonNull()) { //$NON-NLS-1$ //$NON-NLS-2$
            boolean value = arguments.get("setForNewObjects").getAsBoolean(); //$NON-NLS-1$
            change.addProperty("setForNewObjects", description.isSetForNewObjects() + " -> " + value); //$NON-NLS-1$ //$NON-NLS-2$
            description.setSetForNewObjects(value);
        }
        if (arguments.has("setForAttributesByDefault") //$NON-NLS-1$
                && !arguments.get("setForAttributesByDefault").isJsonNull()) { //$NON-NLS-1$
            boolean value = arguments.get("setForAttributesByDefault").getAsBoolean(); //$NON-NLS-1$
            change.addProperty("setForAttributesByDefault", //$NON-NLS-1$
                    description.isSetForAttributesByDefault() + " -> " + value); //$NON-NLS-1$
            description.setSetForAttributesByDefault(value);
        }

        boolean hasObjects = arguments.has("objects") || arguments.has("object"); //$NON-NLS-1$ //$NON-NLS-2$
        if (!hasObjects) {
            if (!change.has("setForNewObjects") && !change.has("setForAttributesByDefault")) { //$NON-NLS-1$ //$NON-NLS-2$
                throw new IllegalArgumentException(
                        "setRoleRights: потрібні objects + rights (або прапорці setForNewObjects/setForAttributesByDefault)"); //$NON-NLS-1$
            }
            return change;
        }
        List<String> rightNames = StructureOps.stringList(arguments, "rights", "right"); //$NON-NLS-1$ //$NON-NLS-2$
        boolean grant = !arguments.has("grant") || arguments.get("grant").getAsBoolean(); //$NON-NLS-1$ //$NON-NLS-2$
        RightValue value = RightsModelUtil.getRightValue(grant);
        IRightInfosService rightInfos = rightInfosService();

        JsonArray results = new JsonArray();
        for (String reference : StructureOps.stringList(arguments, "objects", "object")) { //$NON-NLS-1$ //$NON-NLS-2$
            EObject target = StructureOps.resolveReference(transaction, reference);
            Set<Right> available = rightInfos.getRights(target);
            ObjectRights objectRights = RightsModelUtil.getOrCreateObjectRights(target, description);
            RightValue defaultValue = RightsModelUtil.getDefaultRightValue(target, role);
            List<String> applied = new ArrayList<>();
            for (String rightName : rightNames) {
                Right right = findRight(available, rightName);
                if (right == null) {
                    throw new IllegalArgumentException("Право '" + rightName + "' недоступне для " + reference //$NON-NLS-1$ //$NON-NLS-2$
                            + ". Доступні: " + describe(available)); //$NON-NLS-1$
                }
                RightsModelUtil.changeObjectRight(value, defaultValue, objectRights, right);
                applied.add(right.getName());
            }
            RightsModelUtil.removeEmptyObjectRights(description, objectRights);
            JsonObject entry = new JsonObject();
            entry.addProperty("object", reference); //$NON-NLS-1$
            entry.addProperty(grant ? "granted" : "revoked", String.join(", ", applied)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            JsonArray effective = new JsonArray();
            for (ObjectRight objectRight : objectRights.getRights()) {
                if (objectRight.getValue() == RightValue.SET && objectRight.getRight() != null) {
                    effective.add(objectRight.getRight().getName());
                }
            }
            entry.add("explicitRights", effective); //$NON-NLS-1$
            results.add(entry);
        }
        change.add("objects", results); //$NON-NLS-1$
        return change;
    }

    /**
     * Опис прав (Rights.rights) нової ролі в EDT створюється при першому відкритті редактора;
     * тут створюємо його одразу як окремий top-об'єкт Role.Name.Rights.
     */
    static RoleDescription ensureDescription(IBmTransaction transaction, Role role) {
        if (role.getRights() instanceof RoleDescription existing) {
            return existing;
        }
        if (role.getRights() != null) {
            throw new IllegalStateException("Опис прав ролі " + role.getName() + " має невідомий тип"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        RoleDescription description = com._1c.g5.v8.dt.rights.model.RightsFactory.eINSTANCE.createRoleDescription();
        if (description instanceof com._1c.g5.v8.bm.core.IBmObject bmDescription) {
            transaction.attachTopObject(bmDescription, "Role." + role.getName() + ".Rights"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        role.setRights(description);
        return description;
    }

    private static Right findRight(Set<Right> available, String name) {
        for (Right right : available) {
            if (name.equalsIgnoreCase(right.getName()) || name.equalsIgnoreCase(right.getNameRu())) {
                return right;
            }
        }
        return null;
    }

    private static String describe(Set<Right> available) {
        List<String> names = new ArrayList<>();
        for (Right right : available) {
            names.add(right.getName() + "/" + right.getNameRu()); //$NON-NLS-1$
        }
        return String.join(", ", names); //$NON-NLS-1$
    }

    /** IRightInfosService: сервіс OSGi, а якщо не зареєстрований — із injector-а бандла rights. */
    private static IRightInfosService rightInfosService() {
        IRightInfosService service = EdtServices.get(IRightInfosService.class);
        if (service != null) {
            return service;
        }
        com._1c.g5.v8.dt.rights.RightsPlugin plugin = com._1c.g5.v8.dt.rights.RightsPlugin.getDefault();
        if (plugin == null) {
            throw new IllegalStateException("Бандл com._1c.g5.v8.dt.rights не активний"); //$NON-NLS-1$
        }
        return plugin.getInjector().getInstance(IRightInfosService.class);
    }
}
