package com.marketradar.repo;

import com.marketradar.domain.ResearchCurationBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ResearchCurationBatchRepository extends JpaRepository<ResearchCurationBatch, Long> {
    List<ResearchCurationBatch> findAllByOrderByStartedAtDescIdDesc();
}
