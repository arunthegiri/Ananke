package com.stocktracker.packs;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PackRepository extends JpaRepository<Pack, Long> {
    List<Pack> findAllByOrderByCreatedAtDesc();
    List<Pack> findByStatus(Pack.Status status);
    List<Pack> findByComputeTargetId(Long computeTargetId);
    boolean existsByComputeTargetId(Long id);
}
