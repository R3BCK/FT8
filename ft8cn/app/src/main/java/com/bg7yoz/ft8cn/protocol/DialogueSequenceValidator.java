//DialogueSequenceValidator.java
package com.bg7yoz.ft8cn.protocol;

import com.bg7yoz.ft8cn.Ft8Message;
import java.util.List;

/**
 * Валидатор последовательности FT8-сообщений в диалоге.
 * Проверяет, идут ли сообщения в правильном порядке согласно протоколу.
 */
public final class DialogueSequenceValidator {

    private DialogueSequenceValidator() {}

    /**
     * Проверяет, корректна ли последовательность сообщений в диалоге.
     *
     * @param messages Список сообщений в хронологическом порядке
     * @return true если последовательность корректна
     */
    public static boolean isValidSequence(List<Ft8Message> messages) {
        if (messages == null || messages.isEmpty()) return true;

        ProtocolStep previousStep = null;

        for (Ft8Message msg : messages) {
            ProtocolStep currentStep = FT8MessageClassifier.classify(msg);

            // Проверяем допустимые переходы
            if (previousStep != null && !isValidTransition(previousStep, currentStep)) {
                return false;
            }

            previousStep = currentStep;
        }

        return true;
    }

    /**
     * Проверяет, допустим ли переход от одного шага к другому.
     */
    private static boolean isValidTransition(ProtocolStep from, ProtocolStep to) {
        // Определяем допустимые переходы согласно протоколу FT8
        switch (from) {
            case CQ:
                return to == ProtocolStep.GRID || to == ProtocolStep.CQ;
            case GRID:
                return to == ProtocolStep.REPORT || to == ProtocolStep.GRID;
            case REPORT:
                return to == ProtocolStep.R_REPORT || to == ProtocolStep.REPORT;
            case R_REPORT:
                return to == ProtocolStep.RR73 || to == ProtocolStep.R_REPORT;
            case RR73:
                return to == ProtocolStep.SEVENTY_THREE || to == ProtocolStep.RR73;
            case SEVENTY_THREE:
                return to == ProtocolStep.CQ; // Новый диалог
            default:
                return true;
        }
    }

    /**
     * Определяет следующий ожидаемый шаг после данного.
     *
     * @param currentStep Текущий шаг
     * @return Следующий ожидаемый шаг или UNKNOWN если неизвестно
     */
    public static ProtocolStep getNextExpectedStep(ProtocolStep currentStep) {
        switch (currentStep) {
            case CQ:       return ProtocolStep.GRID;
            case GRID:     return ProtocolStep.REPORT;
            case REPORT:   return ProtocolStep.R_REPORT;
            case R_REPORT: return ProtocolStep.RR73;
            case RR73:     return ProtocolStep.SEVENTY_THREE;
            default:       return ProtocolStep.UNKNOWN;
        }
    }
}