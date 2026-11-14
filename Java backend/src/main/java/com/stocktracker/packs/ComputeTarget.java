package com.stocktracker.packs;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/** A machine a Pack can run on. Credentials live in {@link SecretVault} under {@link #scope()}. */
@Data
@NoArgsConstructor
@Entity
@Table(name = "compute_targets")
public class ComputeTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private String host;

    @Column(nullable = false)
    private int port = 22;

    @Column(nullable = false)
    private String username;

    @Column(name = "host_key", columnDefinition = "TEXT")
    private String hostKey;

    @Column(name = "host_key_fingerprint")
    private String hostKeyFingerprint;

    /** UNVERIFIED → (host key confirmed + probe ok) READY, or ERROR. */
    @Column(nullable = false)
    private String status = "UNVERIFIED";

    @Column(name = "last_probe", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> lastProbe;

    @Column(name = "last_probe_at")
    private Instant lastProbeAt;

    // ── cloud-provisioned targets (§7); null for "ssh" ──────────────────────
    @Column(name = "cloud_account_id")
    private Long cloudAccountId;

    @Column(name = "cloud_region", length = 32)
    private String cloudRegion;

    @Column(name = "cloud_instance_id", length = 32)
    private String cloudInstanceId;

    /** instanceType, amiId, securityGroupId, state, phase, error, estimate, launchedAt… */
    @Column(name = "cloud_meta", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> cloudMeta;

    /** Stop the VM after this many idle minutes (no copilot traffic to its Packs); null/0 = never. */
    @Column(name = "idle_stop_minutes")
    private Integer idleStopMinutes;

    /** The Pack this VM was provisioned for; removing that Pack terminates the VM. */
    @Column(name = "owner_pack_id")
    private Long ownerPackId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public String scope() {
        return "target:" + id;
    }
}
