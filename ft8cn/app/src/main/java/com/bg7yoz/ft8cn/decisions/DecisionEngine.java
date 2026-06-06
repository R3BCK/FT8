//DecisionEngine.java
package com.bg7yoz.ft8cn.decisions;

import android.database.Cursor;
import android.util.Log;

import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.database.DatabaseOpr;
import com.bg7yoz.ft8cn.protocol.FT8MessageClassifier;
import com.bg7yoz.ft8cn.protocol.ProtocolStep;
import com.bg7yoz.ft8cn.rigs.BaseRigOperation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DecisionEngine {
    private static final String TAG = "DecisionEngine";
    private final HybridScorer scorer = new HybridScorer();
    private final Set<String> workedCallsignsCache = new HashSet<>();

    // [FIX] Кэш для RF частоты диапазона (например, 18100000 Hz = 18.1 MHz)
    // НЕ путать с audioFreqHz (0-3000 Hz - аудио тон FT8 сигнала)
    private long cachedRfBandFreq = 0;

    public void resetCache() {
        workedCallsignsCache.clear();
        cachedRfBandFreq = 0;
        Log.d(TAG, "[DECISION] Сброс кэша: RF частота изменена / история очищена");
    }

    private boolean evaluateOverrideLifecycle(DecisionContext ctx) {
        if (!ctx.userOverrideActive) return false;

        boolean shouldClear = false;
        String reason = "";

        if (GeneralVariables.noReplyLimit > 0 && ctx.noReplyCount >= GeneralVariables.noReplyLimit) {
            shouldClear = true;
            reason = "Достигнут лимит попыток (" + ctx.noReplyCount + ")";
        } else if (ctx.currentTarget == null || ctx.currentTarget.isEmpty()) {
            shouldClear = true;
            reason = "Цель очищена";
        } else if (ctx.currentTarget != null && !ctx.currentTarget.isEmpty() && ctx.visibleStations != null) {
            boolean targetVisible = false;
            for (DatabaseOpr.StationRecord s : ctx.visibleStations) {
                if (s.callsign.equals(ctx.currentTarget)) {
                    targetVisible = true;
                    break;
                }
            }
            if (!targetVisible && ctx.noReplyCount > 0) {
                shouldClear = true;
                reason = "Цель исчезла из водопада";
            }
        }

        if (shouldClear) {
            Log.d(TAG, "[OVERRIDE] Условия автоочистки выполнены: " + reason);
            return true;
        }
        return false;
    }

    private Ft8Message findDirectCallToUs(DecisionContext ctx, List<Ft8Message> messages) {
        if (messages == null) return null;

        for (Ft8Message msg : messages) {
            if (GeneralVariables.checkIsMyCallsign(msg.getCallsignTo())) {
                String caller = msg.getCallsignFrom();
                if (!GeneralVariables.checkIsMyCallsign(caller)) {
                    Log.d(TAG, "[DECISION] Найден прямой вызов от " + caller + " нам");
                    return msg;
                }
            }
        }
        return null;
    }

    public StationAction evaluate(DecisionContext ctx, List<Ft8Message> messages, DatabaseOpr db) {
        if (ctx.emergencyStop) {
            Log.d(TAG, "[DECISION] emergencyStop=true -> ABORT");
            return StationAction.abort("Сработала аварийная остановка");
        }

        if (ctx.forceOwnCQ) {
            Log.d(TAG, "[DECISION] forceOwnCQ=true -> TX_OWN_CQ");
            return StationAction.txOwnCQ();
        }

        boolean overrideShouldClear = evaluateOverrideLifecycle(ctx);

        if (overrideShouldClear) {
            return StationAction.abort("Override очищен машиной состояний");
        }

        // [ИСПРАВЛЕНИЕ] Проверяем прямые вызовы НАМ ПЕРВЫМ - наивысший приоритет
        Ft8Message directCallMsg = findDirectCallToUs(ctx, messages);
        if (directCallMsg != null) {
            String caller = directCallMsg.getCallsignFrom();
            Log.d(TAG, "[DECISION] Обнаружен прямой вызов от " + caller);

            ProtocolStep step = FT8MessageClassifier.classify(directCallMsg);
            int nextStep = protocolStepToLegacyStep(step);

            Log.d(TAG, "[DECISION] Классификация сообщения: " + step + " -> шаг " + nextStep);

            boolean isCurrentTarget = (ctx.currentTarget != null &&
                    ctx.currentTarget.equalsIgnoreCase(caller));

            String reason = isCurrentTarget ?
                    "Прямой вызов от текущей цели" :
                    "Обнаружен прямой вызов (переопределение ручной цели)";

            return createTransmitAction(caller, nextStep, reason, directCallMsg);
        }

        if (ctx.userOverrideActive && ctx.currentTarget != null && !ctx.currentTarget.isEmpty()) {
            Log.d(TAG, "[DECISION] userOverrideActive=true -> ПЕРЕДАЧА к " + ctx.currentTarget);
            Ft8Message msg = findMessageByCallsign(messages, ctx.currentTarget);
            return createTransmitAction(ctx.currentTarget, 1, "Ручное переопределение", msg);
        }

        // [КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ] Обрабатываем directCaller используя РЕАЛЬНОЕ содержимое сообщения
        if (ctx.directCaller != null) {
            Ft8Message msg = findMessageByCallsign(messages, ctx.directCaller.callsign);
            if (msg != null) {
                ProtocolStep step = FT8MessageClassifier.classify(msg);
                int nextStep = protocolStepToLegacyStep(step);

                Log.d(TAG, "[DECISION] directCaller=" + ctx.directCaller.callsign +
                        " step=" + step + " (dbState=" + ctx.directCaller.ft8StateRelative +
                        ") -> шаг=" + nextStep);

                return createTransmitAction(ctx.directCaller.callsign, nextStep,
                        "Обнаружен прямой вызов", msg);
            } else {
                Log.w(TAG, "[DECISION] directCaller=" + ctx.directCaller.callsign +
                        " но сообщение не в текущем декодировании");
            }
        }

        switch (ctx.subState) {
            case SEEKING:
                return evaluateSeeking(ctx, messages, db);
            case IN_DIALOGUE:
                return evaluateInDialogue(ctx, messages);
            case SOFT_FINISH:
                return evaluateSoftFinish(ctx, messages);
            case NOMADIC:
                return evaluateNomadic(ctx);
            default:
                Log.d(TAG, "[DECISION] subState=" + ctx.subState + " не реализовано -> WAIT");
                return StationAction.wait("Состояние " + ctx.subState + " не полностью реализовано");
        }
    }

    /**
     * Конвертация ProtocolStep в legacy-номер шага (1-5).
     * Временный метод для обратной совместимости.
     */
    private int protocolStepToLegacyStep(ProtocolStep step) {
        switch (step) {
            case CQ:            return 1; // CQ -> Grid
            case GRID:          return 2; // Grid -> Report
            case REPORT:        return 3; // Report -> R-Report
            case R_REPORT:      return 4; // R-Report -> RR73
            case RR73:          return 5; // RR73 -> 73
            case SEVENTY_THREE: return 5; // 73 -> QSO complete
            default:            return 1; // Unknown -> Grid (default)
        }
    }

    private Ft8Message findMessageByCallsign(List<Ft8Message> messages, String callsign) {
        if (messages == null || callsign == null) return null;
        for (Ft8Message m : messages) {
            if (m.getCallsignFrom().equals(callsign)) {
                return m;
            }
        }
        return null;
    }

    /**
     * Создаёт действие передачи.
     *
     * ВАЖНО о частотах:
     * - msg.freq_hz = АУДИО частота (audioFreqHz), диапазон 0-3000 Hz (тон FT8 сигнала в аудио)
     * - GeneralVariables.band = RF частота (rfBandFreq), диапазон МГц (например 18100000 Hz = 18.1 MHz)
     *
     * В StationAction передаётся RF частота для настройки трансивера.
     * Аудио частота используется для генерации тона внутри FT8TransmitSignal.
     */
    private StationAction createTransmitAction(String callsign, int step, String reason, Ft8Message msg) {
        if (msg != null) {
            // [FIX] Чёткое разделение audio и RF частот в логах
            Log.d(TAG, "[DECISION] Создание TRANSMIT: audioFreqHz=" + msg.freq_hz +
                    " (тон FT8), rfBandHz=" + GeneralVariables.band +
                    " (диапазон), snr=" + msg.snr);
            return StationAction.transmit(callsign, step, reason,
                    GeneralVariables.band,  // RF частота для трансивера
                    msg.snr, msg.i3, msg.n3, msg.extraInfo);
        } else {
            Log.w(TAG, "[DECISION] Нет сообщения для " + callsign + ", используются параметры по умолчанию");
            return StationAction.transmit(callsign, step, reason);
        }
    }

    /**
     * Проверяет, отработан ли позывной на данной RF частоте диапазона.
     *
     * @param callsign Позывной для проверки
     * @param rfBandFreq RF частота диапазона в Hz (например 18100000 для 18.1 MHz)
     * @param db DatabaseOpr для запросов
     */
    private boolean isCallsignWorkedOnBand(String callsign, long rfBandFreq, DatabaseOpr db) {
        if (rfBandFreq != cachedRfBandFreq) {
            workedCallsignsCache.clear();
            cachedRfBandFreq = rfBandFreq;
        }
        String cacheKey = callsign + "@" + rfBandFreq;
        if (workedCallsignsCache.contains(cacheKey)) return true;

        boolean isWorked = false;
        try {
            String bandString = BaseRigOperation.getMeterFromFreq(rfBandFreq);

            Cursor cursor = db.getDb().rawQuery(
                    "SELECT id FROM QSLTable WHERE call = ? AND band = ? LIMIT 1",
                    new String[]{callsign, bandString}
            );
            isWorked = (cursor != null && cursor.getCount() > 0);
            if (cursor != null) cursor.close();

            if (!isWorked) {
                cursor = db.getDb().rawQuery(
                        "SELECT ID FROM QslCallsigns WHERE callsign = ? AND band = ? LIMIT 1",
                        new String[]{callsign, bandString}
                );
                isWorked = (cursor != null && cursor.getCount() > 0);
                if (cursor != null) cursor.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "[QSL] Прямой запрос не удался: " + e.getMessage());
        }

        if (isWorked) {
            workedCallsignsCache.add(cacheKey);
        }
        return isWorked;
    }

    private StationAction evaluateSeeking(DecisionContext ctx, List<Ft8Message> messages, DatabaseOpr db) {
        Log.d(TAG, "[DEBUG] количество visibleStations: " + ctx.visibleStations.size());

        if (ctx.currentTarget != null && !ctx.currentTarget.isEmpty()) {
            DatabaseOpr.StationRecord targetRecord = DatabaseOpr.getStationRecord(ctx.currentTarget);

            boolean targetVisible = false;
            for (DatabaseOpr.StationRecord s : ctx.visibleStations) {
                if (s.callsign.equals(ctx.currentTarget)) {
                    targetVisible = true;
                    break;
                }
            }

            if (targetVisible) {
                // [FIX] Удалена некорректная проверка слотов в SEEKING
                // Если цель видна - мы просто ждём ответа, проверка слотов не нужна
                Log.d(TAG, "[DECISION] SEEKING: Уже есть цель=" + ctx.currentTarget +
                        ", ожидание ответа. НЕ ищем другие CQ");
                return StationAction.wait("Ожидание ответа от цели");
            } else if (ctx.noReplyCount > 0) {
                Log.d(TAG, "[DECISION] Цель " + ctx.currentTarget + " ИСЧЕЗЛА. ABORT.");
                return StationAction.abort("Цель исчезла из водопада");
            }
        }

        List<DatabaseOpr.StationRecord> cqCandidates = new ArrayList<>();
        for (DatabaseOpr.StationRecord s : ctx.visibleStations) {
            if (s.ft8StateRelative == 6) {
                if (s.callsign != null && GeneralVariables.myCallsign != null &&
                        s.callsign.equals(GeneralVariables.myCallsign)) {
                    Log.d(TAG, "[DECISION] ПРОПУСК " + s.callsign + " (собственный позывной)");
                    continue;
                }

                // [FIX] Используем RF частоту диапазона
                if (isCallsignWorkedOnBand(s.callsign, GeneralVariables.band, db)) {
                    Log.d(TAG, "[DECISION] ПРОПУСК " + s.callsign + " (уже в журнале QSL)");
                    continue;
                }

                if (messages == null || findMessageByCallsign(messages, s.callsign) == null) {
                    Log.d(TAG, "[DECISION] ПРОПУСК " + s.callsign + " (нет свежего сообщения в текущем декодировании)");
                    continue;
                }

                // [FIX] Удалена некорректная проверка слотов
                // Логика: если мы декодировали CQ в текущем слоте, мы ВСЕГДА можем ответить в следующем
                // Проверка "текущий слот приёма == наш слот передачи" бессмысленна

                cqCandidates.add(s);
            }
        }

        Log.d(TAG, "[DECISION] SEEKING: найдено " + cqCandidates.size() + " кандидатов CQ (после фильтра QSL + свежее сообщение)");

        if (!cqCandidates.isEmpty() && ctx.autoFollowCQ) {
            DatabaseOpr.StationRecord best = scorer.findBestCandidate(cqCandidates, ctx);
            if (best != null) {
                Log.d(TAG, "[DECISION] SEEKING: выбран лучший CQ=" + best.callsign +
                        " оценка=" + scorer.score(best, ctx) + " seq=" + best.lastSequential);
                Ft8Message msg = findMessageByCallsign(messages, best.callsign);
                return createTransmitAction(best.callsign, 1, "Лучший CQ по оценке", msg);
            }
        }

        // [FIX] Если нет CQ кандидатов, но передача активна - запускаем собственное CQ
        // Проверяем что передача активна (пользователь нажал Transmit button)
        if (ctx.isTransmitActivated) {
            Log.d(TAG, "[DECISION] SEEKING: нет CQ кандидатов, но передача активна -> TX_OWN_CQ");
            return StationAction.txOwnCQ();
        }

        Log.d(TAG, "[DECISION] SEEKING: нет подходящего CQ и передача не активна -> WAIT");
        return StationAction.wait("Подходящий CQ не найден, ожидание");
    }

    private StationAction evaluateInDialogue(DecisionContext ctx, List<Ft8Message> messages) {
        int maxAttempts = ctx.noReplyLimit > 0 ? ctx.noReplyLimit : 5;
        if (ctx.noReplyCount >= maxAttempts) {
            Log.w(TAG, "[DECISION] IN_DIALOGUE: noReplyCount=" + ctx.noReplyCount + " >= " + maxAttempts + " -> ABORT");
            return StationAction.abort("Достигнуто максимальное количество попыток (" + maxAttempts + ")");
        }

        if (ctx.currentTarget != null && ctx.directCaller == null) {
            // [FIX] УДАЛЕНА некорректная проверка слотов!
            // В IN_DIALOGUE мы должны ОБРАБОТАТЬ любое сообщение от цели,
            // чтобы понять - ответил ли он нам, или снова передал CQ.
            // Проверка "текущий слот приёма == наш слот передачи" здесь бессмысленна,
            // так как мы ПРИНИМАЕМ в текущем слоте, а ПЕРЕДАЁМ в следующем.

            if (messages != null) {
                for (Ft8Message msg : messages) {
                    if (msg.getCallsignFrom().equalsIgnoreCase(ctx.currentTarget) &&
                            GeneralVariables.checkIsMyCallsign(msg.getCallsignTo())) {

                        // [НОВОЕ] Полностью переписан determineNextStep
                        int nextStep = determineNextStepNew(msg);

                        Log.d(TAG, "[DECISION] IN_DIALOGUE: Ответ от " + ctx.currentTarget +
                                " (msg=" + msg.getMessageText() + ") -> шаг " + nextStep);

                        return createTransmitAction(ctx.currentTarget, nextStep,
                                "Ответ от " + ctx.currentTarget, msg);
                    }
                }
            }

            Log.d(TAG, "[DECISION] IN_DIALOGUE: Нет ответа от " + ctx.currentTarget +
                    " в слоте " + ctx.currentSlot);
        }

        Log.d(TAG, "[DECISION] IN_DIALOGUE: продолжение диалога с " + ctx.currentTarget);
        return StationAction.wait("Ожидание ответа в диалоге");
    }

    /**
     * [НОВОЕ] Переписанный метод определения следующего шага.
     * Использует FT8MessageClassifier вместо regex-ов.
     */
    private int determineNextStepNew(Ft8Message msg) {
        ProtocolStep step = FT8MessageClassifier.classify(msg);

        Log.d(TAG, "[STEP] Классификация: " + step + " (extra='" + msg.extraInfo + "')");

        switch (step) {
            case CQ:
                Log.d(TAG, "[STEP] Получен CQ -> отправка Grid (шаг 1)");
                return 1;

            case GRID:
                Log.d(TAG, "[STEP] Получен Grid -> отправка Report (шаг 2)");
                return 2;

            case REPORT:
                Log.d(TAG, "[STEP] Получен Signal Report -> отправка R-Report (шаг 3)");
                return 3;

            case R_REPORT:
                Log.d(TAG, "[STEP] Получен R-Report -> отправка RR73 (шаг 4)");
                return 4;

            case RR73:
                Log.d(TAG, "[STEP] Получен RR73 -> отправка 73 (шаг 5)");
                return 5;

            case SEVENTY_THREE:
                Log.d(TAG, "[STEP] Получен 73 -> QSO завершено (шаг 5)");
                return 5;

            case UNKNOWN:
            default:
                Log.d(TAG, "[STEP] Неизвестный тип сообщения -> прогрессия по умолчанию");
                return 1; // Начать с Grid
        }
    }

    private StationAction evaluateSoftFinish(DecisionContext ctx, List<Ft8Message> messages) {
        if (messages != null && !ctx.recentTargets.isEmpty()) {
            for (Ft8Message msg : messages) {
                String from = msg.getCallsignFrom();
                if (!ctx.recentTargets.contains(from)) continue;

                ProtocolStep step = FT8MessageClassifier.classify(msg);

                if (step == ProtocolStep.RR73 || step == ProtocolStep.SEVENTY_THREE) {
                    Log.d(TAG, "[DECISION] SOFT_FINISH: " + from +
                            " отправил RR73/73 — QSO завершено, игнорируем (без возобновления)");
                    continue;
                }

                if (GeneralVariables.checkIsMyCallsign(msg.getCallsignTo()) && !msg.checkIsCQ()) {
                    int nextStep = protocolStepToLegacyStep(step);

                    Log.d(TAG, "[DECISION] SOFT_FINISH: " + from +
                            " снова вызвал нас -> RESUME шаг=" + nextStep);
                    return createTransmitAction(from, nextStep, "Недавняя цель возобновила QSO", msg);
                }
            }
        }

        if (ctx.currentSlot - ctx.lastReplySlot > 8) {
            Log.d(TAG, "[DECISION] SOFT_FINISH: тайм-аут -> NO_OP");
            return StationAction.noOp("Тайм-аут мягкого завершения");
        }

        Log.d(TAG, "[DECISION] SOFT_FINISH: прослушивание для возобновления");
        return StationAction.wait("Прослушивание для возобновления");
    }

    private StationAction evaluateNomadic(DecisionContext ctx) {
        if (ctx.noReplyCount >= 3) {
            Log.d(TAG, "[DECISION] NOMADIC: noReplyCount >= 3 -> NOMADIC_SWITCH");
            return StationAction.nomadicSwitch(0, "Триггер кочевого переключения");
        }
        Log.d(TAG, "[DECISION] NOMADIC: мониторинг текущей RF частоты");
        return StationAction.wait("Мониторинг RF частоты");
    }

    /**
     * Update World Model state based on decoded messages.
     * This is business logic, not DAO logic.
     *
     * @param messages List of newly decoded messages
     */
    public void updateWorldModelState(List<Ft8Message> messages) {
        if (messages == null) return;

        for (Ft8Message msg : messages) {
            String callsign = msg.getCallsignFrom();
            if (callsign == null || callsign.isEmpty()) continue;

            DatabaseOpr.StationRecord record = DatabaseOpr.getStationRecord(callsign);
            if (record == null) continue;

            int detectedState = FT8MessageClassifier.getLegacyCode(msg);
            boolean isRelevantToUs = GeneralVariables.checkIsMyCallsign(msg.getCallsignTo());
            boolean isCQ = msg.checkIsCQ();

            // Бизнес-логика определения состояния диалога
            if (isRelevantToUs && detectedState >= 1 && detectedState <= 4) {
                int oldState = record.ft8StateRelative;
                record.ft8StateRelative = detectedState;
                if (record.ft8StateRelative != oldState) {
                    Log.d(TAG, "[STATE] " + callsign + ": " + oldState + " → " + record.ft8StateRelative);
                }
            } else if (isCQ && record.ft8StateRelative < 1) {
                record.ft8StateRelative = 6;
            }

            // Обновляем в БД
            DatabaseOpr.getInstance(null, null).updateStationStateInDb(record);
        }
    }
}