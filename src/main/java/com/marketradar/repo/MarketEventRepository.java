package com.marketradar.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.MarketEvent;

import java.util.List;
import java.time.LocalDate;

public interface MarketEventRepository extends JpaRepository<MarketEvent, Long> {

    boolean existsByEvidenceFactAndPipelineVersion(EvidenceFact evidenceFact, String pipelineVersion);

    /** Safe synthesis read when open-in-view=false: evidence, document and source are loaded. */
    @Query("select e from MarketEvent e " +
           "join fetch e.evidenceFact f " +
           "join fetch f.rawDoc d " +
           "join fetch d.source s " +
           "where e.pipelineVersion = :pipelineVersion " +
           "and f.active = true " +
           "order by e.publishedDate desc, e.id desc")
    List<MarketEvent> findAllForPipelineVersion(@Param("pipelineVersion") String pipelineVersion);

    @Query("select e from MarketEvent e " +
           "join fetch e.evidenceFact f join fetch f.rawDoc d join fetch d.source s " +
           "left join fetch e.cluster c " +
           "where e.pipelineVersion = :pipelineVersion and f.active = true " +
           "order by e.publishedDate desc, e.id desc")
    List<MarketEvent> findAllWithClusterForPipelineVersion(
            @Param("pipelineVersion") String pipelineVersion);

    /** Mandatory source for future-looking action generation: expired events are absent. */
    @Query("select e from MarketEvent e " +
           "join fetch e.evidenceFact f join fetch f.rawDoc d join fetch d.source s " +
           "where e.pipelineVersion = :pipelineVersion and f.active = true " +
           "and (e.expiryDate is null or e.expiryDate >= :asOf) " +
           "and (e.effectiveDate is null or e.expiryDate is null or e.expiryDate >= e.effectiveDate) " +
           "order by e.publishedDate desc, e.id desc")
    List<MarketEvent> findFutureActionEligible(@Param("pipelineVersion") String pipelineVersion,
                                               @Param("asOf") LocalDate asOf);
}
