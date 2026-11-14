package com.stocktracker.agent.model;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentModelBindingRepository extends JpaRepository<AgentModelBinding, Long> {
    Optional<AgentModelBinding> findByWorkspaceIdAndScopeTypeAndScopeIdAndRoleId(
            String workspaceId, AgentModelBinding.Scope scopeType, String scopeId, String roleId);

    List<AgentModelBinding> findAllByWorkspaceId(String workspaceId);

    List<AgentModelBinding> findAllByWorkspaceIdAndConnectionId(String workspaceId, String connectionId);
}
