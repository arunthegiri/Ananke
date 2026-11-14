package com.stocktracker.marketdata;

import com.stocktracker.instrument.AssetClass;
import com.stocktracker.model.Instrument;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Market-data provider contracts (multi-asset P2, issue #8).
 *
 * <p>Providers are swappable per asset class so the data source is a config
 * decision, not a code one — which matters because hosted real-time display of
 * OPRA/CME data needs licensing that a delayed source does not.
 */
public final class MarketData {

    private MarketData() {}

    /** Whether a provider's data may be shown as live. Drives the UI's delayed label. */
    public enum DataMode { REALTIME, DELAYED }

    /** One OHLCV bar. Volume is decimal because crypto volume is fractional. */
    public record Bar(Instant time, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
                      BigDecimal volume, BigDecimal vwap, Long tradeCount) {}

    /** Historical 1-minute bars for some asset classes. */
    public interface BarProvider {
        String name();
        Set<AssetClass> assetClasses();
        DataMode mode();
        /** 1-minute bars in [from, to], ascending. Empty when the venue has none. */
        List<Bar> minuteBars(Instrument instrument, Instant from, Instant to);
    }

    /** One option contract in a chain, with the quote and model values for it. */
    public record OptionQuote(
            String key, char right, BigDecimal strike,
            Double bid, Double ask, Long bidSize, Long askSize, Double last,
            Long volume, Long openInterest, Double prevClose,
            Double impliedVolatility, Double delta, Double gamma, Double theta, Double vega, Double rho,
            Instant quoteTime) {}

    public record OptionChain(String underlying, LocalDate expiry, Double underlyingPrice,
                              String feed, DataMode mode, Instant asOf, List<OptionQuote> contracts) {}

    /** Option chains for equity / ETF underlyings. */
    public interface OptionChainProvider {
        String name();
        DataMode mode();
        /** Listed expirations from today on, ascending. */
        List<LocalDate> expirations(String underlying);
        OptionChain chain(String underlying, LocalDate expiry);
    }
}
