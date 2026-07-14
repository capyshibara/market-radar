package com.marketradar.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import com.marketradar.domain.PipelineRunLog;

import java.util.List;
import java.util.Optional;

public interface PipelineRunLogRepository extends JpaRepository<PipelineRunLog, Long> {

    @Query("select max(r.batchId) from PipelineRunLog r")
    Optional<Integer> maxBatchId();

    List<PipelineRunLog> findByBatchIdOrderByStartedAtAsc(int batchId);

    List<PipelineRunLog> findAllByOrderByBatchIdDescStartedAtDesc();
}
