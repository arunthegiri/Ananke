package com.stocktracker.instrument;

/**
 * Trading-hours calendar governing an instrument. The schedules themselves live
 * in the frontend's src/lib/session-calendar.ts; the backend only records which
 * one applies.
 */
public enum SessionCalendar {
    US_EQUITY,
    CME_GLOBEX,
    CRYPTO_24_7;

    public static SessionCalendar forAssetClass(AssetClass assetClass) {
        return switch (assetClass) {
            case FUTURE, FUTURE_OPTION -> CME_GLOBEX;
            case CRYPTO -> CRYPTO_24_7;
            default -> US_EQUITY;
        };
    }
}
