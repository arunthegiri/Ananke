package com.stocktracker.connectors;

import java.time.Duration;
import java.util.List;

/**
 * What a connector is, independent of whether it is linked (Connectors roadmap §0, §1).
 * Everything here is static, defined in code, and safe to show in the UI.
 *
 * @param id          stable id, {@code [a-z0-9_]}; also the prefix of every tool it offers
 * @param attribution credit line the source requires; shown wherever its data is shown
 * @param fields      what the user supplies to link it; empty = keyless (linked by default)
 * @param limits      upstream rate limits Ananke enforces before calling, tightest first
 */
public record ConnectorSpec(
        String id,
        String name,
        Category category,
        Licence licence,
        String description,
        String attribution,
        String homepage,
        List<Field> fields,
        List<RateLimit> limits) {

    public ConnectorSpec {
        if (id == null || !id.matches("^[a-z0-9_]{2,32}$")) throw new IllegalArgumentException("Bad connector id: " + id);
        fields = fields == null ? List.of() : List.copyOf(fields);
        limits = limits == null ? List.of() : List.copyOf(limits);
    }

    public boolean keyless() {
        return fields.stream().noneMatch(f -> !f.optional());
    }

    public enum Category {
        FILINGS, MACRO, NEWS, SOCIAL, ALT_DATA, MARKET_DATA, OPTIONS_FLOW, GEOPOLITICS, PREDICTION_MARKETS
    }

    /**
     * How the data may be used (§0.3). Ananke never redistributes: the user links their own
     * account and Ananke acts as their client.
     */
    public enum Licence {
        /** Public-domain / government data, no account needed beyond an identity or free key. */
        FREE_PUBLIC,
        /** User's own key; the provider's self-serve terms are personal use only. */
        BYO_PERSONAL,
        /** User's own key; the provider's plan permits commercial (internal) use. */
        BYO_COMMERCIAL,
        /** AGPL project run as a separate service and called over its API (§0.4). */
        AGPL_SIDECAR
    }

    /**
     * One thing the user enters to link the connector.
     *
     * @param secret true = stored encrypted, never returned (only a last-four hint);
     *               false = still stored in the vault, but its value may be echoed back
     */
    public record Field(String key, String label, String help, boolean secret, boolean optional, String placeholder) {
        public Field {
            if (key == null || !key.matches("^[a-z0-9_]{1,32}$")) throw new IllegalArgumentException("Bad field key: " + key);
        }

        public static Field secret(String key, String label, String help) {
            return new Field(key, label, help, true, false, null);
        }

        public static Field text(String key, String label, String help, String placeholder) {
            return new Field(key, label, help, false, false, placeholder);
        }
    }

    /** At most {@code permits} upstream calls per {@code window}. */
    public record RateLimit(int permits, Duration window) {
        public static RateLimit perSecond(int n) { return new RateLimit(n, Duration.ofSeconds(1)); }
        public static RateLimit perMinute(int n) { return new RateLimit(n, Duration.ofMinutes(1)); }
        public static RateLimit perDay(int n)    { return new RateLimit(n, Duration.ofDays(1)); }
    }
}
