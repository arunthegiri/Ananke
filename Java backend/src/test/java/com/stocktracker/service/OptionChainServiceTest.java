package com.stocktracker.service;

import com.stocktracker.instrument.AssetClass;
import com.stocktracker.instrument.SessionCalendar;
import com.stocktracker.marketdata.MarketData;
import com.stocktracker.marketdata.MarketDataRouter;
import com.stocktracker.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OptionChainServiceTest {

    private static Instrument inst(String key, AssetClass ac) {
        return Instrument.builder().id(1L).key(key).symbol(key.replace("^", "")).assetClass(ac)
                .sessionCalendar(SessionCalendar.forAssetClass(ac)).build();
    }

    /** A provider that counts calls, so the cache is observable. */
    private static final class CountingProvider implements MarketData.OptionChainProvider {
        final AtomicInteger chainCalls = new AtomicInteger();
        @Override public String name() { return "fake"; }
        @Override public MarketData.DataMode mode() { return MarketData.DataMode.DELAYED; }
        @Override public List<LocalDate> expirations(String u) { return List.of(LocalDate.of(2026, 12, 18)); }
        @Override public MarketData.OptionChain chain(String u, LocalDate e) {
            chainCalls.incrementAndGet();
            return new MarketData.OptionChain(u, e, 201.5, "indicative", MarketData.DataMode.DELAYED,
                    Instant.EPOCH, List.of(new MarketData.OptionQuote("AAPL261218C00200000", 'C',
                    new BigDecimal("200"), 12.0, 12.4, 5L, 10L, 12.1, 321L, 1234L, 11.8,
                    0.27, 0.55, 0.02, -0.05, 0.3, 0.1, Instant.EPOCH)));
        }
    }

    private final CountingProvider provider = new CountingProvider();
    private final InstrumentService instruments = mock(InstrumentService.class);
    private final MutableClock clock = new MutableClock();
    private final OptionChainService service = new OptionChainService(
            new MarketDataRouter(List.of(), List.of(provider)), instruments, clock);

    @Test
    void chainMapsAndCachesForTtl() {
        when(instruments.resolve(anyString())).thenReturn(inst("AAPL", AssetClass.EQUITY));
        var r = service.chain("AAPL", LocalDate.of(2026, 12, 18));
        assertThat(r.mode()).isEqualTo("DELAYED");
        assertThat(r.feed()).isEqualTo("indicative");
        assertThat(r.underlyingPrice()).isEqualTo(201.5);
        assertThat(r.contracts()).singleElement().satisfies(c -> {
            assertThat(c.right()).isEqualTo("C");
            assertThat(c.iv()).isEqualTo(0.27);
        });

        service.chain("AAPL", LocalDate.of(2026, 12, 18));
        assertThat(provider.chainCalls).as("second call within TTL is cached").hasValue(1);

        clock.advanceSeconds(16);
        service.chain("AAPL", LocalDate.of(2026, 12, 18));
        assertThat(provider.chainCalls).as("refetched after TTL").hasValue(2);
    }

    @Test
    void nonEquityUnderlyingIsRefused() {
        when(instruments.resolve(anyString())).thenReturn(inst("BTC/USD", AssetClass.CRYPTO));
        assertThatThrownBy(() -> service.expirations("BTC/USD"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("stocks and ETFs only");
    }

    @Test
    void expirationsAreIsoDates() {
        when(instruments.resolve(anyString())).thenReturn(inst("AAPL", AssetClass.EQUITY));
        assertThat(service.expirations("AAPL").expirations()).containsExactly("2026-12-18");
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-14T15:00:00Z");
        void advanceSeconds(long s) { now = now.plusSeconds(s); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
