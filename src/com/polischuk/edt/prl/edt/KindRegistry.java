/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.edt;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Нормалізація видів метаданих: рос/укр/англ псевдоніми → англійська назва EClass,
 * як у моделі mdclass EDT (Catalog, Document, InformationRegister…).
 */
public final class KindRegistry {

    private static final Map<String, String> ALIASES = new HashMap<>();

    private KindRegistry() {
    }

    static {
        alias("Catalog", "справочник", "справочники", "довідник", "довідники", "catalogs"); //$NON-NLS-1$
        alias("Document", "документ", "документы", "документи", "documents"); //$NON-NLS-1$
        alias("Enum", "перечисление", "перечисления", "перелік", "переліки", "enums"); //$NON-NLS-1$
        alias("Constant", "константа", "константы", "константи", "constants"); //$NON-NLS-1$
        alias("Report", "отчет", "отчеты", "звіт", "звіти", "reports"); //$NON-NLS-1$
        alias("DataProcessor", "обработка", "обработки", "обробка", "обробки", "dataProcessors"); //$NON-NLS-1$
        alias("CommonModule", "общиймодуль", "общиемодули", "загальниймодуль", "commonModules"); //$NON-NLS-1$
        alias("InformationRegister", "регистрсведений", "регистрысведений", "регістрвідомостей", //$NON-NLS-1$
                "informationRegisters"); //$NON-NLS-1$
        alias("AccumulationRegister", "регистрнакопления", "регистрынакопления", "регістрнакопичення", //$NON-NLS-1$
                "accumulationRegisters"); //$NON-NLS-1$
        alias("AccountingRegister", "регистрбухгалтерии", "регістрбухгалтерії", "accountingRegisters"); //$NON-NLS-1$
        alias("CalculationRegister", "регистррасчета", "регістррозрахунку", "calculationRegisters"); //$NON-NLS-1$
        alias("ExchangePlan", "планобмена", "планыобмена", "планобміну", "exchangePlans"); //$NON-NLS-1$
        alias("ChartOfCharacteristicTypes", "планвидовхарактеристик", "планвидівхарактеристик", //$NON-NLS-1$
                "chartsOfCharacteristicTypes"); //$NON-NLS-1$
        alias("ChartOfAccounts", "плансчетов", "планрахунків", "chartsOfAccounts"); //$NON-NLS-1$
        alias("ChartOfCalculationTypes", "планвидоврасчета", "планвидіврозрахунку", "chartsOfCalculationTypes"); //$NON-NLS-1$
        alias("BusinessProcess", "бизнеспроцесс", "бизнеспроцессы", "бізнеспроцес", "businessProcesses"); //$NON-NLS-1$
        alias("Task", "задача", "задачи", "завдання", "tasks"); //$NON-NLS-1$
        alias("DocumentJournal", "журналдокументов", "журналдокументів", "documentJournals"); //$NON-NLS-1$
        alias("Subsystem", "подсистема", "подсистемы", "підсистема", "підсистеми", "subsystems"); //$NON-NLS-1$
        alias("Role", "роль", "роли", "ролі", "roles"); //$NON-NLS-1$
        alias("CommonForm", "общаяформа", "общиеформы", "загальнаформа", "commonForms"); //$NON-NLS-1$
        alias("CommonCommand", "общаякоманда", "загальнакоманда", "commonCommands"); //$NON-NLS-1$
        alias("CommonAttribute", "общийреквизит", "загальнийреквізит", "commonAttributes"); //$NON-NLS-1$
        alias("CommonTemplate", "общиймакет", "загальниймакет", "commonTemplates"); //$NON-NLS-1$
        alias("CommonPicture", "общаякартинка", "загальнакартинка", "commonPictures"); //$NON-NLS-1$
        alias("SessionParameter", "параметрсеанса", "параметрсеансу", "sessionParameters"); //$NON-NLS-1$
        alias("FunctionalOption", "функциональнаяопция", "функціональнаопція", "functionalOptions"); //$NON-NLS-1$
        alias("DefinedType", "определяемыйтип", "визначенийтип", "definedTypes"); //$NON-NLS-1$
        alias("EventSubscription", "подписканасобытие", "підписканаподію", "eventSubscriptions"); //$NON-NLS-1$
        alias("ScheduledJob", "регламентноезадание", "регламентнезавдання", "scheduledJobs"); //$NON-NLS-1$
        alias("WebService", "вебсервис", "вебсервіс", "webServices"); //$NON-NLS-1$
        alias("HTTPService", "httpсервис", "httpсервіс", "httpServices"); //$NON-NLS-1$
        alias("Sequence", "последовательность", "послідовність", "sequences"); //$NON-NLS-1$
        alias("DocumentNumerator", "нумератор", "documentNumerators"); //$NON-NLS-1$
        alias("SettingsStorage", "хранилищенастроек", "сховищеналаштувань", "settingsStorages"); //$NON-NLS-1$
        alias("Language", "язык", "языки", "мова", "мови", "languages"); //$NON-NLS-1$
        alias("StyleItem", "элементстиля", "елементстилю", "styleItems"); //$NON-NLS-1$
        alias("FilterCriterion", "критерийотбора", "критерійвідбору", "filterCriteria"); //$NON-NLS-1$
        alias("ExternalDataSource", "внешнийисточникданных", "зовнішнєджерелоданих", "externalDataSources"); //$NON-NLS-1$
    }

    private static void alias(String kind, String... aliases) {
        ALIASES.put(normalize(kind), kind);
        for (String a : aliases) {
            ALIASES.put(normalize(a), kind);
        }
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replace(" ", "").replace(".", "").replace("ё", "е"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
    }

    /** Канонічний вид (назва EClass) або вхідне значення, якщо псевдонім невідомий. */
    public static String canonical(String kind) {
        if (kind == null) {
            return null;
        }
        return ALIASES.getOrDefault(normalize(kind), kind);
    }
}
