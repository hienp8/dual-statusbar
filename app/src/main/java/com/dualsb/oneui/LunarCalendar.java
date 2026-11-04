package com.dualsb.oneui;

/** Chuyển dương lịch -> âm lịch Việt Nam (múi giờ UTC+7), thuật toán của Hồ Ngọc Đức. */
public final class LunarCalendar {
    private LunarCalendar() {}

    private static final double TZ = 7.0;
    private static final String[] CAN = {"Giáp", "Ất", "Bính", "Đinh", "Mậu", "Kỷ", "Canh", "Tân", "Nhâm", "Quý"};
    private static final String[] CHI = {"Tý", "Sửu", "Dần", "Mão", "Thìn", "Tỵ", "Ngọ", "Mùi", "Thân", "Dậu", "Tuất", "Hợi"};

    private static int jd(int dd, int mm, int yy) {
        int a = (14 - mm) / 12;
        int y = yy + 4800 - a;
        int m = mm + 12 * a - 3;
        int j = dd + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - 32045;
        if (j < 2299161) j = dd + (153 * m + 2) / 5 + 365 * y + y / 4 - 32083;
        return j;
    }

    private static double newMoon(int k) {
        double T = k / 1236.85, T2 = T * T, T3 = T2 * T, dr = Math.PI / 180;
        double jd1 = 2415020.75933 + 29.53058868 * k + 0.0001178 * T2 - 0.000000155 * T3;
        jd1 += 0.00033 * Math.sin((166.56 + 132.87 * T - 0.009173 * T2) * dr);
        double M = 359.2242 + 29.10535608 * k - 0.0000333 * T2 - 0.00000347 * T3;
        double Mpr = 306.0253 + 385.81691806 * k + 0.0107306 * T2 + 0.00001236 * T3;
        double F = 21.2964 + 390.67050646 * k - 0.0016528 * T2 - 0.00000239 * T3;
        double c = (0.1734 - 0.000393 * T) * Math.sin(M * dr) + 0.0021 * Math.sin(2 * dr * M);
        c = c - 0.4068 * Math.sin(Mpr * dr) + 0.0161 * Math.sin(dr * 2 * Mpr);
        c = c - 0.0004 * Math.sin(dr * 3 * Mpr);
        c = c + 0.0104 * Math.sin(dr * 2 * F) - 0.0051 * Math.sin(dr * (M + Mpr));
        c = c - 0.0074 * Math.sin(dr * (M - Mpr)) + 0.0004 * Math.sin(dr * (2 * F + M));
        c = c - 0.0004 * Math.sin(dr * (2 * F - M)) - 0.0006 * Math.sin(dr * (2 * F + Mpr));
        c = c + 0.0010 * Math.sin(dr * (2 * F - Mpr)) + 0.0005 * Math.sin(dr * (2 * Mpr + M));
        double dt = T < -11
                ? 0.001 + 0.000839 * T + 0.0002261 * T2 - 0.00000845 * T3 - 0.000000081 * T * T3
                : -0.000278 + 0.000265 * T + 0.000262 * T2;
        return jd1 + c - dt;
    }

    private static double sunLongitude(double jdn) {
        double T = (jdn - 2451545.0) / 36525, T2 = T * T, dr = Math.PI / 180;
        double M = 357.52910 + 35999.05030 * T - 0.0001559 * T2 - 0.00000048 * T * T2;
        double L0 = 280.46645 + 36000.76983 * T + 0.0003032 * T2;
        double DL = (1.914600 - 0.004817 * T - 0.000014 * T2) * Math.sin(dr * M);
        DL += (0.019993 - 0.000101 * T) * Math.sin(dr * 2 * M) + 0.000290 * Math.sin(dr * 3 * M);
        double L = (L0 + DL) * dr;
        L -= Math.PI * 2 * Math.floor(L / (Math.PI * 2));
        return L;
    }

    private static int sunLong(int dayNumber) {
        return (int) Math.floor(sunLongitude(dayNumber - 0.5 - TZ / 24.0) / Math.PI * 6);
    }

    private static int newMoonDay(int k) {
        return (int) Math.floor(newMoon(k) + 0.5 + TZ / 24.0);
    }

    private static int lunarMonth11(int yy) {
        int off = jd(31, 12, yy) - 2415021;
        int k = (int) Math.floor(off / 29.530588853);
        int nm = newMoonDay(k);
        if (sunLong(nm) >= 9) nm = newMoonDay(k - 1);
        return nm;
    }

    private static int leapMonthOffset(int a11) {
        int k = (int) Math.floor((a11 - 2415021.076998695) / 29.530588853 + 0.5);
        int i = 1;
        int arc = sunLong(newMoonDay(k + i));
        int last;
        do {
            last = arc;
            i++;
            arc = sunLong(newMoonDay(k + i));
        } while (arc != last && i < 14);
        return i - 1;
    }

    /** @return {ngày, tháng, năm âm, 1 nếu tháng nhuận} */
    public static int[] solarToLunar(int dd, int mm, int yy) {
        int dayNumber = jd(dd, mm, yy);
        int k = (int) Math.floor((dayNumber - 2415021.076998695) / 29.530588853);
        int monthStart = newMoonDay(k + 1);
        if (monthStart > dayNumber) monthStart = newMoonDay(k);
        int a11 = lunarMonth11(yy), b11 = a11, lunarYear;
        if (a11 >= monthStart) {
            lunarYear = yy;
            a11 = lunarMonth11(yy - 1);
        } else {
            lunarYear = yy + 1;
            b11 = lunarMonth11(yy + 1);
        }
        int lunarDay = dayNumber - monthStart + 1;
        int diff = (int) Math.floor((monthStart - a11) / 29.0);
        int leap = 0, lunarMonth = diff + 11;
        if (b11 - a11 > 365) {
            int leapDiff = leapMonthOffset(a11);
            if (diff >= leapDiff) {
                lunarMonth = diff + 10;
                if (diff == leapDiff) leap = 1;
            }
        }
        if (lunarMonth > 12) lunarMonth -= 12;
        if (lunarMonth >= 11 && diff < 4) lunarYear -= 1;
        return new int[]{lunarDay, lunarMonth, lunarYear, leap};
    }

    public static String canChiYear(int lunarYear) {
        return CAN[((lunarYear + 6) % 10 + 10) % 10] + " " + CHI[((lunarYear + 8) % 12 + 12) % 12];
    }

    /**
     * fmt 0: "25/9 ÂL"   1: "25/9/2026 ÂL"   2: "Ngày 25 tháng 9 âm lịch"   3: "25/9 năm Bính Ngọ"
     * Tháng nhuận: thêm chữ "n" (dạng số) hoặc "nhuận" (dạng chữ).
     */
    public static String format(int[] l, int fmt) {
        String m = l[1] + (l[3] == 1 ? "n" : "");
        switch (fmt) {
            case 1: return l[0] + "/" + m + "/" + l[2] + " ÂL";
            case 2: return "Ngày " + l[0] + " tháng " + l[1] + (l[3] == 1 ? " nhuận" : "") + " âm lịch";
            case 3: return l[0] + "/" + m + " năm " + canChiYear(l[2]);
            case 4: return l[0] + "/" + m + " " + canChiYear(l[2]);
            default: return l[0] + "/" + m + " ÂL";
        }
    }
}
