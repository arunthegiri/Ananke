package com.stocktracker.marketdata.alpaca;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Alpaca crypto + options response shapes (v1beta3 crypto bars, v1beta1 option
 * snapshots, v2 option contracts). Records, unknown fields ignored, so an added
 * Alpaca field never breaks deserialization.
 */
public final class AlpacaMarketDataDto {

    private AlpacaMarketDataDto() {}

    /** Crypto bar: unlike stock bars, {@code v} is fractional. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CryptoBar(
            @JsonProperty("t") Instant time,
            @JsonProperty("o") BigDecimal open,
            @JsonProperty("h") BigDecimal high,
            @JsonProperty("l") BigDecimal low,
            @JsonProperty("c") BigDecimal close,
            @JsonProperty("v") BigDecimal volume,
            @JsonProperty("vw") BigDecimal vwap,
            @JsonProperty("n") Long tradeCount) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CryptoBarsResponse(
            Map<String, List<CryptoBar>> bars,
            @JsonProperty("next_page_token") String nextPageToken) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Greeks(Double delta, Double gamma, Double rho, Double theta, Double vega) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OptionQuote(
            @JsonProperty("t") Instant time,
            @JsonProperty("bp") Double bidPrice,
            @JsonProperty("bs") Long bidSize,
            @JsonProperty("ap") Double askPrice,
            @JsonProperty("as") Long askSize) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OptionTrade(
            @JsonProperty("t") Instant time,
            @JsonProperty("p") Double price,
            @JsonProperty("s") Long size) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OptionBar(
            @JsonProperty("t") Instant time,
            @JsonProperty("c") Double close,
            @JsonProperty("v") Long volume) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OptionSnapshot(
            Greeks greeks,
            Double impliedVolatility,
            OptionQuote latestQuote,
            OptionTrade latestTrade,
            OptionBar dailyBar,
            OptionBar prevDailyBar) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OptionSnapshotsResponse(
            Map<String, OptionSnapshot> snapshots,
            @JsonProperty("next_page_token") String nextPageToken) {}

    /** v2 option contract. Alpaca sends the numeric fields as decimal strings. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OptionContract(
            String symbol,
            @JsonProperty("expiration_date") LocalDate expirationDate,
            String type,
            @JsonProperty("strike_price") BigDecimal strikePrice,
            @JsonProperty("open_interest") Long openInterest,
            @JsonProperty("close_price") BigDecimal closePrice) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OptionContractsResponse(
            @JsonProperty("option_contracts") List<OptionContract> optionContracts,
            @JsonProperty("next_page_token") String nextPageToken) {}
}
