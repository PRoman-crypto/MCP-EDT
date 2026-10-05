/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.metadata;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.google.gson.JsonObject;

/**
 * PNG-рендер керованої форми (рівень B — «sketch»): спрощений макет форми,
 * намальований із дерева Form.form на offscreen SWT Image в UI-треді.
 *
 * <p>Групи, поля (підпис + бокс уведення), кнопки, таблиці з колонками,
 * сторінки (вкладки), декорації малюються прямокутниками з реальними
 * заголовками — достатньо, щоб агент побачив компоновку форми.</p>
 *
 * <p>Вхід — розпарсений DOM-корінь {@code <form:Form>} або сам XML.
 * Вихід — JsonObject {imageBase64 (PNG), width, height, level:"sketch"}.</p>
 */
public final class FormImageRenderer {

    // --- геометрія ---
    private static final int CONTENT_WIDTH = 720;
    private static final int MARGIN = 16;
    private static final int TOTAL_WIDTH = CONTENT_WIDTH + MARGIN * 2;
    private static final int MAX_HEIGHT = 6000;
    private static final int ROW_GAP = 6;
    private static final int FIELD_H = 24;
    private static final int BUTTON_H = 24;
    private static final int LABEL_H = 18;
    private static final int GROUP_PAD = 8;
    private static final int TABLE_ROW_H = 22;
    private static final int TABLE_BODY_ROWS = 3;
    private static final int TAB_H = 26;

    private FormImageRenderer() {
    }

