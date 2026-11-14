-- Packs build doc §7: cloud accounts + cloud-provisioned compute targets.
-- Credentials never live here: the base access key is in ananke_secrets (scope "cloud:<id>").
CREATE TABLE IF NOT EXISTS cloud_accounts (
    id                BIGSERIAL PRIMARY KEY,
    cloud             VARCHAR(16)  NOT NULL,             -- aws (gcp, azure later)
    label             VARCHAR(100) NOT NULL,
    status            VARCHAR(16)  NOT NULL,             -- PENDING | LINKED | FAILED
    link_tag          VARCHAR(40)  NOT NULL UNIQUE,      -- tags every resource Ananke creates via this link (orphan finder)
    external_id       VARCHAR(64)  NOT NULL,             -- AWS sts:ExternalId the role trusts
    home_region       VARCHAR(32)  NOT NULL,
    role_arn          VARCHAR(256),
    account_ref       VARCHAR(64),                       -- AWS account id once verified
    last_verified_at  TIMESTAMPTZ,
    last_error        TEXT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

ALTER TABLE compute_targets ADD COLUMN IF NOT EXISTS cloud_account_id  BIGINT;
ALTER TABLE compute_targets ADD COLUMN IF NOT EXISTS cloud_region      VARCHAR(32);
ALTER TABLE compute_targets ADD COLUMN IF NOT EXISTS cloud_instance_id VARCHAR(32);
ALTER TABLE compute_targets ADD COLUMN IF NOT EXISTS cloud_meta        JSONB;
ALTER TABLE compute_targets ADD COLUMN IF NOT EXISTS idle_stop_minutes INT;
ALTER TABLE compute_targets ADD COLUMN IF NOT EXISTS owner_pack_id     BIGINT;
