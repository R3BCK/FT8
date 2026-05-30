package com.bg7yoz.ft8cn.decisions;

import com.bg7yoz.ft8cn.database.DatabaseOpr;
import java.util.List;
import java.util.Set;

/**
 * DecisionContext: immutable snapshot of all data needed for a decision.
 * Passed to DecisionEngine.evaluate() each slot.
 *
 * [ARCHITECTURE] This class is a pure data holder (DTO).
 * No logic, no side effects, no mutations.
 *
 * @author BG7YOZ
 * @date 2026-05-10
 */
public class DecisionContext {
    // === Current state triple ===
    public final StationState.OperationalMode opMode;
    public final StationState.OperatingSubState subState;
    public final StationState.DialogueStep step;  // <-- Было пропущено!

    // === Dialogue tracking ===
    public final String currentTarget;
    public final int noReplyCount;
    public final long lastReplySlot;
    public final Set<String> recentTargets;

    // === World Model snapshot ===
    public final List<DatabaseOpr.StationRecord> visibleStations;
    public final DatabaseOpr.StationRecord directCaller;

    // === Timing ===
    public final long currentSlot;
    public final long timeUntilTxDeadline;

    // === Configuration ===
    public final int noReplyLimit;
    public final boolean autoFollowCQ;
    public final boolean acceptDxCalls;  // <-- Было пропущено!
    public final float[] weights;         // <-- Было пропущено!

    // === Flags ===
    public final boolean emergencyStop;
    public final boolean userOverrideActive;

    // [NEW] Флаг: после смены частоты/очистки истории приоритет на собственный CQ
    public final boolean forceOwnCQ;

    /**
     * Constructor: builds immutable context snapshot.
     * All fields are final; no setters allowed.
     */
    public DecisionContext(
            StationState.OperationalMode opMode,
            StationState.OperatingSubState subState,
            StationState.DialogueStep step,
            String currentTarget,
            int noReplyCount,
            long lastReplySlot,
            Set<String> recentTargets,
            List<DatabaseOpr.StationRecord> visibleStations,
            DatabaseOpr.StationRecord directCaller,
            long currentSlot,
            long timeUntilTxDeadline,
            int noReplyLimit,
            boolean autoFollowCQ,
            boolean acceptDxCalls,
            float[] weights,
            boolean emergencyStop,
            boolean userOverrideActive,
            boolean forceOwnCQ
    ) {
        this.opMode = opMode;
        this.subState = subState;
        this.step = step;  // <-- Инициализация
        this.currentTarget = currentTarget;
        this.noReplyCount = noReplyCount;
        this.lastReplySlot = lastReplySlot;
        this.recentTargets = recentTargets;
        this.visibleStations = visibleStations;
        this.directCaller = directCaller;
        this.currentSlot = currentSlot;
        this.timeUntilTxDeadline = timeUntilTxDeadline;
        this.noReplyLimit = noReplyLimit;
        this.autoFollowCQ = autoFollowCQ;
        this.acceptDxCalls = acceptDxCalls;  // <-- Инициализация
        this.weights = weights;               // <-- Инициализация
        this.emergencyStop = emergencyStop;
        this.userOverrideActive = userOverrideActive;
        this.forceOwnCQ = forceOwnCQ;
    }
}