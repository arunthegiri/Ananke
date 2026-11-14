package com.stocktracker.packs.cloud;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** A linked cloud account (Packs build doc §7.1). See V9__cloud_compute.sql. */
@Data
@NoArgsConstructor
@Entity
@Table(name = "cloud_accounts")
public class CloudAccount {

    public enum Status { PENDING, LINKED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String cloud;

    @Column(nullable = false, length = 100)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.PENDING;

    @Column(name = "link_tag", nullable = false, length = 40, unique = true)
    private String linkTag;

    @Column(name = "external_id", nullable = false, length = 64)
    private String externalId;

    @Column(name = "home_region", nullable = false, length = 32)
    private String homeRegion;

    @Column(name = "role_arn", length = 256)
    private String roleArn;

    @Column(name = "account_ref", length = 64)
    private String accountRef;

    @Column(name = "last_verified_at")
    private Instant lastVerifiedAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public String scope() {
        return "cloud:" + id;
    }
}
