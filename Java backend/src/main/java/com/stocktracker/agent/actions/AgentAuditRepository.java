package com.stocktracker.agent.actions;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentAuditRepository extends JpaRepository<AgentAuditEntry, Long> {
    List<AgentAuditEntry> findByPackIdOrderByCreatedAtAsc(Long packId);
    List<AgentAuditEntry> findByActionIdOrderByCreatedAtAsc(String actionId);
}
