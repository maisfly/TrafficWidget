package com.example.trafficwidget;

import java.util.Locale;

/** Formats byte counts exactly the way the widget displays them. */
final class StatsFormat {

    private static final double MB = 1024d * 1024d;

    static final class Parts {
        final String number;
        final String unit;

        Parts(String number, String unit) {
            this.number = number;
            this.unit = unit;
        }
    }

    private StatsFormat() {
    }

    static Parts parts(long bytes) {
        double mb = Math.max(0L, bytes) / MB;
        if (mb >= 1024d) {
            return new Parts(String.format(Locale.CHINA, "%.1f", mb / 1024d), "GB");
        }
        return new Parts(String.format(Locale.CHINA, "%.1f", mb), "MB");
    }

    static String number(long bytes) {
        return parts(bytes).number;
    }

    static String unit(long bytes) {
        return parts(bytes).unit;
    }

    /** Example: {@code 35.7MB} (the compact style used by the today label). */
    static String compact(long bytes) {
        Parts p = parts(bytes);
        return p.number + p.unit;
    }

    /** Example: {@code 243.9 MB} (used in the setup screen). */
    static String spaced(long bytes) {
        Parts p = parts(bytes);
        return p.number + " " + p.unit;
    }
}
