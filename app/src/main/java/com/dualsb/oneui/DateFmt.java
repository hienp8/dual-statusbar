package com.dualsb.oneui;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/** Định dạng ngày: SimpleDateFormat + mã {t} {thu} {am} {canchi}. */
final class DateFmt {
    private DateFmt() {}

    static String format(String pat, Calendar cal, Locale loc) {
        if (pat == null || pat.isEmpty()) return "";
        if (pat.indexOf('{') < 0) return seg(pat, cal, loc);
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < pat.length()) {
            int a = pat.indexOf('{', i);
            if (a < 0) { out.append(seg(pat.substring(i), cal, loc)); break; }
            if (a > i) out.append(seg(pat.substring(i, a), cal, loc));
            int b = pat.indexOf('}', a);
            if (b < 0) { out.append(seg(pat.substring(a), cal, loc)); break; }
            out.append(token(pat.substring(a + 1, b), cal));
            i = b + 1;
        }
        return out.toString();
    }

    private static String seg(String p, Calendar cal, Locale loc) {
        if (p.isEmpty()) return "";
        try { return new SimpleDateFormat(p, loc).format(cal.getTime()); }
        catch (Exception e) { return p; }
    }

    private static String token(String t, Calendar cal) {
        int dow = cal.get(Calendar.DAY_OF_WEEK);
        switch (t) {
            case "t": return dow == Calendar.SUNDAY ? "CN" : "T." + dow;
            case "thu": return dow == Calendar.SUNDAY ? "CN" : "Thứ " + dow;
            case "am": {
                int[] l = LunarCalendar.solarToLunar(cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.YEAR));
                return l[0] + "/" + l[1] + (l[3] == 1 ? "n" : "");
            }
            case "canchi": {
                int[] l = LunarCalendar.solarToLunar(cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.YEAR));
                return LunarCalendar.canChiYear(l[2]);
            }
            default: return "";
        }
    }
}
