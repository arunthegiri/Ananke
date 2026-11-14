package com.stocktracker.agent.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A saved role → model choice at one scope (§5.2). Unique per (workspace, scope, role).
 * {@code roleId = "*"} is the workspace default. A row with {@link #inheritDefault} set
 * explicitly defers to the workspace default (spec §5.3 {@code inherit: workspaceDefault}).
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "agent_model_bindings")
public class AgentModelBinding {

    public enum Scope { WORKSPACE, PROJECT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workspace_id", nullable = false, length = 64)
    private String workspaceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 16)
    private Scope scopeType;

    /** Project id for PROJECT scope; empty string for WORKSPACE scope (keeps the unique key simple). */
    @Column(name = "scope_id", nullable = false, length = 64)
    private String scopeId = "";

    @Column(name = "role_id", nullable = false, length = 40)
    private String roleId;

    @Column(name = "inherit_default", nullable = false)
    private boolean inheritDefault;

    @Column(name = "connection_id", length = 64)
    private String connectionId;

    @Column(name = "model_id", length = 200)
    private String modelId;

    @Column(name = "max_output_tokens")
    private Integer maxOutputTokens;

    @Enumerated(EnumType.STRING)
    @Column(name = "fallback_mode", nullable = false, length = 16)
    private FallbackPolicy.Mode fallbackMode = FallbackPolicy.Mode.DISABLED;

    /** Ordered {@code [{connectionId, modelId}]}; only used when {@link #fallbackMode} is EXPLICIT. */
    @Column(name = "fallback_targets", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private List<Map<String, String>> fallbackTargets = new ArrayList<>();

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public BindingSpec toSpec() {
        List<FallbackPolicy.Target> targets = new ArrayList<>();
        for (Map<String, String> t : fallbackTargets == null ? List.<Map<String, String>>of() : fallbackTargets) {
            targets.add(new FallbackPolicy.Target(t.get("connectionId"), t.get("modelId")));
        }
        return new BindingSpec(inheritDefault, connectionId, modelId, maxOutputTokens,
                new FallbackPolicy(fallbackMode, targets), version);
    }
}
