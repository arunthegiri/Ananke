package com.stocktracker.marketdata.alpaca;

import com.stocktracker.config.AlpacaProperties;
import com.stocktracker.instrument.AssetClass;
import com.stocktracker.marketdata.MarketData;
import com.stocktracker.model.Instrument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Alpaca crypto bars ({@code GET /v1beta3/crypto/us/bars}). Crypto data is
 * real-time on every Alpaca plan, so this provider reports REALTIME.
 */
@Slf4j
@Component
public class AlpacaCryptoBarProvider implements MarketData.BarProvider {

    private static final int PAGE_LIMIT = 10_000;
    /**
     * Hard stop so a bad range can't page forever. 14 × 10k 1-minute bars ≈ 97
     * days, which covers the whole 90-day retention window BarService clamps to.
     */
    private static final int MAX_PAGES = 14;

    private final RestClient client;
    private final String baseUrl;

    public AlpacaCryptoBarProvider(@Qualifier("alpacaRestClient") RestClient alpacaRestClient,
                                   AlpacaProperties props) {
        this.client = alpacaRestClient;
        this.baseUrl = props.getBaseUrl();
    }

    @Override public String name() { return "alpaca-crypto"; }
    @Override public Set<AssetClass> assetClasses() { return Set.of(AssetClass.CRYPTO); }
    @Override public MarketData.DataMode mode() { return MarketData.DataMode.REALTIME; }

    @Override
    public List<MarketData.Bar> minuteBars(Instrument instrument, Instant from, Instant to) {
        String symbol = instrument.getKey(); // canonical crypto key == Alpaca symbol ("BTC/USD")
        List<MarketData.Bar> out = new ArrayList<>();
        String pageToken = null;
        int pages = 0;
        do {
            var uri = AlpacaUris.build(baseUrl, "/v1beta3/crypto/us/bars", AlpacaUris.params(
                    "symbols", symbol, "timeframe", "1Min",
                    "start", from.toString(), "end", to.toString(),
                    "limit", PAGE_LIMIT, "sort", "asc", "page_token", pageToken));

            AlpacaMarketDataDto.CryptoBarsResponse res = client.get().uri(uri).retrieve()
                    .body(AlpacaMarketDataDto.CryptoBarsResponse.class);
            if (res == null || res.bars() == null) break;

            for (AlpacaMarketDataDto.CryptoBar bar : res.bars().getOrDefault(symbol, List.of())) {
                out.add(new MarketData.Bar(bar.time(), bar.open(), bar.high(), bar.low(), bar.close(),
                        bar.volume(), bar.vwap(), bar.tradeCount()));
            }
            pageToken = res.nextPageToken();
        } while (pageToken != null && ++pages < MAX_PAGES);

        if (pageToken != null) {
            log.warn("Crypto bars for {} truncated at {} pages ({} bars) [{} -> {}]",
                    symbol, MAX_PAGES, out.size(), from, to);
        }
        return out;
    }
}
