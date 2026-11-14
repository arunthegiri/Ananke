-- Agent Integration spec W1 (§5, §8.5): per-agent model routing.
-- Version V10: V8 is agent-skills; V9 is claimed by both feature/cloud-compute
-- (V9__cloud_compute.sql) and feature/multi-asset (V9__instruments.sql); allocation is
-- tracked in AGENTS.md ("Agent runtime") and issue #9.
--
-- workspace_id scopes every row (§8.6). Today it is always 'local'; it is a seam for hosted
-- multi-user work, not an authorization system.

CREATE TABLE IF NOT EXISTS model_connections (
    id             VARCHAR(64)  PRIMARY KEY,
    workspace_id   VARCHAR(64)  NOT NULL,
    label          VARCHAR(100) NOT NULL,
    adapter        VARCHAR(32)  NOT NULL,      -- ANTHROPIC | OPENAI_COMPATIBLE | PACK
    preset_id      VARCHAR(32),
    base_url       VARCHAR(500),               -- null for PACK (tunnel resolved per call)
    deployment_id  VARCHAR(64),                -- 'pack:<id>' for PACK
    trust_zone     VARCHAR(32)  NOT NULL,      -- EXTERNAL_API | LOCAL | PRIVATE_COMPUTE (server-derived)
    key_required   BOOLEAN      NOT NULL,
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    version        BIGINT       NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT model_connections_pack_chk CHECK (
        (adapter = 'PACK' AND deployment_id IS NOT NULL AND base_url IS NULL)
        OR (adapter <> 'PACK' AND base_url IS NOT NULL))
);
-- Composite key target so child rows can be pinned to the same workspace (§8.5).
CREATE UNIQUE INDEX IF NOT EXISTS model_connections_ws_id_uq ON model_connections (workspace_id, id);

CREATE TABLE IF NOT EXISTS agent_model_bindings (
    id                 BIGSERIAL    PRIMARY KEY,
    workspace_id       VARCHAR(64)  NOT NULL,
    scope_type         VARCHAR(16)  NOT NULL,  -- WORKSPACE | PROJECT
    scope_id           VARCHAR(64)  NOT NULL DEFAULT '',
    role_id            VARCHAR(40)  NOT NULL,  -- role id, or '*' = workspace default
    inherit_default    BOOLEAN      NOT NULL DEFAULT FALSE,
    connection_id      VARCHAR(64),
    model_id           VARCHAR(200),
    max_output_tokens  INTEGER,
    fallback_mode      VARCHAR(16)  NOT NULL DEFAULT 'DISABLED',
    fallback_targets   JSONB        NOT NULL DEFAULT '[]'::jsonb,
    version            BIGINT       NOT NULL DEFAULT 0,
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT agent_model_bindings_scope_uq UNIQUE (workspace_id, scope_type, scope_id, role_id),
    CONSTRAINT agent_model_bindings_conn_fk FOREIGN KEY (workspace_id, connection_id)
        REFERENCES model_connections (workspace_id, id),
    CONSTRAINT agent_model_bindings_target_chk CHECK (inherit_default OR connection_id IS NOT NULL),
    CONSTRAINT agent_model_bindings_tokens_chk CHECK (max_output_tokens IS NULL OR max_output_tokens > 0)
);

-- Capability probe history (§5.6). Rows are keyed by the configuration they tested; editing a
-- connection or redeploying a Pack leaves them behind rather than rewriting them.
CREATE TABLE IF NOT EXISTS model_capability_probes (
    id                   BIGSERIAL    PRIMARY KEY,
    workspace_id         VARCHAR(64)  NOT NULL,
    connection_id        VARCHAR(64)  NOT NULL,
    connection_version   BIGINT       NOT NULL,
    model_id             VARCHAR(200) NOT NULL,
    deployment_revision  VARCHAR(100) NOT NULL DEFAULT '',
    capability           VARCHAR(32)  NOT NULL,
    status               VARCHAR(16)  NOT NULL,  -- VERIFIED | UNVERIFIED | UNSUPPORTED
    probe_version        VARCHAR(32)  NOT NULL,
    detail               TEXT,
    probed_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT model_capability_probes_conn_fk FOREIGN KEY (workspace_id, connection_id)
        REFERENCES model_connections (workspace_id, id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS model_capability_probes_lookup_idx
    ON model_capability_probes (workspace_id, connection_id, connection_version, model_id, deployment_revision, probed_at DESC);

-- One row per attempted model call (§1.1 outcome 1). No FK to model_connections: lineage
-- must outlive a deleted connection. Unknown usage stays NULL, never 0 (§18).
CREATE TABLE IF NOT EXISTS model_calls (
    id                     BIGSERIAL    PRIMARY KEY,
    workspace_id           VARCHAR(64)  NOT NULL,
    run_id                 VARCHAR(64),
    task_id                VARCHAR(64),
    role_id                VARCHAR(40)  NOT NULL,
    connection_id          VARCHAR(64)  NOT NULL,
    connection_version     BIGINT       NOT NULL,
    model_id               VARCHAR(200) NOT NULL,
    deployment_revision    VARCHAR(100),
    fallback_from          VARCHAR(300),
    input_manifest_sha256  VARCHAR(64)  NOT NULL,
    provider_request_id    VARCHAR(200),
    input_tokens           INTEGER,
    output_tokens          INTEGER,
    latency_ms             BIGINT       NOT NULL,
    finish_reason          VARCHAR(40),
    outcome                VARCHAR(16)  NOT NULL,  -- OK | FAILED
    error_detail           VARCHAR(500),
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS model_calls_ws_created_idx ON model_calls (workspace_id, created_at DESC);
CREATE INDEX IF NOT EXISTS model_calls_run_idx ON model_calls (run_id, created_at) WHERE run_id IS NOT NULL;
