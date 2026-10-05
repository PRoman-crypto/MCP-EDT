/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.debug;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;

import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugClientTarget;
import com._1c.g5.v8.dt.debug.core.model.evaluation.EvaluationRequest;
import com._1c.g5.v8.dt.debug.core.model.evaluation.IEvaluationEngine;
import com._1c.g5.v8.dt.debug.core.model.evaluation.IEvaluationRequest;
import com._1c.g5.v8.dt.debug.core.model.evaluation.IEvaluationResult;
import com._1c.g5.v8.dt.debug.core.model.values.BslValuePath;
import com._1c.g5.v8.dt.debug.model.calculations.BaseValueInfoData;
import com._1c.g5.v8.dt.debug.model.calculations.CalculationResultBaseData;
import com._1c.g5.v8.dt.debug.model.calculations.ViewInterface;

/**
 * Обчислення BSL-виразу на зупиненому фреймі відладчика.
 *
 * Синхронна обгортка асинхронного механізму EDT: RuntimeEvaluationEngine
 * реєструє слухач за UUID обчислення і надсилає запит у dbgs з waitTime=0,
 * тому результат зазвичай приходить пізніше подією RDBGEvalExprCompleted —
 * чекаємо його через CountDownLatch з таймаутом.
 *
 * Декодування результату (підтверджено дизасемблюванням
 * BslWatchExpressionDelegate і RuntimePresentationConverter з
 * com._1c.g5.v8.dt.debug.core 18.0.0): byte[]-поля EMF-моделі
 * (Pres, ValueString, ExceptionStr) — це звичайний UTF-8 текст.
 */
public final class DebugEvaluator {

    /** Таймаут очікування результату за замовчуванням, мс. */
    public static final long DEFAULT_TIMEOUT_MS = 10_000L;

    /**
     * Ліміт довжини презентації значення у відповіді dbgs
     * (0 = без ліміту; дефолт EDT — 100, замало для MCP).
     */
    private static final int MAX_TEXT_SIZE = 10_000;

    private DebugEvaluator() {
    }

    /**
     * Обчислює BSL-вираз на зупиненому фреймі.
     *
     * @param target debug-ціль, якій належить фрейм (джерело EvaluationEngine)
     * @param frame зупинений BSL-фрейм (контекст обчислення)
     * @param expression BSL-вираз, наприклад "1+1" або ім'я змінної
     * @param timeoutMs таймаут очікування результату; {@code <= 0} — {@link #DEFAULT_TIMEOUT_MS}
     * @return {expression, value, type?, expandable?, collectionSize?} або {expression, error}
     */
    public static JsonObject evaluate(IRuntimeDebugClientTarget target, IBslStackFrame frame,
            String expression, long timeoutMs) throws Exception {
        JsonObject result = new JsonObject();
        result.addProperty("expression", expression); //$NON-NLS-1$

        // Двигун мовчки ігнорує запит, якщо фрейм вимкнено або ціль не зупинена
        // (RuntimeEvaluationEngine.shouldEvaluate) — без цієї перевірки був би
        // беззмістовний таймаут замість пояснення.
        if (!target.isSuspended()) {
            throw new IllegalStateException(
                    "Debug-ціль не зупинена — evaluate можливий лише в suspended-стані."); //$NON-NLS-1$
        }
        if (!frame.isEnabled()) {
            throw new IllegalStateException(
                    "Фрейм вимкнений (isEnabled()==false) — обчислення на ньому недоступне."); //$NON-NLS-1$
        }

        IEvaluationEngine engine = target.getEvaluationEngine();
        if (engine == null) {
            throw new IllegalStateException("EvaluationEngine недоступний для цієї debug-цілі."); //$NON-NLS-1$
        }

        long timeout = timeoutMs <= 0 ? DEFAULT_TIMEOUT_MS : timeoutMs;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<IEvaluationResult> evaluationResult = new AtomicReference<>();

        // Той самий ланцюг, що у watch-виразів EDT (BslWatchExpressionDelegate):
        // builder + ViewInterface.NONE + випадковий UUID виразу.
        IEvaluationRequest request = EvaluationRequest.builder(new BslValuePath(expression))
                .setStackFrame(frame)
                .setExpressionUuid(UUID.randomUUID())
                .setInterface(ViewInterface.NONE)
                .setMaxTestSize(MAX_TEXT_SIZE) // [sic] назва сеттера в EDT
                .setMultiLine(true)
                .setEvaluationListener(r -> {
                    evaluationResult.set(r);
                    latch.countDown();
                })
                .build();

        // Виклик сам по собі швидкий (HTTP-надсилання з waitTime=0); якщо dbgs
        // відповість одразу — слухач спрацює синхронно ще до await.
        engine.evaluateExpression(request);

        if (!latch.await(timeout, TimeUnit.MILLISECONDS)) {
            result.addProperty("error", "Таймаут очікування результату (" + timeout + " мс). " //$NON-NLS-1$ //$NON-NLS-2$
                    + "Можливо, відбулася нова зупинка (двигун скидає незавершені обчислення) " //$NON-NLS-1$
                    + "або сеанс 1С не відповідає."); //$NON-NLS-1$
            return result;
        }
        return decode(evaluationResult.get(), result);
    }

