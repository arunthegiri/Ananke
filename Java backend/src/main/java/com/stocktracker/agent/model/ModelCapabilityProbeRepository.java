package com.stocktracker.agent.model;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface ModelCapabilityProbeRepository extends JpaRepository<ModelCapabilityProbe, Long> {
    List<ModelCapabilityProbe>
    findByWorkspaceIdAndConnectionIdAndConnectionVersionAndModelIdAndDeploymentRevisionAndProbedAtAfterOrderByProbedAtDesc(
            String workspaceId, String connectionId, long connectionVersion, String modelId,
            String deploymentRevision, Instant notBefore);
}
