package com.stocktracker.instrument;

/** Asset classes Ananke models. Mirrors {@code AssetClass} in src/lib/instruments.ts. */
public enum AssetClass {
    EQUITY,
    ETF,
    INDEX,
    OPTION,
    FUTURE,
    FUTURE_OPTION,
    CRYPTO,
    EVENT_CONTRACT
}
