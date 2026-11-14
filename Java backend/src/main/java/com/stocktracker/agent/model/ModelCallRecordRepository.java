package com.stocktracker.agent.model;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ModelCallRecordRepository extends JpaRepository<ModelCallRecord, Long> {
    List<ModelCallRecord> findTop50ByWorkspaceIdOrderByCreatedAtDesc(String workspaceId);
}
