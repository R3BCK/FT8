package com.bg7yoz.ft8cn.decisions;

import android.database.Cursor;
import android.util.Log;

import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.database.DatabaseOpr;
import com.bg7yoz.ft8cn.rigs.BaseRigOperation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DecisionEngine {
    private static final String TAG = "DecisionEngine";
    private final HybridScorer scorer = new HybridScorer();
    private final Set<String> workedCallsignsCache = new HashSet<>();
    private long cachedBandFreq = 0;

    public void resetCache() {
        workedCallsignsCache.clear();
        cachedBandFreq = 0;
        Log.d(TAG, "[DECISION] Cache reset: freq changed / history cleared");
    }

    private boolean evaluateOverrideLifecycle(DecisionContext ctx) {
        if (!ctx.userOverrideActive) return false;

        boolean shouldClear = false;
        String reason = "";

        if (GeneralVariables.noReplyLimit > 0 && ctx.noReplyCount >= GeneralVariables.noReplyLimit) {
            shouldClear = true;
            reason = "Max retries reached (" + ctx.noReplyCount + ")";
        } else if (ctx.currentTarget == null || ctx.currentTarget.isEmpty()) {
            shouldClear = true;
            reason = "Target cleared";
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
                reason = "Target disappeared from waterfall";
            }
        }

        if (shouldClear) {
            Log.d(TAG, "[OVERRIDE] Auto-clear conditions met: " + reason);
            return true;
        }
        return false;
    }

    public StationAction evaluate(DecisionContext ctx, List<Ft8Message> messages, DatabaseOpr db) {
        if (ctx.emergencyStop) {
            Log.d(TAG, "[DECISION] emergencyStop=true -> ABORT");
            return StationAction.abort("Emergency stop triggered");
        }

        if (ctx.forceOwnCQ) {
            Log.d(TAG, "[DECISION] forceOwnCQ=true -> TX_OWN_CQ");
            return StationAction.txOwnCQ();
        }

        boolean overrideShouldClear = evaluateOverrideLifecycle(ctx);

        if (overrideShouldClear) {
            return StationAction.abort("Override cleared by state machine");
        }

        if (ctx.userOverrideActive && ctx.currentTarget != null && !ctx.currentTarget.isEmpty()) {
            Log.d(TAG, "[DECISION] userOverrideActive=true -> TRANSMIT to " + ctx.currentTarget);
            Ft8Message msg = findMessageByCallsign(messages, ctx.currentTarget);
            return createTransmitAction(ctx.currentTarget, 1, "Manual override", msg);
        }

        if (ctx.directCaller != null) {
            Ft8Message msg = findMessageByCallsign(messages, ctx.directCaller.callsign);
            if (msg != null) {
                int dbState = ctx.directCaller.ft8StateRelative;
                int msgState = GeneralVariables.checkFunOrder(msg);
                if (msgState == -1) msgState = 0;

                int nextStep;
                boolean isRepeat = (dbState > msgState && msgState > 0);

                if (isRepeat) {
                    nextStep = msgState + 1;
                    Log.d(TAG, "[DECISION] directCaller=" + ctx.directCaller.callsign +
                            " REPEAT detected (DB=" + dbState + " Msg=" + msgState +
                            ") -> Answering based on MSG -> step=" + nextStep);
                } else {
                    if (dbState == 0 || msgState > dbState) {
                        nextStep = msgState + 1;
                        Log.d(TAG, "[DECISION] directCaller=" + ctx.directCaller.callsign +
                                " NEW/SYNC (DB=" + dbState + " Msg=" + msgState +
                                ") -> Answering based on MSG to sync -> step=" + nextStep);
                    } else {
                        nextStep = dbState + 1;
                        Log.d(TAG, "[DECISION] directCaller=" + ctx.directCaller.callsign +
                                " FIRST TIME (DB=" + dbState + " Msg=" + msgState +
                                ") -> Answering based on DB -> step=" + nextStep);
                    }
                }

                if (nextStep < 1) nextStep = 1;
                if (nextStep > 5) nextStep = 5;

                return createTransmitAction(ctx.directCaller.callsign, nextStep, "Direct call detected", msg);
            } else {
                Log.w(TAG, "[DECISION] directCaller=" + ctx.directCaller.callsign + " but msg not in current decode");
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
                Log.d(TAG, "[DECISION] subState=" + ctx.subState + " not implemented -> WAIT");
                return StationAction.wait("State " + ctx.subState + " not fully implemented");
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

    private StationAction createTransmitAction(String callsign, int step, String reason, Ft8Message msg) {
        if (msg != null) {
            Log.d(TAG, "[DECISION] Creating TRANSMIT with freq=" + msg.freq_hz + " snr=" + msg.snr);
            // [FIX] Use RF frequency (band), NOT audio offset (msg.freq_hz)
            return StationAction.transmit(callsign, step, reason,
                    GeneralVariables.band,  // <- RF частота трансивера
                    msg.snr, msg.i3, msg.n3, msg.extraInfo);
        } else {
            Log.w(TAG, "[DECISION] No message for " + callsign + ", using default params");
            return StationAction.transmit(callsign, step, reason);
        }
    }

    private boolean isCallsignWorkedOnBand(String callsign, long bandFreq, DatabaseOpr db) {
        if (bandFreq != cachedBandFreq) {
            workedCallsignsCache.clear();
            cachedBandFreq = bandFreq;
        }
        String cacheKey = callsign + "@" + bandFreq;
        if (workedCallsignsCache.contains(cacheKey)) return true;

        boolean isWorked = false;
        try {
            // UI использует QSLTable для отрисовки перечёркивания.
            // Проверяем ИМЕННО ЭТУ таблицу, чтобы логика совпадала с глазами пользователя.
            String bandString = BaseRigOperation.getMeterFromFreq(bandFreq);

            // 1. Основной лог QSO (QSLTable)
            Cursor cursor = db.getDb().rawQuery(
                    "SELECT id FROM QSLTable WHERE call = ? AND band = ? LIMIT 1",
                    new String[]{callsign, bandString}
            );
            isWorked = (cursor != null && cursor.getCount() > 0);
            if (cursor != null) cursor.close();

            // 2. Fallback: таблица сводки по позывным (QslCallsigns)
            if (!isWorked) {
                cursor = db.getDb().rawQuery(
                        "SELECT ID FROM QslCallsigns WHERE callsign = ? AND band = ? LIMIT 1",
                        new String[]{callsign, bandString}
                );
                isWorked = (cursor != null && cursor.getCount() > 0);
                if (cursor != null) cursor.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "[QSL] Direct query failed: " + e.getMessage());
        }

        if (isWorked) {
            workedCallsignsCache.add(cacheKey);
        }
        return isWorked;
    }

    private StationAction evaluateSeeking(DecisionContext ctx, List<Ft8Message> messages, DatabaseOpr db) {
        Log.d(TAG, "[DEBUG] visibleStations count: " + ctx.visibleStations.size());

        // [CRITICAL FIX #1] Если уже есть currentTarget, НЕ ищем другие CQ!
        if (ctx.currentTarget != null && !ctx.currentTarget.isEmpty()) {
            DatabaseOpr.StationRecord targetRecord = DatabaseOpr.getStationRecord(ctx.currentTarget);

            // Проверяем, видна ли наша цель
            boolean targetVisible = false;
            for (DatabaseOpr.StationRecord s : ctx.visibleStations) {
                if (s.callsign.equals(ctx.currentTarget)) {
                    targetVisible = true;
                    break;
                }
            }

            if (targetVisible) {
                // [CRITICAL FIX #2] Проверяем sequential slot!
                if (targetRecord != null && targetRecord.lastSequential >= 0) {
                    int expectedOurSlot = (targetRecord.lastSequential == 0) ? 1 : 0;
                    if (ctx.currentSlot != expectedOurSlot) {
                        Log.d(TAG, "[DECISION] SEEKING: Waiting for correct slot. Target seq=" +
                                targetRecord.lastSequential + ", our expected=" + expectedOurSlot +
                                ", current=" + ctx.currentSlot);
                        return StationAction.wait("Waiting for correct sequential slot");
                    }
                }

                Log.d(TAG, "[DECISION] SEEKING: Already have target=" + ctx.currentTarget +
                        ", waiting for reply. NOT searching for other CQs");
                return StationAction.wait("Waiting for target reply");
            } else if (ctx.noReplyCount > 0) {
                // Цель пропала и мы уже пытались её позвать
                Log.d(TAG, "[DECISION] Target " + ctx.currentTarget + " DISAPPEARED. ABORT.");
                return StationAction.abort("Target disappeared from waterfall");
            }
        }

        // Ищем CQ только если currentTarget пустой
        List<DatabaseOpr.StationRecord> cqCandidates = new ArrayList<>();
        for (DatabaseOpr.StationRecord s : ctx.visibleStations) {
            if (s.ft8StateRelative == 6) {
                // [FIX 2026-05-23] Skip own callsign to prevent self-calling
                // This prevents the state machine from selecting our own CQ as a target
                if (s.callsign != null && GeneralVariables.myCallsign != null &&
                        s.callsign.equals(GeneralVariables.myCallsign)) {
                    Log.d(TAG, "[DECISION] SKIP " + s.callsign + " (own callsign)");
                    continue;
                }
                // [END FIX]

                /* OLD CODE COMMENTED OUT
                if (s.ft8StateRelative == 6) {
                */

                if (isCallsignWorkedOnBand(s.callsign, GeneralVariables.band, db)) {
                    Log.d(TAG, "[DECISION] SKIP " + s.callsign + " (already in QSL log)");
                    continue;
                }

                if (messages == null || findMessageByCallsign(messages, s.callsign) == null) {
                    Log.d(TAG, "[DECISION] SKIP " + s.callsign + " (no fresh message in current decode)");
                    continue;
                }

                // [CRITICAL FIX #3] Проверяем sequential slot кандидата!
                if (s.lastSequential >= 0) {
                    int expectedOurSlot = (s.lastSequential == 0) ? 1 : 0;
                    if (ctx.currentSlot != expectedOurSlot) {
                        Log.d(TAG, "[DECISION] SKIP " + s.callsign + " (wrong slot: their seq=" +
                                s.lastSequential + ", our expected=" + expectedOurSlot +
                                ", current=" + ctx.currentSlot + ")");
                        continue;
                    }
                }

                cqCandidates.add(s);
            }
        }

        Log.d(TAG, "[DECISION] SEEKING: found " + cqCandidates.size() + " CQ candidates (after QSL + fresh msg + slot filter)");

        if (!cqCandidates.isEmpty() && ctx.autoFollowCQ) {
            DatabaseOpr.StationRecord best = scorer.findBestCandidate(cqCandidates, ctx);
            if (best != null) {
                Log.d(TAG, "[DECISION] SEEKING: selected best CQ=" + best.callsign +
                        " score=" + scorer.score(best, ctx) + " seq=" + best.lastSequential);
                Ft8Message msg = findMessageByCallsign(messages, best.callsign);
                return createTransmitAction(best.callsign, 1, "Best CQ by scoring", msg);
            }
        }

        Log.d(TAG, "[DECISION] SEEKING: no suitable CQ -> let`s WAIT");
        return StationAction.wait("No suitable CQ found, waiting");
    }

    private StationAction evaluateInDialogue(DecisionContext ctx, List<Ft8Message> messages) {
        int maxAttempts = ctx.noReplyLimit > 0 ? ctx.noReplyLimit : 5;
        if (ctx.noReplyCount >= maxAttempts) {
            Log.w(TAG, "[DECISION] IN_DIALOGUE: noReplyCount=" + ctx.noReplyCount + " >= " + maxAttempts + " -> ABORT");
            return StationAction.abort("Max attempts (" + maxAttempts + ") reached");
        }

        // [CRITICAL FIX] Check if we have a target and need to wait for alternating slot
        if (ctx.currentTarget != null && ctx.directCaller == null) {
            DatabaseOpr.StationRecord target = DatabaseOpr.getStationRecord(ctx.currentTarget);
            if (target != null && target.lastSequential >= 0) {
                // Calculate which slot WE should transmit in (opposite of partner)
                int expectedOurSlot = (target.lastSequential == 0) ? 1 : 0;

                // If current slot is NOT our expected slot, wait
                if (ctx.currentSlot != expectedOurSlot) {
                    Log.d(TAG, "[DECISION] IN_DIALOGUE: Waiting for correct slot. " +
                            "Target seq=" + target.lastSequential +
                            ", our expected=" + expectedOurSlot +
                            ", current=" + ctx.currentSlot);
                    return StationAction.wait("Waiting for correct sequential slot");
                }

                // We're in the right slot to transmit, but check if target replied
                // Look for messages FROM our target addressed TO us
                if (messages != null) {
                    for (Ft8Message msg : messages) {
                        if (msg.getCallsignFrom().equalsIgnoreCase(ctx.currentTarget) &&
                                GeneralVariables.checkIsMyCallsign(msg.getCallsignTo())) {
                            // Target replied! Reset counter
                            Log.d(TAG, "[DECISION] IN_DIALOGUE: Reply from " + ctx.currentTarget);
                            return StationAction.wait("Received reply, continuing dialogue");
                        }
                    }
                }

                // No reply yet in this slot - increment counter ONLY if we're in the right slot
                Log.d(TAG, "[DECISION] IN_DIALOGUE: No reply from " + ctx.currentTarget +
                        " in slot " + ctx.currentSlot);
            }
        }

        Log.d(TAG, "[DECISION] IN_DIALOGUE: continuing dialogue with " + ctx.currentTarget);
        return StationAction.wait("Awaiting reply in dialogue");
    }

    private StationAction evaluateSoftFinish(DecisionContext ctx, List<Ft8Message> messages) {
        for (DatabaseOpr.StationRecord s : ctx.visibleStations) {
            if (ctx.recentTargets.contains(s.callsign) && s.ft8StateRelative >= 1 && s.ft8StateRelative <= 4) {
                Log.d(TAG, "[DECISION] SOFT_FINISH: recent target " + s.callsign + " called -> RESUME");
                Ft8Message msg = findMessageByCallsign(messages, s.callsign);
                return createTransmitAction(s.callsign, 1, "Recent target called", msg);
            }
        }

        if (ctx.currentSlot - ctx.lastReplySlot > 8) {
            Log.d(TAG, "[DECISION] SOFT_FINISH: timeout -> NO_OP");
            return StationAction.noOp("Soft finish timeout");
        }

        Log.d(TAG, "[DECISION] SOFT_FINISH: listening for resume");
        return StationAction.wait("Listening for resume");
    }

    private StationAction evaluateNomadic(DecisionContext ctx) {
        if (ctx.noReplyCount >= 3) {
            Log.d(TAG, "[DECISION] NOMADIC: noReplyCount >= 3 -> NOMADIC_SWITCH");
            return StationAction.nomadicSwitch(0, "Nomadic switch trigger");
        }
        Log.d(TAG, "[DECISION] NOMADIC: monitoring current frequency");
        return StationAction.wait("Monitoring frequency");
    }
}