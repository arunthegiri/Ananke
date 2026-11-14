package com.stocktracker.connectors;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Small LRU cache of connector results, keyed by connector + tool + arguments. Quota-limited
 * sources (World Monitor Pro: 50 calls/day) are only usable from panels because of this.
 */
public class ResponseCache {

    public record Entry(Object data, Instant observedAt, Instant expiresAt) { }

    private final Clock clock;
    private final LinkedHashMap<String, Entry> map;

    public ResponseCache(Clock clock, int maxEntries) {
        this.clock = clock;
        this.map = new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > maxEntries;
            }
        };
    }

    public synchronized Optional<Entry> get(String key) {
        Entry e = map.get(key);
        if (e == null) return Optional.empty();
        if (!e.expiresAt().isAfter(clock.instant())) {
            map.remove(key);
            return Optional.empty();
        }
        return Optional.of(e);
    }

    public synchronized void put(String key, Object data, Instant observedAt, Duration ttl) {
        if (ttl.isZero() || ttl.isNegative()) return;
        map.put(key, new Entry(data, observedAt, observedAt.plus(ttl)));
    }

    /** Drops everything cached for one connector (on unlink, or when its key changes). */
    public synchronized void evictConnector(String connectorId) {
        map.keySet().removeIf(k -> k.startsWith(connectorId + "\u0000"));
    }
}
