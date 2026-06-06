//ReportExtractor.java
package com.bg7yoz.ft8cn.protocol;

import com.bg7yoz.ft8cn.Ft8Message;

/**
 * Извлекает числовые значения из FT8-сообщений (репорты, R-репорты).
 */
public final class ReportExtractor {

    private ReportExtractor() {}

    /**
     * Извлекает значение signal report из сообщения.
     *
     * @param msg Сообщение
     * @return Значение репорта (например, -15, +05) или -100 если не репорт
     */
    public static int extractReportValue(Ft8Message msg) {
        if (msg == null) return -100;

        String extra = msg.extraInfo;
        if (extra == null || extra.isEmpty()) return -100;

        // Если это 73, возвращаем специальное значение
        if (FT8MessageClassifier.isSeventyThree(msg)) return -100;

        // Пробуем извлечь число
        String normalized = extra.toUpperCase().replace("R", "").trim();

        try {
            return Integer.parseInt(normalized);
        } catch (NumberFormatException e) {
            return -100;
        }
    }

    /**
     * Проверяет, является ли сообщение репортом (обычным или R-репортом).
     */
    public static boolean isAnyReport(Ft8Message msg) {
        return FT8MessageClassifier.isReport(msg) || FT8MessageClassifier.isRReport(msg);
    }
}