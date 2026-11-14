package com.stocktracker.service;

import com.stocktracker.dto.ApiDto;
import com.stocktracker.instrument.AssetClass;
import com.stocktracker.marketdata.MarketData;
import com.stocktracker.marketdata.MarketDataRouter;
import com.stocktracker.model.Instrument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 1-minute bars for any instrument (multi-asset P2, issue #8) — backs
 * {@code GET /api/bars?key=}.
 *
 * <p>Equities delegate to {@link StockPriceService#getRange} unchanged, so the
 * equity path is byte-for-byte what {@code /api/prices/{symbol}/range} serves.
 * Everything else fills gaps from its {@link MarketData.BarProvider} into the
 * {@code bars} hypertable, tracked by {@code bar_coverage}, then reads back.
 */
@Slf4j
@Service
public class BarService {

    /** Matches the V12 retention policy: never claim coverage the DB has dropped. */
    static final Duration RETENTION = Duration.ofDays(90);
    /** Bars this fresh may still be revised/arriving; never mark them covered. */
    static final Duration SETTLE = Duration.ofMinutes(2);

    private final InstrumentService instruments;
    private final StockPriceService stockPrices;
    private final MarketDataRouter router;
    private final JdbcTemplate jdbc;

    public BarService(InstrumentService instruments, StockPriceService stockPrices,
                      MarketDataRouter router, JdbcTemplate jdbc) {
        this.instruments = instruments;
        this.stockPrices = stockPrices;
        this.router = router;
        this.jdbc = jdbc;
    }

    @Transactional
    public ApiDto.BarsResponse getRange(String key, Instant from, Instant to) {
        if (!from.isBefore(to)) throw new IllegalArgumentException("from must be before to");
        Instrument inst = instruments.resolve(key);

        if (inst.getAssetClass() == AssetClass.EQUITY || inst.getAssetClass() == AssetClass.ETF) {
            List<ApiDto.BarResponse> rows = stockPrices.getRange(inst.getSymbol(), from, to).stream()
                    .map(p -> new ApiDto.BarResponse(p.getTime(), inst.getKey(), p.getOpen(), p.getHigh(),
                            p.getLow(), p.getClose(),
                            p.getVolume() == null ? null : BigDecimal.valueOf(p.getVolume()),
                            p.getVwap(), p.getTradeCount() == null ? null : p.getTradeCount().longValue()))
                    .toList();
            return new ApiDto.BarsResponse(inst.getKey(), "alpaca-stocks", "REALTIME", rows);
        }

        MarketData.BarProvider provider = router.barsFor(inst.getAssetClass()).orElseThrow(() ->
                new UnsupportedOperationException("No bar provider for " + inst.getAssetClass()
                        + " yet (" + inst.getKey() + ")"));
        fillGaps(provider, inst, from, to);
        return new ApiDto.BarsResponse(inst.getKey(), provider.name(), provider.mode().name(),
                read(inst, from, to));
    }

    // ── Gap fill (bars twin of StockPriceService.fillGaps) ───────────────────

    void fillGaps(MarketData.BarProvider provider, Instrument inst, Instant from, Instant to) {
        Instant now = Instant.now();
        Instant floor = now.minus(RETENTION);
        Instant settled = now.minus(SETTLE);
        Instant f = from.isBefore(floor) ? floor : from;
        Instant t = to.isAfter(now) ? now : to;
        if (!f.isBefore(t)) return;

        List<Instant[]> cov = jdbc.query(
                "SELECT from_time, to_time FROM bar_coverage WHERE instrument_id = ?",
                (rs, i) -> new Instant[]{rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant()},
                inst.getId());

        Instant newFrom;
        Instant newTo;
        if (cov.isEmpty()) {
            fetchAndSave(provider, inst, f, t);
            newFrom = f;
            newTo = t;
        } else {
            // Retention may have dropped the head of the covered range.
            Instant covFrom = cov.get(0)[0].isBefore(floor) ? floor : cov.get(0)[0];
            Instant covTo = cov.get(0)[1];
            if (f.isBefore(covFrom)) fetchAndSave(provider, inst, f, covFrom);
            if (t.isAfter(covTo)) fetchAndSave(provider, inst, covTo, t);
            newFrom = f.isBefore(covFrom) ? f : covFrom;
            newTo = t.isAfter(covTo) ? t : covTo;
        }
        // Never claim the unsettled tail: the next read refetches it.
        if (newTo.isAfter(settled)) newTo = settled;
        if (!newFrom.isBefore(newTo)) return;
        jdbc.update("""
                INSERT INTO bar_coverage (instrument_id, from_time, to_time) VALUES (?, ?, ?)
                ON CONFLICT (instrument_id) DO UPDATE SET from_time = EXCLUDED.from_time, to_time = EXCLUDED.to_time
                """, inst.getId(), Timestamp.from(newFrom), Timestamp.from(newTo));
    }

    private void fetchAndSave(MarketData.BarProvider provider, Instrument inst, Instant from, Instant to) {
        List<MarketData.Bar> bars = provider.minuteBars(inst, from, to);
        log.info("{}: {} bars for {} [{} -> {}]", provider.name(), bars.size(), inst.getKey(), from, to);
        if (bars.isEmpty()) return;
        // DO UPDATE, not DO NOTHING: the unsettled tail is refetched and must overwrite.
        jdbc.batchUpdate("""
                INSERT INTO bars (time, instrument_id, open, high, low, close, volume, vwap, trade_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (instrument_id, time) DO UPDATE SET
                    open = EXCLUDED.open, high = EXCLUDED.high, low = EXCLUDED.low, close = EXCLUDED.close,
                    volume = EXCLUDED.volume, vwap = EXCLUDED.vwap, trade_count = EXCLUDED.trade_count
                """, bars, 1000, (ps, b) -> {
            ps.setTimestamp(1, Timestamp.from(b.time()));
            ps.setLong(2, inst.getId());
            ps.setBigDecimal(3, b.open());
            ps.setBigDecimal(4, b.high());
            ps.setBigDecimal(5, b.low());
            ps.setBigDecimal(6, b.close());
            ps.setBigDecimal(7, b.volume());
            ps.setBigDecimal(8, b.vwap());
            if (b.tradeCount() == null) ps.setNull(9, java.sql.Types.BIGINT); else ps.setLong(9, b.tradeCount());
        });
    }

    private List<ApiDto.BarResponse> read(Instrument inst, Instant from, Instant to) {
        return jdbc.query("""
                SELECT time, open, high, low, close, volume, vwap, trade_count FROM bars
                WHERE instrument_id = ? AND time BETWEEN ? AND ? ORDER BY time
                """, (rs, i) -> new ApiDto.BarResponse(
                        rs.getTimestamp(1).toInstant(), inst.getKey(),
                        strip(rs.getBigDecimal(2)), strip(rs.getBigDecimal(3)), strip(rs.getBigDecimal(4)),
                        strip(rs.getBigDecimal(5)), strip(rs.getBigDecimal(6)), strip(rs.getBigDecimal(7)),
                        (Long) rs.getObject(8, Long.class)),
                inst.getId(), Timestamp.from(from), Timestamp.from(to));
    }

    /** NUMERIC(28,10) → no trailing-zero padding and no exponent form in JSON. */
    private static BigDecimal strip(BigDecimal v) {
        if (v == null) return null;
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }
}
