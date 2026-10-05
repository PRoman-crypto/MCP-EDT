/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.code;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Легкий структурний розбір модуля BSL: методи, області, змінні модуля.
 * Не повний парсер — але методи в BSL не вкладаються, тож меж достатньо
 * для читання/заміни методів. Точна модель Xtext — кандидат на пізніше.
 */
public final class BslOutline {

    public static final class Method {
        public String name;
        public String kind; // Procedure | Function
        public boolean export;
        public List<String> directives = new ArrayList<>();
        public String signature;
        public int startLine; // 1-based, рядок Процедура/Функция (без директив)
        public int endLine;   // 1-based, рядок КонецПроцедуры/КонецФункции
        /** Параметри, розібрані з сигнатури. */
        public List<Parameter> parameters = new ArrayList<>();
        /** Суцільний блок рядків-коментарів безпосередньо над методом (сирий текст). */
        public String docComment;
        /** Ім'я найглибшої області (#Область), що містить метод; null — поза областями. */
        public String region;
    }

    /** Параметр методу: ім'я, «Знач», значення за замовчуванням, типи з doc-коментаря. */
    public static final class Parameter {
        public String name;
        public boolean byValue;
        public String defaultValue;
        public List<String> types = new ArrayList<>();
        public String description;
    }

    public static final class Region {
        public String name;
        public int level;
        public int startLine;
        public int endLine;
    }

    public final List<Method> methods = new ArrayList<>();
    public final List<Region> regions = new ArrayList<>();
    public final List<String> moduleVariables = new ArrayList<>();
    public int totalLines;

