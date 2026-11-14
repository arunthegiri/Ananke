package com.stocktracker.packs;

/**
 * A user-facing Pack failure with a stable code the UI can explain. Messages
 * must never contain secrets (keys, tokens) — callers pass redacted text only.
 */
public class PackException extends RuntimeException {

    public enum Code {
        INVALID_INPUT, REPO_NOT_FOUND, REPO_GATED, REPO_UNREACHABLE, INCOMPATIBLE_MODEL,
        REMOTE_CODE_UNTRUSTED, SSH_UNREACHABLE, SSH_HOSTKEY, SSH_AUTH, SSH_FAILED,
        NO_GPU, INSUFFICIENT_VRAM, MISSING_PREREQ, INSTALL_FAILED, DOWNLOAD_FAILED,
        VLLM_START_FAILED, HEALTH_TIMEOUT, TUNNEL_FAILED, NOT_DEPLOYED,
        // §7 cloud compute
        CLOUD_NOT_LINKED, CLOUD_AUTH, CLOUD_PERMISSION, CLOUD_FAILED, ESTIMATE_REQUIRED,
        PRICE_UNAVAILABLE, PROVISION_FAILED, HOSTKEY_TIMEOUT
    }

    private final Code code;

    public PackException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
