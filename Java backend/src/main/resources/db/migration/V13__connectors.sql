-- Data connectors (Connectors roadmap §1, issue #10): per-connector link state and health.
-- Credentials never live here: every field the user enters is in ananke_secrets
-- (scope "connector:<id>", kind = field key), encrypted by SecretVault.
CREATE TABLE IF NOT EXISTS connector_accounts (
    workspace_id       VARCHAR(64)  NOT NULL DEFAULT 'local',
    connector_id       VARCHAR(32)  NOT NULL,
    enabled            BOOLEAN      NOT NULL DEFAULT TRUE,
    linked_at          TIMESTAMPTZ,
    last_test_at       TIMESTAMPTZ,
    last_test_ok       BOOLEAN,
    last_test_message  TEXT,
    last_call_at       TIMESTAMPTZ,
    last_error_at      TIMESTAMPTZ,
    last_error         TEXT,
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (workspace_id, connector_id)
);