    // ------------------------------------------------------------------ decode

    /** Розбирає IEvaluationResult → JSON; помилки — в поле error, без винятків. */
    private static JsonObject decode(IEvaluationResult evaluation, JsonObject result) {
        if (evaluation == null) {
            result.addProperty("error", "Порожня відповідь двигуна обчислень."); //$NON-NLS-1$ //$NON-NLS-2$
            return result;
        }
        if (!evaluation.isSuccess()) {
            result.addProperty("error", evaluation.getErrorMessage() == null //$NON-NLS-1$
                    ? "Обчислення не виконано (без повідомлення)." //$NON-NLS-1$
                    : evaluation.getErrorMessage());
            return result;
        }
        CalculationResultBaseData data = evaluation.getResult();
        if (data == null) {
            // Аналог Messages.BslWatchExpressionDelegate_Empty_evaluation_result в EDT.
            result.addProperty("error", "Порожній результат обчислення."); //$NON-NLS-1$ //$NON-NLS-2$
            return result;
        }
        if (Boolean.TRUE.equals(data.getErrorOccurred())) {
            // Текст винятку 1С (наприклад, помилка синтаксису виразу) — UTF-8.
            result.addProperty("error", utf8(data.getExceptionStr())); //$NON-NLS-1$
            return result;
        }
        BaseValueInfoData info = data.getResultValueInfo();
        if (info == null) {
            result.addProperty("error", "Результат без опису значення (ResultValueInfo=null)."); //$NON-NLS-1$ //$NON-NLS-2$
            return result;
        }
        String pres = utf8(info.getPres());
        // Для рядкових значень презентація може бути порожньою — падаємо на ValueString.
        result.addProperty("value", pres.isEmpty() ? utf8(info.getValueString()) : pres); //$NON-NLS-1$
        if (info.isSetTypeName()) {
            result.addProperty("type", info.getTypeName()); //$NON-NLS-1$
        }
        if (Boolean.TRUE.equals(info.getIsExpandable())) {
            result.addProperty("expandable", true); //$NON-NLS-1$
        }
        if (info.isSetCollectionSize() && info.getCollectionSize() != null) {
            result.addProperty("collectionSize", info.getCollectionSize()); //$NON-NLS-1$
        }
        return result;
    }

    /**
     * Декодування byte[]-полів EMF-моделі dbgs: чисте UTF-8, null → "".
     * Точна копія RuntimePresentationConverter.presentation(byte[]) —
     * сам клас internal і не експортується бандлом.
     */
    private static String utf8(byte[] bytes) {
        return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8); //$NON-NLS-1$
    }
}
