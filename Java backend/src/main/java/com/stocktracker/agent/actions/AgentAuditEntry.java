package com.stocktracker.agent.actions;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** One row of the agent action audit trail. See V8__agent_audit_log.sql. */
@Data
@NoArgsConstructor
@Entity
@Table(name = "agent_audit_log")
public class AgentAuditEntry {

    public enum Event { REQUESTED, CONFIRMED, REJECTED, EXECUTED, FAILED, EXPIRED, REFUSED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "action_id", nullable = false, length = 40)
    private String actionId;

    @Column(name = "pack_id")
    private Long packId;

    @Column(nullable = false, length = 32)
    private String agent;

    @Column(length = 16)
    private String cloud;

    @Column(nullable = false, length = 64)
    private String tool;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Event event;

    @Column(name = "cost_estimate", columnDefinition = "text")
    private String costEstimate;

    @Column(columnDefinition = "text")
    private String arguments;

    @Column(columnDefinition = "text")
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
