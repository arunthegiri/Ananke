package com.stocktracker.connectors;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Enforces each connector's {@link ConnectorSpec.RateLimit}s before Ananke calls upstream
 * (sliding log per limit). Short waits — a per-second limit like SEC's 10/s — are absorbed by
 * sleeping; anything longer fails fast with {@link ConnectorException.Kind#RATE_LIMITED} so a
 * panel or agent never hangs on a daily quota.
 *
 * <p>In memory: counts reset when the backend restarts. Upstream quotas do not, so a restart
 * can let through more calls than the provider allows — upstream 429s are still mapped.
 */
public class RateLimiter {

    /** Longest wait absorbed by sleeping instead of failing. */
    static final Duration MAX_WAIT = Duration.ofSeconds(2);

    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    private final Clock clock;
    private final Sleeper sleeper;
    private final Map<String, Deque<Instant>[]> logs = new HashMap<>();

    public RateLimiter(Clock clock, Sleeper sleeper) {
        this.clock   = clock;
        this.sleeper = sleeper;
    }

    public static RateLimiter system() {
        return new RateLimiter(Clock.systemUTC(), d -> Thread.sleep(d.toMillis()));
    }

    /** Blocks briefly or throws; on return one call has been counted against every limit. */
    public void acquire(ConnectorSpec spec) {
        List<ConnectorSpec.RateLimit> limits = spec.limits();
        if (limits.isEmpty()) return;
        Deque<Instant>[] log = logFor(spec.id(), limits.size());
        synchronized (log) {
            while (true) {
                Instant now = clock.instant();
                Duration wait = Duration.ZERO;
                ConnectorSpec.RateLimit blocking = null;
                for (int i = 0; i < limits.size(); i++) {
                    ConnectorSpec.RateLimit l = limits.get(i);
                    Deque<Instant> d = log[i];
                    while (!d.isEmpty() && !d.peekFirst().plus(l.window()).isAfter(now)) d.pollFirst();
                    if (d.size() >= l.permits()) {
                        Duration w = Duration.between(now, d.peekFirst().plus(l.window()));
                        if (w.compareTo(wait) > 0) {
                            wait = w;
                            blocking = l;
                        }
                    }
                }
                if (blocking == null) {
                    for (Deque<Instant> d : log) d.addLast(now);
                    return;
                }
                if (wait.compareTo(MAX_WAIT) > 0) {
                    throw new ConnectorException(ConnectorException.Kind.RATE_LIMITED,
                            spec.name() + ": Ananke's limit of " + blocking.permits() + " call"
                                    + (blocking.permits() == 1 ? "" : "s") + " per " + human(blocking.window())
                                    + " is used up. Next call allowed in " + human(wait) + ".");
                }
                try {
                    sleeper.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ConnectorException(ConnectorException.Kind.RATE_LIMITED, spec.name() + ": interrupted while rate-limited.");
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private synchronized Deque<Instant>[] logFor(String id, int n) {
        return logs.computeIfAbsent(id, k -> {
            Deque<Instant>[] a = new Deque[n];
            for (int i = 0; i < n; i++) a[i] = new ArrayDeque<>();
            return a;
        });
    }

    static String human(Duration d) {
        long s = Math.max(1, (d.toMillis() + 999) / 1000);
        if (d.equals(Duration.ofDays(1))) return "day";
        if (d.equals(Duration.ofHours(1))) return "hour";
        if (d.equals(Duration.ofMinutes(1))) return "minute";
        if (d.equals(Duration.ofSeconds(1))) return "second";
        if (s < 120) return s + "s";
        if (s < 7200) return (s / 60) + " min";
        return (s / 3600) + " h";
    }
}
