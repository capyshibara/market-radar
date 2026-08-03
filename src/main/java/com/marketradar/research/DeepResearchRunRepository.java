package com.marketradar.research;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeepResearchRunRepository extends JpaRepository<DeepResearchRun, Long> {

    List<DeepResearchRun> findAllByOrderByQueuedAtDesc();

    List<DeepResearchRun> findByStatusIn(List<DeepResearchRun.Status> statuses);

    List<DeepResearchRun> findByStatusOrderByQueuedAtAsc(DeepResearchRun.Status status);
}
