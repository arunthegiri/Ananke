package com.stocktracker.packs;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/** A pinned Hugging Face model plus its deployment state. See V7__packs.sql. */
@Data
@NoArgsConstructor
@Entity
@Table(name = "packs")
public class Pack {

    public enum Status { IMPORTED, DEPLOYING, RUNNING, FAILED, STOPPED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "hf_repo", nullable = false, length = 200)
    private String hfRepo;

    @Column(name = "hf_revision", nullable = false, length = 40)
    private String hfRevision;

    @Column(columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> profile;

    @Column(name = "trust_remote_code", nullable = false)
    private boolean trustRemoteCode;

    @Column(name = "launch_config", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> launchConfig;

    @Column(name = "compute_target_id")
    private Long computeTargetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Status status = Status.IMPORTED;

    @Column(length = 32)
    private String phase;

    @Column(name = "failure_code", length = 40)
    private String failureCode;

    @Column(name = "failure_detail", columnDefinition = "TEXT")
    private String failureDetail;

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> endpoint;

    @Column(name = "last_health", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> lastHealth;

    @Column(name = "deploy_log", nullable = false, columnDefinition = "TEXT")
    private String deployLog = "";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public String scope() {
        return "pack:" + id;
    }
}