    /** Зручний вхід: сам парсить XML Form.form (DOM, як GetFormImageTool). */
    public static JsonObject render(String formXml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false); //$NON-NLS-1$
        Element root = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(formXml.getBytes(StandardCharsets.UTF_8)))
                .getDocumentElement();
        return render(root);
    }

    /** Головний вхід: розпарсений DOM-корінь {@code <form:Form>}. */
    public static JsonObject render(Element formRoot) {
        return render(formRoot, null);
    }

    /**
     * @param titleFallback заголовок вікна, якщо у формі немає власного {@code <title>}
     *     (наприклад, «Справочник.Номенклатура: ФормаЭлемента»); може бути null
     */
    public static JsonObject render(Element formRoot, String titleFallback) {
        FNode form = parseForm(formRoot);
        if ((form.title == null || form.title.isBlank()) && titleFallback != null && !titleFallback.isBlank()) {
            form.title = titleFallback;
        }

        Display workbench = workbenchDisplay();
        // фолбек Display.getDefault() — для автономних тестів поза workbench
        Display display = workbench != null ? workbench : Display.getDefault();
        if (display == null || display.isDisposed()) {
            throw new IllegalStateException("SWT Display недоступний"); //$NON-NLS-1$
        }

        AtomicReference<byte[]> png = new AtomicReference<>();
        AtomicReference<Point> size = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        display.syncExec(() -> {
            try {
                Point rendered = renderOnUiThread(display, form, png);
                size.set(rendered);
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        if (failure.get() != null) {
            throw new IllegalStateException("PNG-рендер не вдався: " + failure.get(), failure.get()); //$NON-NLS-1$
        }

        JsonObject result = new JsonObject();
        result.addProperty("imageBase64", Base64.getEncoder().encodeToString(png.get())); //$NON-NLS-1$
        result.addProperty("width", size.get().x); //$NON-NLS-1$
        result.addProperty("height", size.get().y); //$NON-NLS-1$
        result.addProperty("level", "sketch"); //$NON-NLS-1$ //$NON-NLS-2$
        return result;
    }

    /** Display робочого стола EDT; null, якщо workbench недоступний (автономний тест). */
    private static Display workbenchDisplay() {
        try {
            return PlatformUI.isWorkbenchRunning() ? PlatformUI.getWorkbench().getDisplay() : null;
        } catch (LinkageError e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Модель вузла форми
    // ------------------------------------------------------------------

    private static final class FNode {
        String kind = "";      // FormGroup | FormField | Button | Decoration | Table | Form //$NON-NLS-1$
        String subtype = "";   // UsualGroup | Pages | Page | CommandBar | Popup | ButtonGroup | InputField | ... //$NON-NLS-1$
        String name = "";      //$NON-NLS-1$
        String title;
        String dataPath;
        boolean visible = true;
        boolean horizontal;
        final List<FNode> children = new ArrayList<>();

        String label() {
            if (title != null && !title.isBlank()) {
                return title;
            }
            if (dataPath != null && !dataPath.isBlank()) {
                int dot = dataPath.lastIndexOf('.');
                return dot >= 0 ? dataPath.substring(dot + 1) : dataPath;
            }
            return name;
        }
    }

    private static FNode parseForm(Element root) {
        FNode form = new FNode();
        form.kind = "Form"; //$NON-NLS-1$
        form.title = localizedText(root, "title"); //$NON-NLS-1$
        // Верхня командна панель форми (autoCommandBar із явними кнопками)
        Element bar = directChild(root, "autoCommandBar"); //$NON-NLS-1$
        if (bar != null) {
            FNode barNode = parseItem(bar);
            if (barNode != null && !barNode.children.isEmpty()) {
                barNode.kind = "FormGroup"; //$NON-NLS-1$
                barNode.subtype = "CommandBar"; //$NON-NLS-1$
                form.children.add(barNode);
            }
        }
        for (Element item : directChildren(root, "items")) { //$NON-NLS-1$
            FNode node = parseItem(item);
            if (node != null) {
                form.children.add(node);
            }
        }
        return form;
    }

    private static FNode parseItem(Element element) {
        FNode node = new FNode();
        node.kind = element.getAttribute("xsi:type").replace("form:", ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        node.subtype = text(directChild(element, "type")); //$NON-NLS-1$
        node.name = text(directChild(element, "name")); //$NON-NLS-1$
        node.title = localizedText(element, "title"); //$NON-NLS-1$
        node.visible = !"false".equals(text(directChild(element, "visible"))); //$NON-NLS-1$ //$NON-NLS-2$
        Element dataPath = directChild(element, "dataPath"); //$NON-NLS-1$
        if (dataPath != null) {
            node.dataPath = text(directChild(dataPath, "segments")); //$NON-NLS-1$
        }
        // Additions (пошуковий рядок, статус перегляду тощо) не малюємо
        if (node.subtype.endsWith("Addition") || "Added".equals(node.subtype)) { //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
        Element extInfo = directChild(element, "extInfo"); //$NON-NLS-1$
        if (extInfo != null) {
            String group = text(directChild(extInfo, "group")); //$NON-NLS-1$
            node.horizontal = group.startsWith("Horizontal") || group.startsWith("AlwaysHorizontal"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        for (Element item : directChildren(element, "items")) { //$NON-NLS-1$
            FNode child = parseItem(item);
            if (child != null) {
                node.children.add(child);
            }
        }
        return node;
    }

    // ------------------------------------------------------------------
    // Рендер (тільки в UI-треді)
    // ------------------------------------------------------------------

    /** Палітра й шрифти одного прогону; всі ресурси dispose у {@link #dispose()}. */
    private static final class Style {
        final Display display;
        final Color windowBg;
        final Color headerText;
        final Color separator;
        final Color groupTitle;
        final Color groupBorder;
        final Color labelText;
        final Color inputBorder;
        final Color inputBg;
        final Color buttonBg;
        final Color buttonBorder;
        final Color buttonText;
        final Color tableHeaderBg;
        final Color tableGrid;
        final Color disabledText;
        final Color decorationText;
        final Color tabActiveBg;
        final Font normal;
        final Font bold;
        final Font small;

        Style(Display display) {
            this.display = display;
            windowBg = new Color(display, 255, 255, 255);
            headerText = new Color(display, 46, 46, 46);
            separator = new Color(display, 213, 213, 213);
            groupTitle = new Color(display, 51, 103, 163);
            groupBorder = new Color(display, 208, 214, 222);
            labelText = new Color(display, 64, 64, 64);
            inputBorder = new Color(display, 184, 184, 184);
            inputBg = new Color(display, 255, 255, 255);
            buttonBg = new Color(display, 245, 245, 245);
            buttonBorder = new Color(display, 172, 172, 172);
            buttonText = new Color(display, 32, 32, 32);
            tableHeaderBg = new Color(display, 242, 242, 242);
            tableGrid = new Color(display, 208, 208, 208);
            disabledText = new Color(display, 154, 154, 154);
            decorationText = new Color(display, 106, 106, 106);
            tabActiveBg = new Color(display, 232, 239, 247);
            FontData base = display.getSystemFont().getFontData()[0];
            normal = new Font(display, base.getName(), Math.max(9, base.getHeight()), SWT.NORMAL);
            bold = new Font(display, base.getName(), Math.max(9, base.getHeight()), SWT.BOLD);
            small = new Font(display, base.getName(), Math.max(8, base.getHeight() - 1), SWT.NORMAL);
        }

        void dispose() {
            for (Color color : new Color[] {windowBg, headerText, separator, groupTitle, groupBorder, labelText,
                    inputBorder, inputBg, buttonBg, buttonBorder, buttonText, tableHeaderBg, tableGrid,
                    disabledText, decorationText, tabActiveBg}) {
                color.dispose();
            }
            normal.dispose();
            bold.dispose();
            small.dispose();
        }
    }

    /** Контекст малювання: paint=false — тільки вимір висоти. */
    private static final class Ctx {
        final GC gc;
        final Style style;
        final boolean paint;

        Ctx(GC gc, Style style, boolean paint) {
            this.gc = gc;
            this.style = style;
            this.paint = paint;
        }
    }

    private static Point renderOnUiThread(Display display, FNode form, AtomicReference<byte[]> pngOut) {
        Style style = new Style(display);
        Image measureImage = new Image(display, 1, 1);
        GC measureGc = new GC(measureImage);
        int height;
        try {
            measureGc.setFont(style.normal);
            height = drawForm(new Ctx(measureGc, style, false), form);
        } finally {
            measureGc.dispose();
            measureImage.dispose();
        }
        height = Math.min(height, MAX_HEIGHT);

        Image image = new Image(display, TOTAL_WIDTH, height);
        GC gc = new GC(image);
        try {
            gc.setAntialias(SWT.ON);
            gc.setTextAntialias(SWT.ON);
            gc.setFont(style.normal);
            gc.setBackground(style.windowBg);
            gc.fillRectangle(0, 0, TOTAL_WIDTH, height);
            drawForm(new Ctx(gc, style, true), form);
        } finally {
            gc.dispose();
        }
        try {
            ImageData data = image.getImageData();
            ImageLoader loader = new ImageLoader();
            loader.data = new ImageData[] {data};
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            loader.save(buffer, SWT.IMAGE_PNG);
            pngOut.set(buffer.toByteArray());
        } finally {
            image.dispose();
            style.dispose();
        }
        return new Point(TOTAL_WIDTH, height);
    }

    /** Малює всю форму, повертає повну висоту в пікселях. */
    private static int drawForm(Ctx ctx, FNode form) {
        int y = 0;
        // Заголовок вікна
        String title = form.title != null && !form.title.isBlank() ? form.title : "Форма"; //$NON-NLS-1$
        if (ctx.paint) {
            ctx.gc.setFont(ctx.style.bold);
            ctx.gc.setForeground(ctx.style.headerText);
            ctx.gc.drawText(truncate(ctx, title, CONTENT_WIDTH - 20), MARGIN, 8, true);
            ctx.gc.setFont(ctx.style.normal);
            ctx.gc.setForeground(ctx.style.disabledText);
            ctx.gc.drawText("✕", TOTAL_WIDTH - MARGIN - 10, 8, true); //$NON-NLS-1$
        }
        y += 30;
        if (ctx.paint) {
            ctx.gc.setForeground(ctx.style.separator);
            ctx.gc.drawLine(0, y, TOTAL_WIDTH, y);
        }
        y += ROW_GAP + 2;
        y = drawChildrenVertical(ctx, form.children, MARGIN, y, CONTENT_WIDTH);
        return y + MARGIN;
    }

    /** Вертикальний стек дітей; повертає новий y. */
    private static int drawChildrenVertical(Ctx ctx, List<FNode> children, int x, int y, int width) {
        for (FNode child : children) {
            int height = drawNode(ctx, child, x, y, width);
            if (height > 0) {
                y += height + ROW_GAP;
            }
            if (y > MAX_HEIGHT) {
                break;
            }
        }
        return y;
    }

    /** Малює один вузол, повертає його висоту (0 — нічого не намальовано). */
    private static int drawNode(Ctx ctx, FNode node, int x, int y, int width) {
        return switch (node.kind) {
            case "FormGroup" -> drawGroup(ctx, node, x, y, width); //$NON-NLS-1$
            case "FormField" -> drawField(ctx, node, x, y, width); //$NON-NLS-1$
            case "Button" -> drawButton(ctx, node, x, y, width, false); //$NON-NLS-1$
            case "Decoration" -> drawDecoration(ctx, node, x, y, width); //$NON-NLS-1$
            case "Table" -> drawTable(ctx, node, x, y, width); //$NON-NLS-1$
            default -> node.children.isEmpty() ? 0 : drawChildrenVertical(ctx, node.children, x, y, width) - y;
        };
    }

    private static int drawGroup(Ctx ctx, FNode group, int x, int y, int width) {
        return switch (group.subtype) {
            case "Pages" -> drawPages(ctx, group, x, y, width); //$NON-NLS-1$
            case "CommandBar", "ButtonGroup" -> drawButtonRow(ctx, group.children, x, y, width); //$NON-NLS-1$ //$NON-NLS-2$
            case "Popup" -> drawButton(ctx, group, x, y, width, true); //$NON-NLS-1$
            default -> drawUsualGroup(ctx, group, x, y, width);
        };
    }

    private static int drawUsualGroup(Ctx ctx, FNode group, int x, int y, int width) {
        boolean showTitle = group.title != null && !group.title.isBlank();
        int top = y;
        int innerY = y + GROUP_PAD + (showTitle ? LABEL_H + 2 : 0);
        int innerX = x + GROUP_PAD;
        int innerW = width - GROUP_PAD * 2;
        int bottom;
        if (group.horizontal && group.children.size() > 1) {
            int gap = 10;
            int columnWidth = (innerW - gap * (group.children.size() - 1)) / group.children.size();
            int maxBottom = innerY;
            int columnX = innerX;
            for (FNode child : group.children) {
                int height = drawNode(ctx, child, columnX, innerY, columnWidth);
                maxBottom = Math.max(maxBottom, innerY + height);
                columnX += columnWidth + gap;
            }
            bottom = maxBottom;
        } else {
            bottom = drawChildrenVertical(ctx, group.children, innerX, innerY, innerW) - ROW_GAP;
        }
        bottom += GROUP_PAD;
        if (ctx.paint) {
            ctx.gc.setForeground(ctx.style.groupBorder);
            ctx.gc.drawRoundRectangle(x, top, width - 1, bottom - top, 6, 6);
            if (showTitle) {
                ctx.gc.setFont(ctx.style.bold);
                ctx.gc.setForeground(group.visible ? ctx.style.groupTitle : ctx.style.disabledText);
                ctx.gc.drawText(truncate(ctx, group.title, width - GROUP_PAD * 2), innerX, top + 4, true);
                ctx.gc.setFont(ctx.style.normal);
            }
        }
        return bottom - top;
    }

    private static int drawPages(Ctx ctx, FNode pages, int x, int y, int width) {
        int top = y;
        // Смуга вкладок
        int tabX = x;
        boolean first = true;
        for (FNode page : pages.children) {
            String label = page.label();
            Point extent = ctx.gc.textExtent(label);
            int tabW = Math.min(extent.x + 20, width / Math.max(1, pages.children.size()));
            if (ctx.paint) {
                if (first) {
                    ctx.gc.setBackground(ctx.style.tabActiveBg);
                    ctx.gc.fillRectangle(tabX, y, tabW, TAB_H);
                    ctx.gc.setBackground(ctx.style.windowBg);
                }
                ctx.gc.setForeground(ctx.style.groupBorder);
                ctx.gc.drawRectangle(tabX, y, tabW, TAB_H);
                ctx.gc.setForeground(first ? ctx.style.headerText : ctx.style.decorationText);
                ctx.gc.drawText(truncate(ctx, label, tabW - 12), tabX + 6, y + 5, true);
            }
            tabX += tabW;
            first = false;
        }
        y += TAB_H + ROW_GAP;
        // Вміст кожної сторінки — стеком, з підписом-«вкладкою»
        for (int i = 0; i < pages.children.size(); i++) {
            FNode page = pages.children.get(i);
            if (i > 0) {
                if (ctx.paint) {
                    ctx.gc.setFont(ctx.style.small);
                    ctx.gc.setForeground(ctx.style.decorationText);
                    ctx.gc.drawText("[" + page.label() + "]", x + 2, y, true); //$NON-NLS-1$ //$NON-NLS-2$
                    ctx.gc.setFont(ctx.style.normal);
                }
                y += LABEL_H;
            }
            int height = drawUsualGroupBody(ctx, page, x, y, width);
            y += height + ROW_GAP;
        }
        return y - top - ROW_GAP;
    }

    /** Тіло сторінки: рамка без заголовка, вміст вертикально/горизонтально. */
    private static int drawUsualGroupBody(Ctx ctx, FNode page, int x, int y, int width) {
        String savedTitle = page.title;
        page.title = null;
        int height = drawUsualGroup(ctx, page, x, y, width);
        page.title = savedTitle;
        return height;
    }

    private static int drawField(Ctx ctx, FNode field, int x, int y, int width) {
        String label = field.label();
        Color textColor = field.visible ? ctx.style.labelText : ctx.style.disabledText;
        switch (field.subtype) {
            case "CheckBoxField": { //$NON-NLS-1$
                if (ctx.paint) {
                    ctx.gc.setForeground(ctx.style.inputBorder);
                    ctx.gc.drawRectangle(x, y + 4, 13, 13);
                    ctx.gc.setForeground(textColor);
                    ctx.gc.drawText(truncate(ctx, label, width - 24), x + 20, y + 2, true);
                }
                return FIELD_H - 2;
            }
            case "LabelField": { //$NON-NLS-1$
                if (ctx.paint) {
                    ctx.gc.setForeground(textColor);
                    ctx.gc.drawText(truncate(ctx, label + ":", width), x, y + 2, true); //$NON-NLS-1$
                }
                return LABEL_H;
            }
            case "RadioButtonField": { //$NON-NLS-1$
                if (ctx.paint) {
                    ctx.gc.setForeground(textColor);
                    ctx.gc.drawText(truncate(ctx, label, width / 2), x, y + 2, true);
                    ctx.gc.setForeground(ctx.style.inputBorder);
                    int cx = x + width / 2;
                    ctx.gc.drawOval(cx, y + 5, 11, 11);
                    ctx.gc.drawOval(cx + 60, y + 5, 11, 11);
                }
                return FIELD_H;
            }
            case "PictureField": { //$NON-NLS-1$
                int boxH = 64;
                if (ctx.paint) {
                    ctx.gc.setForeground(ctx.style.inputBorder);
                    ctx.gc.drawRectangle(x, y, width - 1, boxH);
                    ctx.gc.drawLine(x, y, x + width - 1, y + boxH);
                    ctx.gc.drawLine(x, y + boxH, x + width - 1, y);
                    ctx.gc.setForeground(ctx.style.decorationText);
                    ctx.gc.setFont(ctx.style.small);
                    ctx.gc.drawText(truncate(ctx, label, width - 8), x + 4, y + 2, true);
                    ctx.gc.setFont(ctx.style.normal);
                }
                return boxH;
            }
            default: { // InputField та решта — «підпис + бокс»
                int labelWidth = Math.min(width * 2 / 5, ctx.gc.textExtent(label + ":").x + 6); //$NON-NLS-1$
                int boxX = x + labelWidth + 8;
                int boxW = Math.max(40, x + width - boxX);
                if (ctx.paint) {
                    ctx.gc.setForeground(textColor);
                    ctx.gc.drawText(truncate(ctx, label + ":", labelWidth), x, y + 4, true); //$NON-NLS-1$
                    ctx.gc.setBackground(ctx.style.inputBg);
                    ctx.gc.fillRectangle(boxX, y, boxW, FIELD_H - 4);
                    ctx.gc.setForeground(ctx.style.inputBorder);
                    ctx.gc.drawRectangle(boxX, y, boxW - 1, FIELD_H - 4);
                    if ("InputField".equals(field.subtype)) { //$NON-NLS-1$
                        ctx.gc.setForeground(ctx.style.decorationText);
                        ctx.gc.drawText("…", boxX + boxW - 14, y + 2, true); //$NON-NLS-1$
                    } else if (!field.subtype.isEmpty()) {
                        ctx.gc.setForeground(ctx.style.disabledText);
                        ctx.gc.setFont(ctx.style.small);
                        ctx.gc.drawText(truncate(ctx, field.subtype, boxW - 8), boxX + 4, y + 4, true);
                        ctx.gc.setFont(ctx.style.normal);
                    }
                    ctx.gc.setBackground(ctx.style.windowBg);
                }
                return FIELD_H;
            }
        }
    }

    private static int drawButton(Ctx ctx, FNode button, int x, int y, int width, boolean popup) {
        String label = button.label() + (popup ? " ▾" : ""); //$NON-NLS-1$ //$NON-NLS-2$
        int buttonWidth = Math.min(width, ctx.gc.textExtent(label).x + 20);
        if (ctx.paint) {
            ctx.gc.setBackground(ctx.style.buttonBg);
            ctx.gc.fillRoundRectangle(x, y, buttonWidth, BUTTON_H, 5, 5);
            ctx.gc.setForeground(ctx.style.buttonBorder);
            ctx.gc.drawRoundRectangle(x, y, buttonWidth - 1, BUTTON_H - 1, 5, 5);
            ctx.gc.setForeground(button.visible ? ctx.style.buttonText : ctx.style.disabledText);
            ctx.gc.drawText(truncate(ctx, label, buttonWidth - 12), x + 10, y + 4, true);
            ctx.gc.setBackground(ctx.style.windowBg);
        }
        return BUTTON_H;
    }

    /** Горизонтальний ряд кнопок (командна панель) із переносом рядка. */
    private static int drawButtonRow(Ctx ctx, List<FNode> children, int x, int y, int width) {
        int currentX = x;
        int rows = 1;
        for (FNode child : children) {
            String label = child.label() + ("Popup".equals(child.subtype) ? " ▾" : ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            int buttonWidth = Math.min(width, ctx.gc.textExtent(label).x + 20);
            if (currentX + buttonWidth > x + width) {
                currentX = x;
                rows++;
            }
            int rowY = y + (rows - 1) * (BUTTON_H + 4);
            drawButton(ctx, child, currentX, rowY, buttonWidth, "Popup".equals(child.subtype)); //$NON-NLS-1$
            currentX += buttonWidth + 6;
        }
        return rows * (BUTTON_H + 4) - 4;
    }

    private static int drawDecoration(Ctx ctx, FNode decoration, int x, int y, int width) {
        if (ctx.paint) {
            ctx.gc.setForeground(decoration.visible ? ctx.style.decorationText : ctx.style.disabledText);
            ctx.gc.drawText(truncate(ctx, decoration.label(), width), x, y + 2, true);
        }
        return LABEL_H;
    }

    private static int drawTable(Ctx ctx, FNode table, int x, int y, int width) {
        int top = y;
        // Підпис таблиці
        if (ctx.paint) {
            ctx.gc.setFont(ctx.style.bold);
            ctx.gc.setForeground(ctx.style.groupTitle);
            ctx.gc.drawText(truncate(ctx, table.label(), width), x, y, true);
            ctx.gc.setFont(ctx.style.normal);
        }
        y += LABEL_H + 2;
        // Колонки: сплощуємо ColumnGroup до листових полів
        List<FNode> columns = new ArrayList<>();
        collectColumns(table, columns);
        if (columns.isEmpty()) {
            FNode stub = new FNode();
            stub.name = "…"; //$NON-NLS-1$
            columns.add(stub);
        }
        int tableHeight = TABLE_ROW_H * (1 + TABLE_BODY_ROWS);
        int columnWidth = width / columns.size();
        if (ctx.paint) {
            ctx.gc.setBackground(ctx.style.tableHeaderBg);
            ctx.gc.fillRectangle(x, y, width, TABLE_ROW_H);
            ctx.gc.setBackground(ctx.style.windowBg);
            ctx.gc.setForeground(ctx.style.tableGrid);
            ctx.gc.drawRectangle(x, y, width - 1, tableHeight);
            for (int row = 1; row <= TABLE_BODY_ROWS; row++) {
                ctx.gc.drawLine(x, y + TABLE_ROW_H * row, x + width - 1, y + TABLE_ROW_H * row);
            }
            int columnX = x;
            ctx.gc.setFont(ctx.style.small);
            for (FNode column : columns) {
                if (columnX > x) {
                    ctx.gc.setForeground(ctx.style.tableGrid);
                    ctx.gc.drawLine(columnX, y, columnX, y + tableHeight);
                }
                ctx.gc.setForeground(ctx.style.labelText);
                ctx.gc.drawText(truncate(ctx, column.label(), columnWidth - 8), columnX + 4, y + 4, true);
                columnX += columnWidth;
            }
            ctx.gc.setFont(ctx.style.normal);
        }
        y += tableHeight;
        return y - top;
    }

    private static void collectColumns(FNode parent, List<FNode> out) {
        for (FNode child : parent.children) {
            if ("FormField".equals(child.kind)) { //$NON-NLS-1$
                out.add(child);
            } else if ("FormGroup".equals(child.kind)) { //$NON-NLS-1$
                collectColumns(child, out);
            }
        }
    }

    // ------------------------------------------------------------------
    // Допоміжні
    // ------------------------------------------------------------------

    /** Обрізає текст під задану ширину в пікселях, додаючи «…». */
    private static String truncate(Ctx ctx, String value, int maxWidth) {
        if (value == null || value.isEmpty() || maxWidth <= 0) {
            return ""; //$NON-NLS-1$
        }
        if (ctx.gc.textExtent(value).x <= maxWidth) {
            return value;
        }
        String ellipsis = "…"; //$NON-NLS-1$
        int low = 0;
        int high = value.length();
        while (low < high) {
            int mid = (low + high + 1) / 2;
            if (ctx.gc.textExtent(value.substring(0, mid) + ellipsis).x <= maxWidth) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return value.substring(0, low) + ellipsis;
    }

    private static Element directChild(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element && tagName.equals(element.getTagName())) {
                return element;
            }
        }
        return null;
    }

    private static List<Element> directChildren(Element parent, String tagName) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element && tagName.equals(element.getTagName())) {
                result.add(element);
            }
        }
        return result;
    }

    private static String text(Element element) {
        if (element == null) {
            return ""; //$NON-NLS-1$
        }
        String content = element.getTextContent();
        return content == null ? "" : content.strip(); //$NON-NLS-1$
    }

    /** Локалізований текст: {@code <tag><key>ru</key><value>Текст</value></tag>}. */
    private static String localizedText(Element parent, String tagName) {
        Element element = directChild(parent, tagName);
        if (element == null) {
            return null;
        }
        String value = text(directChild(element, "value")); //$NON-NLS-1$
        if (!value.isEmpty()) {
            return value;
        }
        String raw = text(element);
        return raw.isEmpty() ? null : raw.replaceAll("\\s+", " "); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
