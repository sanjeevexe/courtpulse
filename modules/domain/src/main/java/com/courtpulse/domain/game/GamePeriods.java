package com.courtpulse.domain.game;

/** NBA period rules: four 12-minute quarters, then any number of 5-minute overtimes (bounded here). */
public final class GamePeriods {
    public static final int REGULATION_PERIODS = 4;
    /** Four quarters plus six overtimes, the longest NBA game on record. */
    public static final int MAXIMUM_PERIOD = 10;
    public static final long REGULATION_PERIOD_MILLIS = 12 * 60 * 1_000L;
    public static final long OVERTIME_PERIOD_MILLIS = 5 * 60 * 1_000L;

    private GamePeriods() {}

    public static boolean isOvertime(int period) {
        return period > REGULATION_PERIODS;
    }

    public static long maximumClockMillis(int period) {
        if (period < 1 || period > MAXIMUM_PERIOD) {
            throw new IllegalArgumentException("period must be between 1 and " + MAXIMUM_PERIOD);
        }
        return isOvertime(period) ? OVERTIME_PERIOD_MILLIS : REGULATION_PERIOD_MILLIS;
    }

    /** Human label used in alert titles: Q1..Q4, OT1..OT6. */
    public static String label(int period) {
        return isOvertime(period) ? "OT" + (period - REGULATION_PERIODS) : "Q" + period;
    }
}
