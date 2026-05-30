package com.bg7yoz.ft8cn.decisions;
/**
 * StationAction: immutable command object from DecisionEngine to executor.
 * Contains all data needed to perform an action, including technical parameters.
 *
 * [ARCHITECTURE] This class is the contract between decision logic and execution.
 * DecisionEngine creates StationAction instances; MainViewModel executes them.
 * No side effects, no state mutations here.
 *
 * @author BG7YOZ
 * @date 2026-05-10
 */

public class StationAction {

    /**
     * Типы действий, которые может вернуть DecisionEngine.
     * Каждый тип обрабатывается в MainViewModel.executeAction().
     */
    public enum ActionType {
        TRANSMIT,       // Начать или продолжить передачу
        WAIT,           // Ждать, ничего не передавать
        ABORT,          // Прервать текущий диалог, вернуться в SEEKING
        RESUME,         // Возобновить диалог с недавней станцией
        NOMADIC_SWITCH, // Переключиться на следующую частоту в режиме Nomadic
        SCAN_AUDIO,     // Переключиться в режим аудио-калибровки
        NO_OP,          // Нейтральное действие (для переходов состояний)
        TX_OWN_CQ       // [NEW] Приоритетная отправка собственного CQ
    }

    // === Основные поля действия ===

    // Тип действия (обязательное поле)
    public final ActionType type;

    // Позывной целевой станции (может быть пустым для WAIT/NO_OP)
    public final String targetCallsign;

    // Шаг протокола FT8 (1-6), 0 = не определён
    public final int protocolStep;

    // Целевая частота для переключения (0 = не менять)
    public final long targetFrequency;

    // Приоритет действия (0.0-1.0), для логирования и отладки
    public final float priority;

    // Причина принятия решения (человекочитаемая строка для лога)
    public final String reason;

    // === [NEW] Технические параметры для передачи ===
    // Эти поля позволяют DecisionEngine передать все данные для ft8TransmitSignal.setTransmit()
    // без необходимости поиска сообщения в executeAction.

    // Частота в Гц (0 = использовать текущую частоту из GeneralVariables.band)
    public final long freqHz;

    // Измеренный SNR (-99 = неизвестно, использовать дефолт)
    public final int snr;

    // Hash part 1 (i3) для кодирования позывного в пакет FT8
    public final int i3;

    // Hash part 2 (n3) для кодирования грид-локатора
    public final int n3;

    // Дополнительная информация (грид, рапорт, "CQ", и т.д.)
    public final String extraInfo;

    /**
     * Приватный конструктор: создаёт объект только через фабричные методы.
     * Гарантирует неизменяемость (immutable) и валидацию полей.
     */
    private StationAction(ActionType type, String targetCallsign, int protocolStep,
                          long targetFrequency, float priority, String reason,
                          long freqHz, int snr, int i3, int n3, String extraInfo) {
        this.type = type;
        this.targetCallsign = targetCallsign;
        this.protocolStep = protocolStep;
        this.targetFrequency = targetFrequency;
        this.priority = priority;
        this.reason = reason;
        this.freqHz = freqHz;
        this.snr = snr;
        this.i3 = i3;
        this.n3 = n3;
        this.extraInfo = extraInfo;
    }

    // === Фабричные методы (Factory Methods) ===
    // Упрощают создание действий с правильными дефолтными значениями.

    /**
     * Создать действие TRANSMIT с полными техническими параметрами.
     * Используется, когда у DecisionEngine есть сообщение с параметрами.
     */
    public static StationAction transmit(String callsign, int step, String reason,
                                         long freqHz, int snr, int i3, int n3, String extraInfo) {
        return new StationAction(ActionType.TRANSMIT, callsign, step, 0, 1.0f, reason,
                freqHz, snr, i3, n3, extraInfo);
    }

    /**
     * Создать действие TRANSMIT с параметрами по умолчанию.
     * Используется, когда сообщение не найдено (редкий кейс).
     * Частота и параметры будут взяты из текущего контекста в executeAction.
     */
    public static StationAction transmit(String callsign, int step, String reason) {
        return transmit(callsign, step, reason, 0, -99, 0, 0, "");
    }

    /**
     * Создать действие WAIT (ожидание).
     */
    public static StationAction wait(String reason) {
        return new StationAction(ActionType.WAIT, "", 0, 0, 0.5f, reason,
                0, -99, 0, 0, "");
    }

    /**
     * Создать действие ABORT (прерывание).
     */
    public static StationAction abort(String reason) {
        return new StationAction(ActionType.ABORT, "", 0, 0, 0.9f, reason,
                0, -99, 0, 0, "");
    }

    /**
     * Создать действие RESUME (возобновление диалога).
     */
    public static StationAction resume(String callsign, String reason) {
        return new StationAction(ActionType.RESUME, callsign, 1, 0, 0.8f, reason,
                0, -99, 0, 0, "");
    }

    /**
     * Создать действие NOMADIC_SWITCH (переключение частоты).
     * @param freq Целевая частота (0 = вычислить автоматически)
     */
    public static StationAction nomadicSwitch(long freq, String reason) {
        return new StationAction(ActionType.NOMADIC_SWITCH, "", 0, freq, 0.7f, reason,
                0, -99, 0, 0, "");
    }

    /**
     * Создать действие SCAN_AUDIO (переход в аудио-режим).
     */
    public static StationAction scanAudio(String reason) {
        return new StationAction(ActionType.SCAN_AUDIO, "", 0, 0, 0.6f, reason,
                0, -99, 0, 0, "");
    }

    /**
     * Создать действие NO_OP (нейтральное, для переходов состояний).
     */
    public static StationAction noOp(String reason) {
        return new StationAction(ActionType.NO_OP, "", 0, 0, 0.1f, reason,
                0, -99, 0, 0, "");
    }

    public static StationAction txOwnCQ() {
        return new StationAction(ActionType.TX_OWN_CQ, "", 0, 0, 1.0f, "Own CQ priority",
                0, -99, 0, 0, "");
    }

}