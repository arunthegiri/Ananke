package com.stocktracker.marketdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stocktracker.instrument.InstrumentKey;
import com.stocktracker.marketdata.alpaca.AlpacaMarketDataDto;
import com.stocktracker.marketdata.alpaca.AlpacaOptionsProviderTestAccess;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Alpaca wire-format parsing and mapping, against payloads shaped like the documented responses. */
class AlpacaMarketDataTest {

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void cryptoVolumeIsFractional() throws Exception {
        var res = json.readValue("""
                {"bars":{"BTC/USD":[{"t":"2022-05-27T10:18:00Z","o":28999,"h":29003,"l":28999,
                  "c":29003,"v":0.01,"n":4,"vw":29001}]},"next_page_token":null}
                """, AlpacaMarketDataDto.CryptoBarsResponse.class);
        var bar = res.bars().get("BTC/USD").get(0);
        assertThat(bar.volume()).isEqualByComparingTo("0.01");
        assertThat(bar.time()).isEqualTo(Instant.parse("2022-05-27T10:18:00Z"));
        assertThat(res.nextPageToken()).isNull();
    }

    @Test
    void optionContractsParseDecimalStrings() throws Exception {
        var res = json.readValue("""
                {"option_contracts":[{"id":"x","symbol":"AAPL261218C00200000","expiration_date":"2026-12-18",
                  "type":"call","strike_price":"200","multiplier":"100","open_interest":"1234",
                  "close_price":"12.5","unknown_new_field":true}],"next_page_token":"abc+/="}
                """, AlpacaMarketDataDto.OptionContractsResponse.class);
        var c = res.optionContracts().get(0);
        assertThat(c.expirationDate()).isEqualTo(LocalDate.of(2026, 12, 18));
        assertThat(c.strikePrice()).isEqualByComparingTo("200");
        assertThat(c.openInterest()).isEqualTo(1234L);
        assertThat(c.closePrice()).isEqualByComparingTo("12.5");
    }

    @Test
    void snapshotMapsToQuoteAndZerosBecomeNull() throws Exception {
        var res = json.readValue("""
                {"snapshots":{"AAPL261218C00200000":{
                  "greeks":{"delta":0.55,"gamma":0.02,"rho":0.1,"theta":-0.05,"vega":0.3},
                  "impliedVolatility":0.27,
                  "latestQuote":{"t":"2026-11-14T15:00:00Z","bx":"C","bp":0,"bs":0,"ap":12.4,"as":10,"ax":"C","c":"A"},
                  "latestTrade":{"t":"2026-11-14T14:59:00Z","x":"C","p":12.1,"s":1,"c":"I"},
                  "dailyBar":{"t":"2026-11-14T05:00:00Z","o":12,"h":12.5,"l":11.9,"c":12.1,"v":321,"n":20,"vw":12.2},
                  "prevDailyBar":{"t":"2026-11-13T05:00:00Z","o":11,"h":12,"l":11,"c":11.8,"v":100,"n":9,"vw":11.5}
                }},"next_page_token":null}
                """, AlpacaMarketDataDto.OptionSnapshotsResponse.class);
        var key = (InstrumentKey.Option) InstrumentKey.parse("AAPL261218C00200000").orElseThrow();
        var contract = new AlpacaMarketDataDto.OptionContract("AAPL261218C00200000",
                LocalDate.of(2026, 12, 18), "call", new BigDecimal("200"), 1234L, new BigDecimal("11.7"));

        MarketData.OptionQuote q = AlpacaOptionsProviderTestAccess.toQuote(
                key, res.snapshots().get("AAPL261218C00200000"), contract);

        assertThat(q.bid()).as("Alpaca's 0 bid means no bid").isNull();
        assertThat(q.ask()).isEqualTo(12.4);
        assertThat(q.last()).isEqualTo(12.1);
        assertThat(q.volume()).isEqualTo(321L);
        assertThat(q.openInterest()).isEqualTo(1234L);
        assertThat(q.prevClose()).as("prevDailyBar wins over contract close").isEqualTo(11.8);
        assertThat(q.impliedVolatility()).isEqualTo(0.27);
        assertThat(q.delta()).isEqualTo(0.55);
        assertThat(q.right()).isEqualTo('C');
        assertThat(q.strike()).isEqualByComparingTo("200");
    }

    @Test
    void contractWithoutSnapshotStillMaps() {
        var key = (InstrumentKey.Option) InstrumentKey.parse("AAPL261218P00150000").orElseThrow();
        var contract = new AlpacaMarketDataDto.OptionContract("AAPL261218P00150000",
                LocalDate.of(2026, 12, 18), "put", new BigDecimal("150"), 50L, new BigDecimal("1.25"));
        MarketData.OptionQuote q = AlpacaOptionsProviderTestAccess.toQuote(key, null, contract);
        assertThat(q.bid()).isNull();
        assertThat(q.impliedVolatility()).isNull();
        assertThat(q.openInterest()).isEqualTo(50L);
        assertThat(q.prevClose()).isEqualTo(1.25);
    }

    @Test
    void pageTokensAreStrictlyEncoded() {
        URI uri = AlpacaOptionsProviderTestAccess.uri("https://data.alpaca.markets", "/v1beta3/crypto/us/bars",
                "symbols", "BTC/USD", "page_token", "ab+c/d=", "skipped", null);
        assertThat(uri.toString())
                .startsWith("https://data.alpaca.markets/v1beta3/crypto/us/bars?")
                .contains("page_token=ab%2Bc%2Fd%3D")
                .doesNotContain("skipped");
    }
}