    private static final Pattern METHOD_START = Pattern.compile(
            "^\\s*(Процедура|Функция|Procedure|Function)\\s+([\\p{L}\\d_]+)\\s*\\(", //$NON-NLS-1$
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern METHOD_END = Pattern.compile(
            "^\\s*(КонецПроцедуры|КонецФункции|EndProcedure|EndFunction)\\b", //$NON-NLS-1$
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern REGION_START = Pattern.compile(
            "^\\s*#(?:Область|Region)\\s+(.+?)\\s*$", //$NON-NLS-1$
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern REGION_END = Pattern.compile(
            "^\\s*#(?:КонецОбласти|EndRegion)\\b", //$NON-NLS-1$
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern VARIABLE = Pattern.compile(
            "^\\s*(?:Перем|Var)\\s+(.+?);?\\s*(?://.*)?$", //$NON-NLS-1$
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern EXPORT_AFTER_PARAMS = Pattern.compile(
            "\\)\\s*(Экспорт|Export)\\b", //$NON-NLS-1$
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern BY_VALUE = Pattern.compile(
            "^(?:Знач|Val)\\s+", //$NON-NLS-1$
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern DOC_PARAMETERS = Pattern.compile(
            "^(?:Параметры|Parameters)\\s*:", //$NON-NLS-1$
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** Будь-який інший заголовок секції doc-коментаря — кінець блоку параметрів. */
    private static final Pattern DOC_SECTION = Pattern.compile(
            "^(?:Возвращаемое значение|Returned value|Пример|Example|Описание|Description)\\s*:", //$NON-NLS-1$
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern DOC_PARAMETER_ENTRY = Pattern.compile(
            "^([\\p{L}\\d_]+)\\s*[-–—]\\s*([^-–—]+?)\\s*(?:[-–—]\\s*(.*))?$", //$NON-NLS-1$
            Pattern.UNICODE_CASE);

    public static BslOutline parse(String source) {
        BslOutline outline = new BslOutline();
        String[] lines = source.split("\r?\n", -1); //$NON-NLS-1$
        outline.totalLines = lines.length;

        Deque<Region> regionStack = new ArrayDeque<>();
        Method current = null;
        boolean beforeFirstMethod = true;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineNo = i + 1;

            Matcher regionStart = REGION_START.matcher(line);
            if (regionStart.find()) {
                Region region = new Region();
                region.name = regionStart.group(1).trim();
                region.level = regionStack.size();
                region.startLine = lineNo;
                regionStack.push(region);
                outline.regions.add(region);
                continue;
            }
            if (REGION_END.matcher(line).find()) {
                if (!regionStack.isEmpty()) {
                    regionStack.pop().endLine = lineNo;
                }
                continue;
            }

            if (current == null) {
                Matcher start = METHOD_START.matcher(line);
                if (start.find()) {
                    beforeFirstMethod = false;
                    current = new Method();
                    String kindWord = start.group(1).toLowerCase();
                    current.kind = kindWord.startsWith("процедура") || kindWord.startsWith("procedure") //$NON-NLS-1$ //$NON-NLS-2$
                            ? "Procedure" : "Function"; //$NON-NLS-1$ //$NON-NLS-2$
                    current.name = start.group(2);
                    current.startLine = lineNo;
                    collectDirectives(lines, i, current.directives);
                    current.signature = collectSignature(lines, i);
                    current.export = EXPORT_AFTER_PARAMS.matcher(current.signature).find();
                    continue;
                }
                if (beforeFirstMethod) {
                    Matcher variable = VARIABLE.matcher(line);
                    if (variable.find()) {
                        for (String name : variable.group(1).split(",")) { //$NON-NLS-1$
                            String clean = name.replaceAll("(?iu)\\bЭкспорт\\b|\\bExport\\b", "").trim(); //$NON-NLS-1$ //$NON-NLS-2$
                            if (!clean.isEmpty()) {
                                outline.moduleVariables.add(clean);
                            }
                        }
                    }
                }
            } else if (METHOD_END.matcher(line).find()) {
                current.endLine = lineNo;
                outline.methods.add(current);
                current = null;
            }
        }
        if (current != null) { // незакритий метод — беремо до кінця файлу
            current.endLine = lines.length;
            outline.methods.add(current);
        }
        for (Method method : outline.methods) {
            method.parameters = parseParameters(method.signature);
            method.docComment = collectDocComment(lines, method);
            applyDocTypes(method);
            method.region = regionOf(outline.regions, method.startLine);
        }
        return outline;
    }

    /** Найглибша область, що містить рядок; null — рядок поза областями. */
    private static String regionOf(List<Region> regions, int line) {
        String name = null;
        int bestLevel = -1;
        for (Region region : regions) {
            int end = region.endLine > 0 ? region.endLine : Integer.MAX_VALUE;
            if (line >= region.startLine && line <= end && region.level > bestLevel) {
                bestLevel = region.level;
                name = region.name;
            }
        }
        return name;
    }

    /**
     * Параметри з тексту сигнатури. Розбір на верхньому рівні дужок і поза рядковими
     * літералами — інакше кома всередині {@code = "а,б"} або {@code Новий Массив(1,2)}
     * розірвала б параметр навпіл.
     */
    private static List<Parameter> parseParameters(String signature) {
        List<Parameter> parameters = new ArrayList<>();
        if (signature == null) {
            return parameters;
        }
        int open = signature.indexOf('(');
        if (open < 0) {
            return parameters;
        }
        int depth = 0;
        boolean inString = false;
        StringBuilder token = new StringBuilder();
        for (int i = open; i < signature.length(); i++) {
            char c = signature.charAt(i);
            if (c == '"') {
                inString = !inString;
            }
            if (!inString) {
                if (c == '(') {
                    depth++;
                    if (depth == 1) {
                        continue; // сама відкривна дужка списку
                    }
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        addParameter(parameters, token.toString());
                        break;
                    }
                } else if (c == ',' && depth == 1) {
                    addParameter(parameters, token.toString());
                    token.setLength(0);
                    continue;
                }
            }
            token.append(c);
        }
        return parameters;
    }

    private static void addParameter(List<Parameter> parameters, String raw) {
        String text = raw.trim();
        if (text.isEmpty()) {
            return;
        }
        Parameter parameter = new Parameter();
        Matcher byValue = BY_VALUE.matcher(text);
        if (byValue.find()) {
            parameter.byValue = true;
            text = text.substring(byValue.end()).trim();
        }
        int equals = text.indexOf('=');
        if (equals >= 0) {
            parameter.name = text.substring(0, equals).trim();
            parameter.defaultValue = text.substring(equals + 1).trim();
        } else {
            parameter.name = text;
        }
        if (!parameter.name.isEmpty()) {
            parameters.add(parameter);
        }
    }

    /** Рядки-коментарі суцільним блоком над методом (над директивами теж). */
    private static String collectDocComment(String[] lines, Method method) {
        List<String> comment = new ArrayList<>();
        for (int i = startLineWithDirectives(method) - 2; i >= 0; i--) {
            String line = lines[i].strip();
            if (line.startsWith("//")) { //$NON-NLS-1$
                comment.add(0, line);
            } else if (!line.isEmpty()) {
                break;
            } else if (!comment.isEmpty()) {
                break; // порожній рядок відділяє чужий коментар
            }
        }
        return comment.isEmpty() ? null : String.join("\n", comment); //$NON-NLS-1$
    }

    /**
     * Типи й описи параметрів із doc-коментаря стандарту 1С:
     * {@code // Параметры:} далі рядки {@code //  Имя - Тип - опис}.
     */
    private static void applyDocTypes(Method method) {
        if (method.docComment == null || method.parameters.isEmpty()) {
            return;
        }
        boolean inParameters = false;
        for (String rawLine : method.docComment.split("\n")) { //$NON-NLS-1$
            String line = rawLine.replaceFirst("^//+", "").strip(); //$NON-NLS-1$ //$NON-NLS-2$
            if (DOC_PARAMETERS.matcher(line).find()) {
                inParameters = true;
                continue;
            }
            if (DOC_SECTION.matcher(line).find()) {
                inParameters = false;
                continue;
            }
            if (!inParameters) {
                continue;
            }
            Matcher entry = DOC_PARAMETER_ENTRY.matcher(line);
            if (!entry.find()) {
                continue;
            }
            String name = entry.group(1);
            for (Parameter parameter : method.parameters) {
                if (!parameter.name.equalsIgnoreCase(name)) {
                    continue;
                }
                for (String type : entry.group(2).split("[,|]")) { //$NON-NLS-1$
                    String clean = type.trim();
                    if (!clean.isEmpty()) {
                        parameter.types.add(clean);
                    }
                }
                String description = entry.group(3);
                if (description != null && !description.isBlank()) {
                    parameter.description = description.strip();
                }
            }
        }
    }

    /** Директиви компіляції (&НаСервере тощо) — суцільний блок рядків із & безпосередньо над методом. */
    private static void collectDirectives(String[] lines, int methodIndex, List<String> target) {
        List<String> collected = new ArrayList<>();
        for (int i = methodIndex - 1; i >= 0; i--) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("&")) { //$NON-NLS-1$
                collected.add(0, trimmed);
            } else if (!trimmed.isEmpty() && !trimmed.startsWith("//")) { //$NON-NLS-1$
                break;
            } else if (trimmed.isEmpty()) {
                break;
            }
        }
        target.addAll(collected);
    }

    /** Сигнатура: від рядка оголошення до першого рядка з ')' (максимум 100 рядків). */
    private static String collectSignature(String[] lines, int methodIndex) {
        StringBuilder signature = new StringBuilder();
        for (int i = methodIndex; i < Math.min(methodIndex + 100, lines.length); i++) {
            if (i > methodIndex) {
                signature.append(' ');
            }
            signature.append(lines[i].trim());
            if (lines[i].contains(")")) { //$NON-NLS-1$
                break;
            }
        }
        return signature.toString();
    }

    public Method findMethod(String name) {
        for (Method method : methods) {
            if (method.name.equalsIgnoreCase(name)) {
                return method;
            }
        }
        return null;
    }

    /** Початковий рядок методу з урахуванням директив над ним. */
    public static int startLineWithDirectives(Method method) {
        return Math.max(1, method.startLine - method.directives.size());
    }
}
