package com.stocktracker.packs;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ComputeTargetRepository extends JpaRepository<ComputeTarget, Long> {
    List<ComputeTarget> findAllByOrderByCreatedAtDesc();
    List<ComputeTarget> findByProvider(String provider);
    List<ComputeTarget> findByCloudAccountId(Long cloudAccountId);
}
