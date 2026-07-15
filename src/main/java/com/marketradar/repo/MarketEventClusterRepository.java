package com.marketradar.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import com.marketradar.domain.MarketEventCluster;

import java.util.Optional;

public interface MarketEventClusterRepository extends JpaRepository<MarketEventCluster, Long> {
    Optional<MarketEventCluster> findByClusterKey(String clusterKey);
}
