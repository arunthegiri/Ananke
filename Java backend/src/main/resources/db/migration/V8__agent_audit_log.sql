-- Agent action audit log (Packs build doc §9.3): every billable action an agent asks
-- for is recorded on the Pack it concerns — requested, then confirmed/rejected by a
-- person, then executed/failed. No FK on pack_id: the history outlives a removed Pack.
CREATE TABLE IF NOT EXISTS agent_audit_log (
    id             BIGSERIAL PRIMARY KEY,
    action_id      VARCHAR(40)  NOT NULL,
    pack_id        BIGINT,
    agent          VARCHAR(32)  NOT NULL,
    cloud          VARCHAR(16),
    tool           VARCHAR(64)  NOT NULL,
    event          VARCHAR(16)  NOT NULL,   -- REQUESTED | CONFIRMED | REJECTED | EXECUTED | FAILED | EXPIRED | REFUSED
    cost_estimate  TEXT,
    arguments      TEXT,
    detail         TEXT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS agent_audit_log_pack_idx   ON agent_audit_log (pack_id, created_at);
CREATE INDEX IF NOT EXISTS agent_audit_log_action_idx ON agent_audit_log (action_id);
