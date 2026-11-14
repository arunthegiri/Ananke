package com.stocktracker.marketdata.alpaca;

import com.stocktracker.config.AlpacaProperties;
import com.stocktracker.dto.AlpacaDto;
import com.stocktracker.instrument.InstrumentKey;
import com.stocktracker.marketdata.MarketData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Alpaca option chains.
 *
 * <ul>
 *   <li>Expirations + open interest: trading API {@code GET /v2/options/contracts}
 *       (paper host, same keys as market data).</li>
 *   <li>Quotes, IV and greeks: {@code GET /v1beta1/options/snapshots/{underlying}}.</li>
 * </ul>
 *
 * <p>Feed defaults to {@code indicative} — Alpaca's free feed with delayed trades
 * and modified quotes — so the provider reports DELAYED. Setting
 * {@code ALPACA_OPTIONS_FEED=opra} (paid OPRA subscription) switches to REALTIME;
 * displaying that to other users of a hosted Ananke additionally needs OPRA
 * redistribution licensing (issue #8).
 */
@Slf4j
@Component
public class AlpacaOptionsProvider implements MarketData.OptionChainProvider {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final int MAX_PAGES = 20;

    private final RestClient dataClient;
    private final RestClient brokerClient;
    private final String dataBase;
    private final String brokerBase;
    private final String feed;

    public AlpacaOptionsProvider(@Qualifier("alpacaRestClient") RestClient dataClient,
                                 @Qualifier("alpacaBrokerClient") RestClient brokerClient,
                                 AlpacaProperties props,
                                 @Value("${marketdata.options.feed:indicative}") String feed) {
        this.dataClient = dataClient;
        this.brokerClient = brokerClient;
        this.dataBase = props.getBaseUrl();
        this.brokerBase = props.getBrokerBaseUrl();
        this.feed = feed.trim().toLowerCase(Locale.ROOT);
    }

    @Override public String name() { return "alpaca-options"; }

    @Override
    public MarketData.DataMode mode() {
        return "opra".equals(feed) ? MarketData.DataMode.REALTIME : MarketData.DataMode.DELAYED;
    }

    @Override
    public List<LocalDate> expirations(String underlying) {
        LocalDate today = LocalDate.now(ET);
        TreeSet<LocalDate> dates = new TreeSet<>();
        for (AlpacaMarketDataDto.OptionContract c : contracts(underlying, AlpacaUris.params(
                "expiration_date_gte", today.toString(),
                // Without an upper bound Alpaca defaults to "the next weekend".
                "expiration_date_lte", today.plusYears(3).toString()))) {
            if (c.expirationDate() != null) dates.add(c.expirationDate());
        }
        return List.copyOf(dates);
    }

    @Override
    public MarketData.OptionChain chain(String underlying, LocalDate expiry) {
        Map<String, AlpacaMarketDataDto.OptionContract> contractsByKey = new HashMap<>();
        for (AlpacaMarketDataDto.OptionContract c : contracts(underlying,
                AlpacaUris.params("expiration_date", expiry.toString()))) {
            contractsByKey.put(c.symbol(), c);
        }

        Map<String, AlpacaMarketDataDto.OptionSnapshot> snapshots = snapshots(underlying, expiry);

        // Union: a listed contract with no quote yet still belongs in the chain.
        Set<String> keys = new TreeSet<>(contractsByKey.keySet());
        keys.addAll(snapshots.keySet());

        List<MarketData.OptionQuote> quotes = new ArrayList<>();
        for (String key : keys) {
            Optional<InstrumentKey> parsed = InstrumentKey.parse(key);
            if (parsed.isEmpty() || !(parsed.get() instanceof InstrumentKey.Option o)) continue;
            quotes.add(toQuote(o, snapshots.get(key), contractsByKey.get(key)));
        }
        quotes.sort(Comparator.comparing(MarketData.OptionQuote::strike)
                .thenComparing(MarketData.OptionQuote::right));

        return new MarketData.OptionChain(underlying, expiry, underlyingPrice(underlying),
                feed, mode(), Instant.now(), quotes);
    }

    // ── Alpaca calls ──────────────────────────────────────────────────────────

    private List<AlpacaMarketDataDto.OptionContract> contracts(String underlying, Map<String, Object> filters) {
        List<AlpacaMarketDataDto.OptionContract> out = new ArrayList<>();
        String pageToken = null;
        int pages = 0;
        do {
            Map<String, Object> params = AlpacaUris.params(
                    "underlying_symbols", underlying, "status", "active", "limit", 10_000);
            params.putAll(filters);
            params.put("page_token", pageToken);
            var res = brokerClient.get().uri(AlpacaUris.build(brokerBase, "/v2/options/contracts", params))
                    .retrieve().body(AlpacaMarketDataDto.OptionContractsResponse.class);
            if (res == null || res.optionContracts() == null) break;
            out.addAll(res.optionContracts());
            pageToken = res.nextPageToken();
        } while (pageToken != null && ++pages < MAX_PAGES);
        return out;
    }

    private Map<String, AlpacaMarketDataDto.OptionSnapshot> snapshots(String underlying, LocalDate expiry) {
        Map<String, AlpacaMarketDataDto.OptionSnapshot> out = new HashMap<>();
        String pageToken = null;
        int pages = 0;
        do {
            var uri = AlpacaUris.build(dataBase, "/v1beta1/options/snapshots/" + underlying, AlpacaUris.params(
                    "feed", feed, "expiration_date", expiry.toString(), "limit", 1000, "page_token", pageToken));
            var res = dataClient.get().uri(uri).retrieve().body(AlpacaMarketDataDto.OptionSnapshotsResponse.class);
            if (res == null || res.snapshots() == null) break;
            out.putAll(res.snapshots());
            pageToken = res.nextPageToken();
        } while (pageToken != null && ++pages < MAX_PAGES);
        return out;
    }

    /**
     * Spot from the single-symbol snapshot ({@code /v2/stocks/{symbol}/snapshot}),
     * which returns the snapshot object directly. (AlpacaClient.fetchSnapshots
     * expects a {"snapshots":…} wrapper the multi-symbol endpoint does not send.)
     */
    private Double underlyingPrice(String underlying) {
        try {
            AlpacaDto.Snapshot s = dataClient.get()
                    .uri(AlpacaUris.build(dataBase, "/v2/stocks/" + underlying + "/snapshot",
                            AlpacaUris.params("feed", "iex")))
                    .retrieve().body(AlpacaDto.Snapshot.class);
            if (s == null) return null;
            if (s.getLatestTrade() != null && s.getLatestTrade().getPrice() != null) {
                return s.getLatestTrade().getPrice().doubleValue();
            }
            return s.getDailyBar() != null && s.getDailyBar().getClose() != null
                    ? s.getDailyBar().getClose().doubleValue() : null;
        } catch (Exception e) {
            // The chain is still useful without a spot price; the UI just can't mark ATM.
            log.warn("Underlying snapshot for {} failed: {}", underlying, e.getMessage());
            return null;
        }
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    static MarketData.OptionQuote toQuote(InstrumentKey.Option o,
                                          AlpacaMarketDataDto.OptionSnapshot snap,
                                          AlpacaMarketDataDto.OptionContract contract) {
        var q = snap == null ? null : snap.latestQuote();
        var t = snap == null ? null : snap.latestTrade();
        var g = snap == null ? null : snap.greeks();
        Long volume = snap != null && snap.dailyBar() != null ? snap.dailyBar().volume() : null;
        Double prevClose = snap != null && snap.prevDailyBar() != null ? snap.prevDailyBar().close() : null;
        if (prevClose == null && contract != null && contract.closePrice() != null) {
            prevClose = contract.closePrice().doubleValue();
        }
        BigDecimal strike = o.strike();
        return new MarketData.OptionQuote(
                o.key(), o.right(), strike,
                positiveOrNull(q == null ? null : q.bidPrice()),
                positiveOrNull(q == null ? null : q.askPrice()),
                q == null ? null : q.bidSize(), q == null ? null : q.askSize(),
                t == null ? null : t.price(),
                volume,
                contract == null ? null : contract.openInterest(),
                prevClose,
                snap == null ? null : positiveOrNull(snap.impliedVolatility()),
                g == null ? null : g.delta(), g == null ? null : g.gamma(),
                g == null ? null : g.theta(), g == null ? null : g.vega(), g == null ? null : g.rho(),
                q == null ? null : q.time());
    }

    /** Alpaca reports "no bid" / "no IV" as 0; the UI must show a dash, not a price of zero. */
    private static Double positiveOrNull(Double v) {
        return v == null || v <= 0 ? null : v;
    }
}
