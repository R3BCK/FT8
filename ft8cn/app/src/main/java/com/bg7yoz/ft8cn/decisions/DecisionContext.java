//DecisionContext.java
package com.bg7yoz.ft8cn.decisions;

import com.bg7yoz.ft8cn.database.DatabaseOpr;
import com.bg7yoz.ft8cn.Ft8Message;

import java.util.List;
import java.util.Set;

/**
 * Контекст для принятия решений в DecisionEngine.
 * Содержит все необходимые данные для оценки ситуации.
 */
public class DecisionContext {
    // Состояние станции
    public final StationState.OperationalMode opMode;
    public final StationState.OperatingSubState subState;
    public final StationState.DialogueStep step;

    // Диалог
    public final String currentTarget;
    public final int noReplyCount;
    public final long lastReplySlot;
    public final Set<String> recentTargets;

    // Окружение
    public final List<DatabaseOpr.StationRecord> visibleStations;
    public final DatabaseOpr.StationRecord directCaller;
    public final long currentSlot;
    public final long timeUntilTxDeadline;

    // Настройки
    public final int noReplyLimit;
    public final boolean autoFollowCQ;
    public final boolean acceptDxCalls;
    public final float[] weights;

    // Флаги
    public final boolean emergencyStop;
    public final boolean userOverrideActive;
    public final boolean forceOwnCQ;

    // [FIX] Добавлено поле для проверки активности передачи
    public final boolean isTransmitActivated;

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
            boolean forceOwnCQ,
            boolean isTransmitActivated  // [FIX] Добавлен параметр
    ) {
        this.opMode = opMode;
        this.subState = subState;
        this.step = step;
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
        this.acceptDxCalls = acceptDxCalls;
        this.weights = weights;
        this.emergencyStop = emergencyStop;
        this.userOverrideActive = userOverrideActive;
        this.forceOwnCQ = forceOwnCQ;
        this.isTransmitActivated = isTransmitActivated;  // [FIX] Инициализация поля
    }
}