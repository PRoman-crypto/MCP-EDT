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

/**
 * Порядковий diff двох версій модуля.
 *
 * <p>Спершу зрізається спільний префікс і суфікс — на реальних правках це прибирає
 * майже весь файл. Далі середина зіставляється через LCS. Якщо середина завелика
 * (правка розкидана по всьому модулю), LCS-таблиця не будується: віддається один
 * блок «видалено/додано», і це чесно позначається в {@link #algorithm()}.
 */
final class LineDiff {

    /** Стеля для LCS-таблиці: 4 млн комірок ≈ 16 МБ int — прийнятно для модуля будь-якого розміру. */
    private static final long LCS_CELL_LIMIT = 4_000_000L;

    enum Kind { EQUAL, DELETE, INSERT }

    record Op(Kind kind, int oldLine, int newLine, String text) {
    }

    private final List<Op> ops = new ArrayList<>();
    private final String algorithm;
    private int added;
    private int removed;

    private LineDiff(String algorithm) {
        this.algorithm = algorithm;
    }

    static LineDiff between(String[] oldLines, String[] newLines) {
        int prefix = 0;
        while (prefix < oldLines.length && prefix < newLines.length
                && oldLines[prefix].equals(newLines[prefix])) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < oldLines.length - prefix && suffix < newLines.length - prefix
                && oldLines[oldLines.length - 1 - suffix].equals(newLines[newLines.length - 1 - suffix])) {
            suffix++;
        }
        int oldMiddle = oldLines.length - prefix - suffix;
        int newMiddle = newLines.length - prefix - suffix;

        boolean lcs = (long) oldMiddle * newMiddle <= LCS_CELL_LIMIT;
        LineDiff diff = new LineDiff(lcs ? "lcs" : "block"); //$NON-NLS-1$ //$NON-NLS-2$
        for (int i = 0; i < prefix; i++) {
            diff.ops.add(new Op(Kind.EQUAL, i + 1, i + 1, oldLines[i]));
        }
        if (lcs) {
            diff.lcsMiddle(oldLines, newLines, prefix, oldMiddle, newMiddle);
        } else {
            for (int i = 0; i < oldMiddle; i++) {
                diff.ops.add(new Op(Kind.DELETE, prefix + i + 1, 0, oldLines[prefix + i]));
                diff.removed++;
            }
            for (int i = 0; i < newMiddle; i++) {
                diff.ops.add(new Op(Kind.INSERT, 0, prefix + i + 1, newLines[prefix + i]));
                diff.added++;
            }
        }
        for (int i = 0; i < suffix; i++) {
            int oldIndex = prefix + oldMiddle + i;
            int newIndex = prefix + newMiddle + i;
            diff.ops.add(new Op(Kind.EQUAL, oldIndex + 1, newIndex + 1, oldLines[oldIndex]));
        }
        return diff;
    }

    private void lcsMiddle(String[] oldLines, String[] newLines, int prefix, int oldMiddle, int newMiddle) {
        int[][] table = new int[oldMiddle + 1][newMiddle + 1];
        for (int i = oldMiddle - 1; i >= 0; i--) {
            for (int j = newMiddle - 1; j >= 0; j--) {
                table[i][j] = oldLines[prefix + i].equals(newLines[prefix + j])
                        ? table[i + 1][j + 1] + 1
                        : Math.max(table[i + 1][j], table[i][j + 1]);
            }
        }
        int i = 0;
        int j = 0;
        while (i < oldMiddle && j < newMiddle) {
            if (oldLines[prefix + i].equals(newLines[prefix + j])) {
                ops.add(new Op(Kind.EQUAL, prefix + i + 1, prefix + j + 1, oldLines[prefix + i]));
                i++;
                j++;
            } else if (table[i + 1][j] >= table[i][j + 1]) {
                ops.add(new Op(Kind.DELETE, prefix + i + 1, 0, oldLines[prefix + i]));
                removed++;
                i++;
            } else {
                ops.add(new Op(Kind.INSERT, 0, prefix + j + 1, newLines[prefix + j]));
                added++;
                j++;
            }
        }
        for (; i < oldMiddle; i++) {
            ops.add(new Op(Kind.DELETE, prefix + i + 1, 0, oldLines[prefix + i]));
            removed++;
        }
        for (; j < newMiddle; j++) {
            ops.add(new Op(Kind.INSERT, 0, prefix + j + 1, newLines[prefix + j]));
            added++;
        }
    }

    List<Op> ops() {
        return ops;
    }

    String algorithm() {
        return algorithm;
    }

    int added() {
        return added;
    }

    int removed() {
        return removed;
    }

    boolean isEmpty() {
        return added == 0 && removed == 0;
    }

    /** Unified diff із контекстом; maxLines обрізає вивід, лишаючи лічильники правдивими. */
    String unified(int context, int maxLines) {
        List<int[]> hunks = hunkRanges(context);
        StringBuilder out = new StringBuilder();
        int emitted = 0;
        for (int[] hunk : hunks) {
            int from = hunk[0];
            int to = hunk[1];
            int oldStart = 0;
            int newStart = 0;
            int oldCount = 0;
            int newCount = 0;
            for (int k = from; k <= to; k++) {
                Op op = ops.get(k);
                if (op.kind() != Kind.INSERT) {
                    if (oldStart == 0) {
                        oldStart = op.oldLine();
                    }
                    oldCount++;
                }
                if (op.kind() != Kind.DELETE) {
                    if (newStart == 0) {
                        newStart = op.newLine();
                    }
                    newCount++;
                }
            }
            out.append("@@ -").append(oldStart).append(',').append(oldCount) //$NON-NLS-1$
                    .append(" +").append(newStart).append(',').append(newCount).append(" @@\n"); //$NON-NLS-1$ //$NON-NLS-2$
            for (int k = from; k <= to; k++) {
                if (emitted >= maxLines) {
                    out.append("… diff обрізано (показано ").append(emitted).append(" рядків)\n"); //$NON-NLS-1$ //$NON-NLS-2$
                    return out.toString();
                }
                Op op = ops.get(k);
                out.append(switch (op.kind()) {
                    case EQUAL -> " "; //$NON-NLS-1$
                    case DELETE -> "-"; //$NON-NLS-1$
                    case INSERT -> "+"; //$NON-NLS-1$
                }).append(op.text()).append('\n');
                emitted++;
            }
        }
        return out.toString();
    }

    /** Діапазони індексів ops, що утворюють hunk-и: зміни плюс context рядків навколо. */
    private List<int[]> hunkRanges(int context) {
        List<int[]> hunks = new ArrayList<>();
        int start = -1;
        int lastChange = -1;
        for (int k = 0; k < ops.size(); k++) {
            if (ops.get(k).kind() == Kind.EQUAL) {
                continue;
            }
            if (start >= 0 && k - lastChange - 1 <= context * 2) {
                lastChange = k;
                continue;
            }
            if (start >= 0) {
                hunks.add(new int[] {Math.max(0, start - context),
                        Math.min(ops.size() - 1, lastChange + context)});
            }
            start = k;
            lastChange = k;
        }
        if (start >= 0) {
            hunks.add(new int[] {Math.max(0, start - context),
                    Math.min(ops.size() - 1, lastChange + context)});
        }
        return hunks;
    }
}
