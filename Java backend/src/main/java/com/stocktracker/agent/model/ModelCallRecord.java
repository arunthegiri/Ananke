package com.stocktracker.agent.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One model call (§1.1 outcome 1, §8.5 {@code model_calls}): which role, run and task made
 * it, with exactly which binding, over what input (manifest hash), and what it cost. Usage
 * the provider did not report is null — unknown, never zero (§18).
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "model_calls")
public class ModelCallRecord {

    public enum Outcome { OK, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workspace_id", nullable = false, length = 64)
    private String workspaceId;

    @Column(name = "run_id", length = 64)
    private String runId;

    @Column(name = "task_id", length = 64)
    private String taskId;

    @Column(name = "role_id", nullable = false, length = 40)
    private String roleId;

    @Column(name = "connection_id", nullable = false, length = 64)
    private String connectionId;

    @Column(name = "connection_version", nullable = false)
    private long connectionVersion;

    @Column(name = "model_id", nullable = false, length = 200)
    private String modelId;

    @Column(name = "deployment_revision", length = 100)
    private String deploymentRevision;

    /** Set when this call was a fallback: the connection/model that failed first. */
    @Column(name = "fallback_from", length = 300)
    private String fallbackFrom;

    @Column(name = "input_manifest_sha256", nullable = false, length = 64)
    private String inputManifestSha256;

    @Column(name = "provider_request_id", length = 200)
    private String providerRequestId;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "finish_reason", length = 40)
    private String finishReason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Outcome outcome;

    /** Scrubbed, capped error text; never contains a credential. */
    @Column(name = "error_detail", length = 500)
    private String errorDetail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
