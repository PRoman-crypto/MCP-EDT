/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.edt;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.ui.resource.IResourceSetProvider;

import com._1c.g5.v8.derived.IDerivedDataManager;
import com._1c.g5.v8.dt.bm.xtext.resource.BmAwareSynchronizedXtextResourceSet;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;

/**
 * ResourceSet проєкту для Xtext-ресурсів EDT (BSL, QL).
 *
 * <p>Основний шлях — власний BM-aware набір EDT
 * ({@code BmAwareSynchronizedXtextResourceSet}): він прив'язаний до BM-моделі
 * проєкту, тому скоупінг бачить метадані конфігурації. Працює headless і не
 * залежить від активації UI-бандлів мови.
 *
 * <p>Запасний шлях — Xtext-UI-сервіс {@code IResourceSetProvider} з інжектора мови.
 * Він доступний лише коли UI-бандл мови вже піднятий: до того
 * {@code IResourceServiceProvider.get(IResourceSetProvider.class)} повертає null,
 * і саме на цьому раніше падала семантична перевірка запитів (див.
 * docs/research/rsv-parity-2026-09-03.md, дефекти 1–2).
 */
public final class EdtResourceSets {

    private EdtResourceSets() {
    }

    /**
     * ResourceSet, прив'язаний до BM-моделі проєкту.
     *
     * @param project проєкт workspace
     * @param provider провайдер сервісів мови Xtext для запасного шляху (може бути null)
     * @return набір ресурсів; null — якщо не вдалося створити жодним шляхом
     */
    public static ResourceSet forProject(IProject project, IResourceServiceProvider provider) {
        ResourceSet bmAware = bmAware(project);
        if (bmAware != null) {
            return bmAware;
        }
        return fromLanguageProvider(project, provider);
    }

    /**
     * ResourceSet мови Xtext (UI-сервіс) з відкатом на BM-aware.
     * Порядок зворотний до {@link #forProject}: для BSL штатний шлях уже
     * відпрацьований і перевірений, BM-aware потрібен лише як страховка.
     */
    public static ResourceSet forProjectPreferLanguage(IProject project, IResourceServiceProvider provider) {
        ResourceSet fromLanguage = fromLanguageProvider(project, provider);
        if (fromLanguage != null) {
            return fromLanguage;
        }
        return bmAware(project);
    }

    /** Чекає синхронізації BM-моделі проєкту: без цього скоуп може бути ще порожній. */
    public static boolean waitModelSynchronization(IProject project) {
        try {
            IBmModelManager modelManager = EdtServices.get(IBmModelManager.class);
            if (modelManager == null) {
                return false;
            }
            modelManager.waitModelSynchronization(project);
            return true;
        } catch (Throwable e) { // модель ще не піднята / API дрейфнув
            return false;
        }
    }

    /**
     * Чекає обчислення derived data проєкту (серед них — модель таблиць БД, на якій
     * тримається скоуп мови запитів). Поки вона не порахована, кожна таблиця запиту
     * «не знайдена» — на свіжозапущеній EDT це давало хибні помилки на великій
     * конфігурації.
     *
     * @return true — усе обчислено (результату скоупа можна довіряти)
     */
    public static boolean waitDerivedData(IProject project, long timeoutMs) {
        try {
            IDerivedDataManagerProvider provider = EdtServices.get(IDerivedDataManagerProvider.class);
            if (provider == null) {
                return false;
            }
            IDerivedDataManager manager = provider.get(project);
            if (manager == null) {
                return false;
            }
            if (manager.isAllComputed()) {
                return true;
            }
            // true — дочекались; інакше ще раз питаємо загальний стан: важливі
            // сегменти могли дорахуватись, поки ми чекали
            return manager.waitImportantDataComputations(timeoutMs) || manager.isAllComputed();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Throwable e) { // сервіс недоступний / API дрейфнув
            return false;
        }
    }

    /** Лише BM-aware набір EDT; null — якщо сервіси EDT недоступні. */
    public static ResourceSet bmAware(IProject project) {
        try {
            IBmModelManager modelManager = EdtServices.get(IBmModelManager.class);
            IDtProjectManager dtProjectManager = EdtServices.get(IDtProjectManager.class);
            if (modelManager == null || dtProjectManager == null) {
                return null;
            }
            IDtProject dtProject = dtProjectManager.getDtProject(project);
            if (dtProject == null) {
                return null;
            }
            return new BmAwareSynchronizedXtextResourceSet(dtProject, modelManager);
        } catch (Throwable e) { // LinkageError при дрейфі API EDT — теж сюди
            return null;
        }
    }

    /** Лише набір з інжектора мови (UI-сервіс Xtext); null — якщо сервіс ще не піднятий. */
    public static ResourceSet fromLanguageProvider(IProject project, IResourceServiceProvider provider) {
        if (provider == null) {
            return null;
        }
        try {
            IResourceSetProvider resourceSetProvider = provider.get(IResourceSetProvider.class);
            return resourceSetProvider == null ? null : resourceSetProvider.get(project);
        } catch (Throwable e) {
            return null;
        }
    }
}
