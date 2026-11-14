package com.stocktracker.agent.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A provider connection (§5.1): adapter, endpoint, credential <em>reference</em>, owner scope
 * and trust zone. One connection serves many models; two connections may use the same
 * provider with different credentials and never collide, because everything is keyed by
 * {@link #id}, never by a provider label.
 *
 * <p>{@link #version} is the configuration version recorded in binding snapshots. It bumps on
 * any edit to this row (endpoint, adapter, enabled…), which also invalidates capability
 * probes. Rotating the API key does <em>not</em> bump it: the key lives in the vault and is
 * resolved on every call, so a rotation or revocation takes effect immediately for running
 * work without forcing a rebind (§5.2).
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "model_connections")
public class ModelConnection {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "workspace_id", nullable = false, length = 64)
    private String workspaceId;

    @Column(nullable = false, length = 100)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Adapter adapter;

    /** Settings preset this was created from (anthropic, openai, ollama…); informational. */
    @Column(name = "preset_id", length = 32)
    private String presetId;

    /** Null for {@link Adapter#PACK}: a Pack's endpoint is its tunnel, resolved per call. */
    @Column(name = "base_url", length = 500)
    private String baseUrl;

    /** For {@link Adapter#PACK}: the provider id, {@code pack:<id>}. */
    @Column(name = "deployment_id", length = 64)
    private String deploymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "trust_zone", nullable = false, length = 32)
    private TrustZone trustZone;

    /** Whether calls need a vault credential; false for keyless local servers and Packs. */
    @Column(name = "key_required", nullable = false)
    private boolean keyRequired;

    @Column(nullable = false)
    private boolean enabled = true;

    /** Boxed so Spring Data treats a fresh row (null) as new despite the assigned id. */
    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    /** Vault scope of this connection's credential. */
    public String secretScope() {
        return "model-conn:" + id;
    }

    public static final String SECRET_KIND = "api-key";
}
