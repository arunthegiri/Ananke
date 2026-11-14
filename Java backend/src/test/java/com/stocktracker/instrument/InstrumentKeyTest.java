package com.stocktracker.instrument;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Mirrors tests/instruments.test.mjs — a key must parse identically on both sides. */
class InstrumentKeyTest {

    @ParameterizedTest
    @CsvSource({
            "AAPL,                Equity",
            "BRK.B,               Equity",
            "BF-B,                Equity",
            "^SPX,                Index",
            "AAPL261218C00200000, Option",
            "ESZ26@CME,           Future",
            "MESZ26@CME,          Future",
            "6EH27@CME,           Future",
            "ES1!,                ContinuousFuture",
            "NQ2!@CME,            ContinuousFuture",
            "BTC/USD,             Crypto",
    })
    void shapeDeterminesKind(String key, String kind) {
        assertThat(InstrumentKey.parse(key)).get()
                .extracting(k -> k.getClass().getSimpleName()).isEqualTo(kind);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ESZ26", "", "   ", "aapl!!", "AAPL261318C00200000", "AAPL270230C00200000", "BTC/", "/USD"})
    void rejectsInvalidShapes(String key) {
        assertThat(InstrumentKey.parse(key)).isEmpty();
    }

    @Test
    void nullIsRejected() {
        assertThat(InstrumentKey.parse(null)).isEmpty();
        assertThatThrownBy(() -> InstrumentKey.parseOrThrow("ESZ26"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalizes() {
        assertThat(InstrumentKey.normalize("  aapl ")).isEqualTo("AAPL");
        assertThat(InstrumentKey.normalize("btc/usd")).isEqualTo("BTC/USD");
        assertThat(InstrumentKey.normalize("AAPL  261218C00200000")).isEqualTo("AAPL261218C00200000");
        assertThat(InstrumentKey.normalize("SPY   270115P00450500")).isEqualTo("SPY270115P00450500");
    }

    @Test
    void extractsOptionFields() {
        var o = (InstrumentKey.Option) InstrumentKey.parse("AAPL  261218C00200000").orElseThrow();
        assertThat(o.key()).isEqualTo("AAPL261218C00200000");
        assertThat(o.underlying()).isEqualTo("AAPL");
        assertThat(o.expiry()).isEqualTo(LocalDate.of(2026, 12, 18));
        assertThat(o.right()).isEqualTo('C');
        assertThat(o.strike()).isEqualByComparingTo("200");

        var p = (InstrumentKey.Option) InstrumentKey.parse("SPY270115P00450500").orElseThrow();
        assertThat(p.strike()).isEqualByComparingTo("450.5");
    }

    @Test
    void futuresRootIsNonGreedy() {
        var mes = (InstrumentKey.Future) InstrumentKey.parse("MESZ26@CME").orElseThrow();
        assertThat(mes.root()).isEqualTo("MES");
        assertThat(mes.month()).isEqualTo(12);
        assertThat(mes.year()).isEqualTo(2026);
        assertThat(mes.exchange()).isEqualTo("CME");
        assertThat(mes.contractMonth()).isEqualTo("2026-12");

        var nq = (InstrumentKey.Future) InstrumentKey.parse("NQH27@CME").orElseThrow();
        assertThat(nq.root()).isEqualTo("NQ");
        assertThat(nq.month()).isEqualTo(3);
        assertThat(nq.year()).isEqualTo(2027);
        assertThat(((InstrumentKey.Future) InstrumentKey.parse("GCQ26@COMEX").orElseThrow()).root()).isEqualTo("GC");
        assertThat(((InstrumentKey.Future) InstrumentKey.parse("6EH27@CME").orElseThrow()).root()).isEqualTo("6E");
    }

    @Test
    void continuousKeyDropsExchange() {
        var c = (InstrumentKey.ContinuousFuture) InstrumentKey.parse("nq2!@cme").orElseThrow();
        assertThat(c.key()).isEqualTo("NQ2!");
        assertThat(c.root()).isEqualTo("NQ");
        assertThat(c.rank()).isEqualTo(2);
        assertThat(c.exchange()).isEqualTo("CME");
        assertThat(InstrumentKey.parse("ES1!").orElseThrow().key()).isEqualTo("ES1!");
    }

    @Test
    void crypto() {
        var c = (InstrumentKey.Crypto) InstrumentKey.parse("eth/usdt").orElseThrow();
        assertThat(c.key()).isEqualTo("ETH/USDT");
        assertThat(c.base()).isEqualTo("ETH");
        assertThat(c.quote()).isEqualTo("USDT");
    }

    @Test
    void buildersRoundTrip() {
        assertThat(InstrumentKey.optionKey("aapl", LocalDate.of(2026, 12, 18), 'C', new BigDecimal("200")))
                .isEqualTo("AAPL261218C00200000");
        assertThat(InstrumentKey.optionKey("SPY", LocalDate.of(2027, 1, 15), 'P', new BigDecimal("450.5")))
                .isEqualTo("SPY270115P00450500");
        assertThatThrownBy(() -> InstrumentKey.optionKey("SPY", LocalDate.of(2027, 1, 15), 'P', new BigDecimal("1e9")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(InstrumentKey.futureKey("es", 12, 2026, "cme")).isEqualTo("ESZ26@CME");
        assertThatThrownBy(() -> InstrumentKey.futureKey("ES", 13, 2026, "CME"))
                .isInstanceOf(IllegalArgumentException.class);
        for (int month = 1; month <= 12; month++) {
            var f = (InstrumentKey.Future) InstrumentKey.parse(InstrumentKey.futureKey("CL", month, 2027, "NYMEX")).orElseThrow();
            assertThat(f.month()).isEqualTo(month);
            assertThat(f.year()).isEqualTo(2027);
        }
    }
}
