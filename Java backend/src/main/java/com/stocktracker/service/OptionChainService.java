package com.stocktracker.service;

import com.stocktracker.dto.ApiDto;
import com.stocktracker.instrument.AssetClass;
import com.stocktracker.marketdata.MarketData;
import com.stocktracker.marketdata.MarketDataRouter;
import com.stocktracker.model.Instrument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Option chains (multi-asset P2, issue #8).
 *
 * <p>Chains are fetched on demand and cached briefly, never stored: a single
 * SPY expiry is hundreds of contracts and the full SPY surface is thousands,
 * re-quoted every second. Storing that would be the database's whole job.
 */
@Service
public class OptionChainService {

    static final Duration CHAIN_TTL = Duration.ofSeconds(15);
    static final Duration EXPIRATIONS_TTL = Duration.ofMinutes(10);

    private record Cached<T>(Instant at, T value) {}

    private final MarketDataRouter router;
    private final InstrumentService instruments;
    private final Clock clock;
    private final ConcurrentHashMap<String, Cached<?>> cache = new ConcurrentHashMap<>();

    @Autowired
    public OptionChainService(MarketDataRouter router, InstrumentService instruments) {
        this(router, instruments, Clock.systemUTC());
    }

    OptionChainService(MarketDataRouter router, InstrumentService instruments, Clock clock) {
        this.router = router;
        this.instruments = instruments;
        this.clock = clock;
    }

    public ApiDto.OptionExpirationsResponse expirations(String underlyingKey) {
        String underlying = optionableUnderlying(underlyingKey);
        MarketData.OptionChainProvider p = router.chains();
        List<LocalDate> dates = cached("exp:" + underlying, EXPIRATIONS_TTL, () -> p.expirations(underlying));
        return new ApiDto.OptionExpirationsResponse(underlying, p.name(),
                dates.stream().map(LocalDate::toString).toList());
    }

    public ApiDto.OptionChainResponse chain(String underlyingKey, LocalDate expiry) {
        String underlying = optionableUnderlying(underlyingKey);
        MarketData.OptionChainProvider p = router.chains();
        MarketData.OptionChain c = cached("chain:" + underlying + ":" + expiry, CHAIN_TTL,
                () -> p.chain(underlying, expiry));
        return new ApiDto.OptionChainResponse(c.underlying(), c.expiry().toString(), c.underlyingPrice(),
                p.name(), c.feed(), c.mode().name(), c.asOf(),
                c.contracts().stream().map(q -> new ApiDto.OptionContractResponse(
                        q.key(), String.valueOf(q.right()), q.strike(),
                        q.bid(), q.ask(), q.bidSize(), q.askSize(), q.last(),
                        q.volume(), q.openInterest(), q.prevClose(),
                        q.impliedVolatility(), q.delta(), q.gamma(), q.theta(), q.vega(), q.rho(),
                        q.quoteTime())).toList());
    }

    /** Equity/ETF underlyings only: Alpaca lists no index or futures options. */
    private String optionableUnderlying(String key) {
        Instrument inst = instruments.resolve(key);
        if (inst.getAssetClass() != AssetClass.EQUITY && inst.getAssetClass() != AssetClass.ETF) {
            throw new UnsupportedOperationException("Option chains are available for stocks and ETFs only, not "
                    + inst.getAssetClass() + " (" + inst.getKey() + ")");
        }
        return inst.getSymbol();
    }

    @SuppressWarnings("unchecked")
    private <T> T cached(String key, Duration ttl, Supplier<T> load) {
        Instant now = clock.instant();
        Cached<?> hit = cache.get(key);
        if (hit != null && hit.at().plus(ttl).isAfter(now)) return (T) hit.value();
        T value = load.get();
        cache.put(key, new Cached<>(now, value));
        // Bounded by usage (one entry per viewed underlying/expiry); sweep stale ones as we go.
        cache.entrySet().removeIf(e -> e.getValue().at().plus(EXPIRATIONS_TTL).isBefore(now));
        return value;
    }
}
