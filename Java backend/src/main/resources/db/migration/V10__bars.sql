-- Multi-asset P2 (issue #8): OHLCV for any instrument, keyed by instrument_id.
--
-- Equities keep writing stock_prices (unchanged behaviour). Non-equity
-- instruments — crypto first — land here. Volume is NUMERIC, not BIGINT:
-- crypto volume is fractional (0.01 BTC), and truncating it would be silent
-- data loss.

CREATE TABLE IF NOT EXISTS bars (
    time          TIMESTAMPTZ    NOT NULL,
    instrument_id BIGINT         NOT NULL REFERENCES instruments (id),
    open          NUMERIC(28,10),
    high          NUMERIC(28,10),
    low           NUMERIC(28,10),
    close         NUMERIC(28,10) NOT NULL,
    volume        NUMERIC(28,10),
    vwap          NUMERIC(28,10),
    trade_count   BIGINT,
    open_interest BIGINT,
    PRIMARY KEY (instrument_id, time)
);

SELECT create_hypertable('bars', 'time', if_not_exists => TRUE);

CREATE INDEX IF NOT EXISTS idx_bars_instrument_time ON bars (instrument_id, time DESC);

-- Same retention as stock_prices (V1).
SELECT add_retention_policy('bars', INTERVAL '90 days', if_not_exists => TRUE);

-- Contiguous fetched range per instrument; the bars twin of symbol_coverage.
CREATE TABLE IF NOT EXISTS bar_coverage (
    instrument_id BIGINT      PRIMARY KEY REFERENCES instruments (id),
    from_time     TIMESTAMPTZ NOT NULL,
    to_time       TIMESTAMPTZ NOT NULL
);
