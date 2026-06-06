//ProtocolStep.java
package com.bg7yoz.ft8cn.protocol;

/**
 * Канонический enum всех типов FT8-сообщений.
 * Единый источник правды для всей бизнес-логики протокола.
 *
 * Legacy-коды соответствуют старым значениям из parseMessageState / checkFun*:
 *   1 = GRID, 2 = REPORT, 3 = R_REPORT, 4 = RR73, 5 = 73, 6 = CQ
 */
public enum ProtocolStep {
    CQ(6, "CQ / DE / QRZ"),
    GRID(1, "Grid locator (e.g. KO85, FN31pr)"),
    REPORT(2, "Signal report (e.g. -15, +05)"),
    R_REPORT(3, "R-Report (e.g. R-15, R+05)"),
    RR73(4, "RR73 or RRR"),
    SEVENTY_THREE(5, "73"),
    UNKNOWN(-1, "Unknown / free text / non-standard");

    private final int legacyCode;
    private final String description;

    ProtocolStep(int legacyCode, String description) {
        this.legacyCode = legacyCode;
        this.description = description;
    }

    public int getLegacyCode() {
        return legacyCode;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Обратное преобразование для совместимости со старым кодом,
     * который ещё оперирует магическими числами 1..6.
     */
    public static ProtocolStep fromLegacyCode(int code) {
        for (ProtocolStep step : values()) {
            if (step.legacyCode == code) return step;
        }
        return UNKNOWN;
    }
}