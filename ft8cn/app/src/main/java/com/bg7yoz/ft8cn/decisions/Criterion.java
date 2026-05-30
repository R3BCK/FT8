/*
Важно для будущих ИИ-помощников:
При модификации этого модуля:
Сохраняйте комментарии с пометками [STATE MACHINE], [DECISION ENGINE], [NEW]
Не меняйте сигнатуры публичных методов DecisionEngine.evaluate() и StationAction фабрик без веской причины
Любое новое правило в приоритетной лестнице должно быть задокументировано комментарием [P#]
Логирование решений — обязательно для отладки и обучения
*/
package com.bg7yoz.ft8cn.decisions;

/**
 * Критерии для многокритериальной оценки кандидатов.
 * [STATE MACHINE] Используется в HybridScorer для расчёта приоритета.
 *
 * @author BG7YOZ
 * @date 2026-05-10
 */
public enum Criterion {
    SNR("Signal quality", 0.25f),           // Качество сигнала (базовый вес)
    RARITY("DX/new entity", 0.35f),         // Редкость: новый DXCC/CQ/ITU
    PROPAGATION("Band opening", 0.20f),     // Прогноз прохождения на бэнде
    PERSONAL_GOAL("Award progress", 0.15f), // Прогресс в личных целях
    TECH_LIMIT("Antenna/tech", 0.05f);      // Технические ограничения

    public final String description;
    public final float baseWeight; // Базовый вес (настраивается в настройках)

    Criterion(String description, float baseWeight) {
        this.description = description;
        this.baseWeight = baseWeight;
    }
}