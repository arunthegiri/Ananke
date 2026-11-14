-- Multi-asset P1 (issue #8): one row per tradable/chartable instrument, any asset class.
--
-- Numbered V9, not V6: feature/agent-skills already ships V6–V8, and Flyway
-- refuses a lower version once a higher one is applied.
--
-- `key` is the canonical instrument key shared with the frontend
-- (src/lib/instruments.ts) and com.stocktracker.instrument.InstrumentKey:
--   AAPL · ^SPX · AAPL261218C00200000 · ESZ26@CME · ES1! · BTC/USD
--
-- Equity bars stay in stock_prices for now; a generalized `bars` hypertable
-- keyed by instrument_id arrives with the first non-equity data provider (P2).

CREATE TABLE IF NOT EXISTS instruments (
    id                    BIGSERIAL     PRIMARY KEY,
    key                   VARCHAR(40)   NOT NULL UNIQUE,
    asset_class           VARCHAR(20)   NOT NULL,
    symbol                VARCHAR(40)   NOT NULL,
    name                  VARCHAR(200),
    exchange              VARCHAR(16),
    currency              VARCHAR(8)    NOT NULL DEFAULT 'USD',
    -- Contract multiplier: 1 for shares/crypto, 100 for equity options, 50 for ES.
    multiplier            NUMERIC(18,6) NOT NULL DEFAULT 1,
    tick_size             NUMERIC(18,8),
    session_calendar      VARCHAR(16)   NOT NULL,
    underlying_id         BIGINT        REFERENCES instruments (id),

    option_expiry         DATE,
    option_right          VARCHAR(1),
    option_strike         NUMERIC(18,3),

    future_root           VARCHAR(8),
    -- 'YYYY-MM' for dated contracts; NULL for continuous series.
    future_contract_month VARCHAR(7),
    future_last_trade     DATE,

    crypto_base           VARCHAR(10),
    crypto_quote          VARCHAR(6),

    active                BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT instruments_asset_class_chk CHECK (asset_class IN
        ('EQUITY','ETF','INDEX','OPTION','FUTURE','FUTURE_OPTION','CRYPTO','EVENT_CONTRACT')),
    CONSTRAINT instruments_calendar_chk CHECK (session_calendar IN
        ('US_EQUITY','CME_GLOBEX','CRYPTO_24_7')),
    CONSTRAINT instruments_option_right_chk CHECK (option_right IS NULL OR option_right IN ('C','P')),
    CONSTRAINT instruments_option_fields_chk CHECK (
        asset_class NOT IN ('OPTION','FUTURE_OPTION')
        OR (option_expiry IS NOT NULL AND option_right IS NOT NULL AND option_strike IS NOT NULL)),
    CONSTRAINT instruments_future_fields_chk CHECK (
        asset_class <> 'FUTURE' OR future_root IS NOT NULL),
    CONSTRAINT instruments_crypto_fields_chk CHECK (
        asset_class <> 'CRYPTO' OR (crypto_base IS NOT NULL AND crypto_quote IS NOT NULL)),
    CONSTRAINT instruments_multiplier_chk CHECK (multiplier > 0)
);

CREATE INDEX IF NOT EXISTS idx_instruments_asset_class ON instruments (asset_class);
CREATE INDEX IF NOT EXISTS idx_instruments_underlying  ON instruments (underlying_id);
CREATE INDEX IF NOT EXISTS idx_instruments_future      ON instruments (future_root, future_contract_month);
CREATE INDEX IF NOT EXISTS idx_instruments_option      ON instruments (underlying_id, option_expiry);

-- Seed: every symbol Ananke already tracks or holds bars for becomes an
-- instrument. Today that is equities (Alpaca data feed); a slash means crypto.
INSERT INTO instruments (key, asset_class, symbol, session_calendar, crypto_base, crypto_quote, tick_size)
SELECT s,
       CASE WHEN s LIKE '%/%' THEN 'CRYPTO' ELSE 'EQUITY' END,
       s,
       CASE WHEN s LIKE '%/%' THEN 'CRYPTO_24_7' ELSE 'US_EQUITY' END,
       CASE WHEN s LIKE '%/%' THEN split_part(s, '/', 1) END,
       CASE WHEN s LIKE '%/%' THEN split_part(s, '/', 2) END,
       CASE WHEN s LIKE '%/%' THEN NULL ELSE 0.01 END
FROM (
    SELECT UPPER(symbol) AS s FROM tracked_symbols
    UNION
    SELECT DISTINCT UPPER(symbol) FROM stock_prices
) seed
ON CONFLICT (key) DO NOTHING;
