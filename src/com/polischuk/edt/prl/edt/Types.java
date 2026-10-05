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
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.mcore.DateFractions;
import com._1c.g5.v8.dt.mcore.DateQualifiers;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.NumberQualifiers;
import com._1c.g5.v8.dt.mcore.StringQualifiers;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.version.IRuntimeVersionSupport;
import com._1c.g5.v8.dt.platform.version.Version;

/**
 * Побудова TypeDescription за іменами типів 1С. Російські/українські назви
 * мапляться на англійські платформені ("Строка" → String, "СправочникСсылка.X" → CatalogRef.X);
 * TypeItem створюється проксі через IEObjectProvider для версії платформи проєкту.
 */
public final class Types {

    private static final Map<String, String> SIMPLE = new HashMap<>();
    private static final Map<String, String> REF_PREFIX = new HashMap<>();

    /** Метадані, на які посилаються ссылочные типи, беремо з транзакції, у якій виконується операція. */
    private static final ThreadLocal<IBmTransaction> CURRENT_TRANSACTION = new ThreadLocal<>();

    private static final Map<String, String> REF_KIND = new HashMap<>();

    static {
        REF_KIND.put("CatalogRef", "Catalog"); //$NON-NLS-1$ //$NON-NLS-2$
        REF_KIND.put("DocumentRef", "Document"); //$NON-NLS-1$ //$NON-NLS-2$
        REF_KIND.put("EnumRef", "Enum"); //$NON-NLS-1$ //$NON-NLS-2$
        REF_KIND.put("ChartOfCharacteristicTypesRef", "ChartOfCharacteristicTypes"); //$NON-NLS-1$ //$NON-NLS-2$
        REF_KIND.put("ChartOfAccountsRef", "ChartOfAccounts"); //$NON-NLS-1$ //$NON-NLS-2$
        REF_KIND.put("ChartOfCalculationTypesRef", "ChartOfCalculationTypes"); //$NON-NLS-1$ //$NON-NLS-2$
        REF_KIND.put("ExchangePlanRef", "ExchangePlan"); //$NON-NLS-1$ //$NON-NLS-2$
        REF_KIND.put("BusinessProcessRef", "BusinessProcess"); //$NON-NLS-1$ //$NON-NLS-2$
        REF_KIND.put("TaskRef", "Task"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private Types() {
    }

    /** Задає транзакцію для резолву ссылочных типів (скидається викликом з null). */
    public static void setTransaction(IBmTransaction transaction) {
        if (transaction == null) {
            CURRENT_TRANSACTION.remove();
        } else {
            CURRENT_TRANSACTION.set(transaction);
        }
    }

    /** "CatalogRef.X" → TypeItem зі згенерованих типів (producedTypes.refType) метаданих X. */
    private static TypeItem producedRefType(String platformName) {
        IBmTransaction transaction = CURRENT_TRANSACTION.get();
        int dot = platformName.indexOf('.');
        if (transaction == null || dot <= 0) {
            return null;
        }
        String kind = REF_KIND.get(platformName.substring(0, dot));
        if (kind == null) {
            return null;
        }
        EObject owner = transaction.getTopObjectByFqn(kind + "." + platformName.substring(dot + 1)); //$NON-NLS-1$
        if (owner == null) {
            throw new IllegalArgumentException("Тип " + platformName + ": об'єкт " + kind + "." //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + platformName.substring(dot + 1) + " не знайдено у конфігурації"); //$NON-NLS-1$
        }
        Object produced = Emf.get(owner, "producedTypes"); //$NON-NLS-1$
        Object refType = produced instanceof EObject types ? Emf.get(types, "refType") : null; //$NON-NLS-1$
        return refType instanceof TypeItem item ? item : null;
    }

    static {
        simple("String", "строка", "рядок"); //$NON-NLS-1$
        simple("Number", "число"); //$NON-NLS-1$
        simple("Boolean", "булево"); //$NON-NLS-1$
        simple("Date", "дата"); //$NON-NLS-1$
        simple("UUID", "уникальныйидентификатор", "унікальнийідентифікатор"); //$NON-NLS-1$
        simple("ValueStorage", "хранилищезначения", "сховищезначення"); //$NON-NLS-1$
        simple("AnyRef", "любаяссылка", "будьяке посилання", "будь-якепосилання"); //$NON-NLS-1$
        simple("BinaryData", "двоичныеданные", "двійковідані"); //$NON-NLS-1$
        simple("CatalogRef", "справочникссылка", "довідникпосилання"); //$NON-NLS-1$
        simple("DocumentRef", "документссылка", "документпосилання"); //$NON-NLS-1$
        simple("EnumRef", "перечислениессылка", "перелікпосилання"); //$NON-NLS-1$
        simple("ChartOfCharacteristicTypesRef", "планвидовхарактеристикссылка"); //$NON-NLS-1$
        simple("ChartOfAccountsRef", "плансчетовссылка"); //$NON-NLS-1$
        simple("ChartOfCalculationTypesRef", "планвидоврасчетассылка"); //$NON-NLS-1$
        simple("ExchangePlanRef", "планобменассылка"); //$NON-NLS-1$
        simple("BusinessProcessRef", "бизнеспроцессссылка"); //$NON-NLS-1$
        simple("TaskRef", "задачассылка"); //$NON-NLS-1$
        ref("CatalogRef", "справочникссылка", "довідникпосилання"); //$NON-NLS-1$
        ref("DocumentRef", "документссылка", "документпосилання"); //$NON-NLS-1$
        ref("EnumRef", "перечислениессылка", "перелікпосилання"); //$NON-NLS-1$
        ref("ChartOfCharacteristicTypesRef", "планвидовхарактеристикссылка"); //$NON-NLS-1$
        ref("ChartOfAccountsRef", "плансчетовссылка"); //$NON-NLS-1$
        ref("ChartOfCalculationTypesRef", "планвидоврасчетассылка"); //$NON-NLS-1$
        ref("ExchangePlanRef", "планобменассылка"); //$NON-NLS-1$
        ref("BusinessProcessRef", "бизнеспроцессссылка"); //$NON-NLS-1$
        ref("TaskRef", "задачассылка"); //$NON-NLS-1$
        ref("DefinedType", "определяемыйтип", "визначенийтип"); //$NON-NLS-1$
        ref("Characteristic", "характеристика"); //$NON-NLS-1$
    }

    private static void simple(String platform, String... aliases) {
        SIMPLE.put(normalize(platform), platform);
        for (String a : aliases) {
            SIMPLE.put(normalize(a), platform);
        }
    }

    private static void ref(String platform, String... aliases) {
        REF_PREFIX.put(normalize(platform), platform);
        for (String a : aliases) {
            REF_PREFIX.put(normalize(a), platform);
        }
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replace(" ", "").replace("ё", "е"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /** "СправочникСсылка.Номенклатура" → "CatalogRef.Номенклатура"; "Строка" → "String". */
    public static String toPlatformTypeName(String raw) {
        String value = raw.strip();
        int dot = value.indexOf('.');
        if (dot > 0) {
            String prefix = REF_PREFIX.get(normalize(value.substring(0, dot)));
            return (prefix != null ? prefix : value.substring(0, dot)) + value.substring(dot);
        }
        return SIMPLE.getOrDefault(normalize(value), value);
    }

    /** Створює TypeDescription для проєкту; qualifiers: length | precision+scale. */
    public static TypeDescription build(IProject project, List<String> typeNames,
            Integer length, Integer precision, Integer scale) {
        return build(project, typeNames, length, precision, scale, null, null);
    }

    /**
     * Те саме + dateFractions (Date | Time | DateTime) для типу Дата і nonNegative для Число.
     */
    public static TypeDescription build(IProject project, List<String> typeNames,
            Integer length, Integer precision, Integer scale, String dateFractions, Boolean nonNegative) {
        Version version = EdtServices.require(IRuntimeVersionSupport.class).getRuntimeVersion(project);
        IEObjectProvider provider = IEObjectProvider.Registry.INSTANCE
                .get(McorePackage.Literals.TYPE_ITEM, version);
        TypeDescription description = McoreFactory.eINSTANCE.createTypeDescription();
        for (String rawName : typeNames) {
            String platformName = toPlatformTypeName(rawName);
            TypeItem item = producedRefType(platformName);
            if (item == null) {
                item = provider.getProxy(platformName);
            }
            if (item == null) {
                throw new IllegalArgumentException("Тип не знайдено: " + rawName //$NON-NLS-1$
                        + " (платформене ім'я: " + platformName + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            description.getTypes().add(item);
        }
        if (length != null) {
            StringQualifiers qualifiers = McoreFactory.eINSTANCE.createStringQualifiers();
            qualifiers.setLength(length.intValue());
            description.setStringQualifiers(qualifiers);
        }
        if (precision != null) {
            NumberQualifiers qualifiers = McoreFactory.eINSTANCE.createNumberQualifiers();
            qualifiers.setPrecision(precision.intValue());
            qualifiers.setScale(scale == null ? 0 : scale.intValue());
            if (nonNegative != null) {
                qualifiers.setNonNegative(nonNegative.booleanValue());
            }
            description.setNumberQualifiers(qualifiers);
        }
        if (dateFractions != null && !dateFractions.isBlank()) {
            DateQualifiers qualifiers = McoreFactory.eINSTANCE.createDateQualifiers();
            qualifiers.setDateFractions(parseDateFractions(dateFractions));
            description.setDateQualifiers(qualifiers);
        }
        return description;
    }

    private static DateFractions parseDateFractions(String raw) {
        String value = normalize(raw);
        return switch (value) {
        case "date", "дата" -> DateFractions.DATE; //$NON-NLS-1$ //$NON-NLS-2$
        case "time", "время", "час" -> DateFractions.TIME; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        case "datetime", "датавремя", "датачас" -> DateFractions.DATE_TIME; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        default -> throw new IllegalArgumentException("dateFractions: Date | Time | DateTime, отримано " + raw); //$NON-NLS-1$
        };
    }
}
