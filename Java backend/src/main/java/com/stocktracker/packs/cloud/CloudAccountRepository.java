package com.stocktracker.packs.cloud;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CloudAccountRepository extends JpaRepository<CloudAccount, Long> {
    List<CloudAccount> findAllByOrderByCreatedAtAsc();
    List<CloudAccount> findByCloudAndStatus(String cloud, CloudAccount.Status status);
}
