package com.bg7yoz.ft8cn.protocol;

import com.bg7yoz.ft8cn.Ft8Message;

import java.util.regex.Pattern;

/**
 * Stateless-классификатор FT8-сообщений.
 * Единая точка входа для всей логики "что это за сообщение?".
 */
public final class FT8MessageClassifier {

    private static final Pattern GRID_PATTERN =
            Pattern.compile("^[A-R]{2}\\d{2}([a-x]{2})?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern REPORT_PATTERN =
            Pattern.compile("^[-+]\\d{1,3}$");
    private static final Pattern R_REPORT_PATTERN =
            Pattern.compile("^R[-+]\\d{1,3}$", Pattern.CASE_INSENSITIVE);

    private FT8MessageClassifier() {}

    // ==================== Основные методы (для Ft8Message) ====================

    public static ProtocolStep classify(Ft8Message msg) {
        if (msg == null) return ProtocolStep.UNKNOWN;
        return classify(msg.extraInfo, msg.getCallsignTo());
    }

    public static boolean isCQ(Ft8Message msg) {
        if (msg == null) return false;
        return isCQ(msg.getCallsignTo());
    }

    public static boolean isGrid(Ft8Message msg) {
        return msg != null && isGrid(msg.extraInfo);
    }

    public static boolean isReport(Ft8Message msg) {
        return msg != null && isReport(msg.extraInfo);
    }

    public static boolean isRReport(Ft8Message msg) {
        return msg != null && isRReport(msg.extraInfo);
    }

    public static boolean isRR73(Ft8Message msg) {
        return msg != null && isRR73(msg.extraInfo);
    }

    public static boolean isSeventyThree(Ft8Message msg) {
        return msg != null && isSeventyThree(msg.extraInfo);
    }

    public static int getLegacyCode(Ft8Message msg) {
        return classify(msg).getLegacyCode();
    }

    // ==================== Перегруженные методы (для String) ====================

    public static ProtocolStep classify(String extraInfo, String callsignTo) {
        if (extraInfo == null) {
            return isCQ(callsignTo) ? ProtocolStep.CQ : ProtocolStep.UNKNOWN;
        }
        String extra = extraInfo.trim().toUpperCase();

        if (isSeventyThree(extra)) return ProtocolStep.SEVENTY_THREE;
        if (isRR73(extra))         return ProtocolStep.RR73;
        if (isRReport(extra))      return ProtocolStep.R_REPORT;
        if (isReport(extra))       return ProtocolStep.REPORT;
        if (isGrid(extra))         return ProtocolStep.GRID;
        if (isCQ(callsignTo))      return ProtocolStep.CQ;

        return ProtocolStep.UNKNOWN;
    }

    public static ProtocolStep classify(String extraInfo) {
        return classify(extraInfo, null);
    }

    public static boolean isCQ(String callsignTo) {
        String to = trimToEmpty(callsignTo);
        if (to.isEmpty()) return false;
        String first = to.split("\\s+")[0].toUpperCase();
        return first.equals("CQ") || first.startsWith("CQ")
                || first.equals("DE") || first.equals("QRZ");
    }

    public static boolean isGrid(String extraInfo) {
        String extra = trimToEmpty(extraInfo).toUpperCase();
        if (extra.equals("RR73") || extra.equals("RRR") || extra.equals("73")) return false;
        return extra.length() >= 4 && GRID_PATTERN.matcher(extra).matches();
    }

    public static boolean isReport(String extraInfo) {
        return REPORT_PATTERN.matcher(trimToEmpty(extraInfo).toUpperCase()).matches();
    }

    public static boolean isRReport(String extraInfo) {
        return R_REPORT_PATTERN.matcher(trimToEmpty(extraInfo).toUpperCase()).matches();
    }

    public static boolean isRR73(String extraInfo) {
        String extra = trimToEmpty(extraInfo).toUpperCase();
        return extra.equals("RR73") || extra.equals("RRR");
    }

    public static boolean isSeventyThree(String extraInfo) {
        return trimToEmpty(extraInfo).toUpperCase().equals("73");
    }

    public static int getLegacyCode(String extraInfo) {
        return classify(extraInfo).getLegacyCode();
    }

    private static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }
}