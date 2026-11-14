package com.stocktracker.agent.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One capability probe result (§5.6). Keyed by the configuration it was run against —
 * connection version, model id and deployment revision — so changing any of them leaves the
 * old row behind and the capability reads as UNVERIFIED until probed again.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "model_capability_probes")
public class ModelCapabilityProbe {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workspace_id", nullable = false, length = 64)
    private String workspaceId;

    @Column(name = "connection_id", nullable = false, length = 64)
    private String connectionId;

    @Column(name = "connection_version", nullable = false)
    private long connectionVersion;

    @Column(name = "model_id", nullable = false, length = 200)
    private String modelId;

    /** "" when the connection has no deployment (hosted APIs). */
    @Column(name = "deployment_revision", nullable = false, length = 100)
    private String deploymentRevision = "";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Capability capability;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Capability.Status status;

    @Column(name = "probe_version", nullable = false, length = 32)
    private String probeVersion;

    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(name = "probed_at", nullable = false)
    private Instant probedAt = Instant.now();
}
