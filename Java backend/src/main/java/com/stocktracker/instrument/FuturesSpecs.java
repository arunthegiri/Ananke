package com.stocktracker.instrument;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * Contract specs for the futures roots Ananke knows how to account for.
 *
 * <p>Deliberately a closed list: a wrong multiplier silently turns every P/L,
 * margin and position-size number into nonsense, so {@code InstrumentService}
 * refuses to register a futures root that has no entry here rather than
 * defaulting to 1. Add a root by adding its exchange-published spec.
 *
 * @param exchange   listing exchange used in the canonical key
 * @param multiplier dollars per 1.0 point of price
 * @param tickSize   minimum price increment
 */
public record FuturesSpecs(String name, String exchange, BigDecimal multiplier, BigDecimal tickSize) {

    private static FuturesSpecs spec(String name, String exchange, String multiplier, String tick) {
        return new FuturesSpecs(name, exchange, new BigDecimal(multiplier), new BigDecimal(tick));
    }

    private static final Map<String, FuturesSpecs> BY_ROOT = Map.ofEntries(
            // Equity index (CME / CBOT)
            Map.entry("ES",  spec("E-mini S&P 500",          "CME",   "50",     "0.25")),
            Map.entry("MES", spec("Micro E-mini S&P 500",    "CME",   "5",      "0.25")),
            Map.entry("NQ",  spec("E-mini Nasdaq-100",       "CME",   "20",     "0.25")),
            Map.entry("MNQ", spec("Micro E-mini Nasdaq-100", "CME",   "2",      "0.25")),
            Map.entry("RTY", spec("E-mini Russell 2000",     "CME",   "50",     "0.1")),
            Map.entry("M2K", spec("Micro E-mini Russell 2000","CME",  "5",      "0.1")),
            Map.entry("YM",  spec("E-mini Dow",              "CBOT",  "5",      "1")),
            Map.entry("MYM", spec("Micro E-mini Dow",        "CBOT",  "0.5",    "1")),
            // Rates (CBOT)
            Map.entry("ZN",  spec("10-Year T-Note",          "CBOT",  "1000",   "0.015625")),
            Map.entry("ZB",  spec("U.S. Treasury Bond",      "CBOT",  "1000",   "0.03125")),
            Map.entry("ZF",  spec("5-Year T-Note",           "CBOT",  "1000",   "0.0078125")),
            // Energy (NYMEX)
            Map.entry("CL",  spec("Crude Oil",               "NYMEX", "1000",   "0.01")),
            Map.entry("MCL", spec("Micro Crude Oil",         "NYMEX", "100",    "0.01")),
            Map.entry("NG",  spec("Henry Hub Natural Gas",   "NYMEX", "10000",  "0.001")),
            // Metals (COMEX)
            Map.entry("GC",  spec("Gold",                    "COMEX", "100",    "0.1")),
            Map.entry("MGC", spec("Micro Gold",              "COMEX", "10",     "0.1")),
            Map.entry("SI",  spec("Silver",                  "COMEX", "5000",   "0.005")),
            // FX (CME)
            Map.entry("6E",  spec("Euro FX",                 "CME",   "125000", "0.00005")),
            Map.entry("6J",  spec("Japanese Yen",            "CME",   "12500000","0.0000005"))
    );

    public static Optional<FuturesSpecs> forRoot(String root) {
        return Optional.ofNullable(BY_ROOT.get(root));
    }
}
