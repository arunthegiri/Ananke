-- Ananke Packs (Dev-notes/Ananke Packs Build Doc.md): a pinned Hugging Face
-- model + a compute target + a vLLM deployment that the copilot can use.

-- Encrypted credentials (AES-256-GCM, see SecretVault). The owner scope
-- (e.g. "target:3", "pack:7") is bound into the ciphertext as AAD, so a blob
-- copied to another row fails to decrypt. Plaintext never touches this table.
CREATE TABLE IF NOT EXISTS ananke_secrets (
    id          BIGSERIAL PRIMARY KEY,
    scope       VARCHAR(64)  NOT NULL,
    kind        VARCHAR(32)  NOT NULL,
    ciphertext  TEXT         NOT NULL,
    hint        VARCHAR(16),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (scope, kind)
);

CREATE TABLE IF NOT EXISTS compute_targets (
    id               BIGSERIAL PRIMARY KEY,
    provider         VARCHAR(32)  NOT NULL,           -- "ssh" today; "aws"/"gcp" later
    label            VARCHAR(100) NOT NULL,
    host             VARCHAR(255) NOT NULL,
    port             INT          NOT NULL DEFAULT 22,
    username         VARCHAR(64)  NOT NULL,
    host_key         TEXT,                            -- pinned known_hosts line, confirmed by the user
    host_key_fingerprint VARCHAR(80),
    status           VARCHAR(32)  NOT NULL DEFAULT 'UNVERIFIED',
    last_probe       JSONB,
    last_probe_at    TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS packs (
    id                 BIGSERIAL PRIMARY KEY,
    name               VARCHAR(100) NOT NULL,
    hf_repo            VARCHAR(200) NOT NULL,
    hf_revision        VARCHAR(40)  NOT NULL,          -- pinned commit SHA, never a branch
    profile            JSONB        NOT NULL,          -- model metadata + compatibility report
    trust_remote_code  BOOLEAN      NOT NULL DEFAULT FALSE,
    launch_config      JSONB,
    compute_target_id  BIGINT REFERENCES compute_targets(id) ON DELETE SET NULL,
    status             VARCHAR(32)  NOT NULL DEFAULT 'IMPORTED',
    phase              VARCHAR(32),
    failure_code       VARCHAR(40),
    failure_detail     TEXT,
    endpoint           JSONB,                          -- remote port, tunnel, served model, capabilities
    last_health        JSONB,
    deploy_log         TEXT         NOT NULL DEFAULT '',
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
