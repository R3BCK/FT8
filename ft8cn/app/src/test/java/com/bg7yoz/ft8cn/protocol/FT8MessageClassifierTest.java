package com.bg7yoz.ft8cn.protocol;

// [ИСПРАВЛЕНО] Правильный импорт Ft8Message
import com.bg7yoz.ft8cn.Ft8Message;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FT8MessageClassifierTest {

    private static Ft8Message msg(String extraInfo, String callsignTo) {
        return new StubFt8Message(extraInfo, callsignTo);
    }

    private static Ft8Message msg(String extraInfo) {
        return msg(extraInfo, "");
    }

    @Test public void classify_cq_variants() {
        assertEquals(ProtocolStep.CQ, FT8MessageClassifier.classify(msg("", "CQ")));
        assertEquals(ProtocolStep.CQ, FT8MessageClassifier.classify(msg("", "CQ DX")));
        assertEquals(ProtocolStep.CQ, FT8MessageClassifier.classify(msg("", "DE")));
    }

    @Test public void classify_grid_4char() {
        assertEquals(ProtocolStep.GRID, FT8MessageClassifier.classify(msg("KO85")));
        assertEquals(ProtocolStep.GRID, FT8MessageClassifier.classify(msg("FN31pr")));
    }

    @Test public void grid_excludes_RR73_and_73() {
        assertFalse(FT8MessageClassifier.isGrid(msg("RR73")));
        assertFalse(FT8MessageClassifier.isGrid(msg("73")));
    }

    @Test public void classify_signal_reports() {
        assertEquals(ProtocolStep.REPORT, FT8MessageClassifier.classify(msg("-15")));
        assertEquals(ProtocolStep.REPORT, FT8MessageClassifier.classify(msg("+05")));
    }

    @Test public void classify_r_reports() {
        assertEquals(ProtocolStep.R_REPORT, FT8MessageClassifier.classify(msg("R-15")));
        assertEquals(ProtocolStep.R_REPORT, FT8MessageClassifier.classify(msg("R+05")));
    }

    @Test public void classify_rr73_and_rrr() {
        assertEquals(ProtocolStep.RR73, FT8MessageClassifier.classify(msg("RR73")));
        assertEquals(ProtocolStep.RR73, FT8MessageClassifier.classify(msg("RRR")));
    }

    @Test public void classify_73() {
        assertEquals(ProtocolStep.SEVENTY_THREE, FT8MessageClassifier.classify(msg("73")));
    }

    @Test public void classify_null() {
        assertEquals(ProtocolStep.UNKNOWN, FT8MessageClassifier.classify(null));
    }

    @Test public void legacyCode_mapping() {
        assertEquals(6, FT8MessageClassifier.getLegacyCode(msg("", "CQ")));
        assertEquals(1, FT8MessageClassifier.getLegacyCode(msg("KO85")));
        assertEquals(2, FT8MessageClassifier.getLegacyCode(msg("-15")));
        assertEquals(3, FT8MessageClassifier.getLegacyCode(msg("R-15")));
        assertEquals(4, FT8MessageClassifier.getLegacyCode(msg("RR73")));
        assertEquals(5, FT8MessageClassifier.getLegacyCode(msg("73")));
    }

    // Минимальный Stub для тестов, так как Ft8Message в FT8CN имеет открытые поля
    private static class StubFt8Message extends Ft8Message {
        StubFt8Message(String extraInfo, String callsignTo) {
            // В Ft8CN эти поля публичные
            this.extraInfo = extraInfo;
            this.callsignTo = callsignTo;
        }

        // Переопределяем геттер, если он используется внутри checkIsCQ/isCQ
        @Override
        public String getCallsignTo() {
            return callsignTo;
        }
    }
}