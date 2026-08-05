package com.marketradar.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.marketradar.domain.FactExtractionRun;
import com.marketradar.domain.RawDoc;

import java.time.Instant;
import java.util.List;

public interface FactExtractionRunRepository extends JpaRepository<FactExtractionRun, Long> {

    boolean existsByRawDocAndExtractionSignatureAndStatusAndCurrentEditionTrue(
            RawDoc rawDoc, String extractionSignature, FactExtractionRun.Status status);

    long countByStatus(FactExtractionRun.Status status);

    @Query("select r from FactExtractionRun r join fetch r.rawDoc d join fetch d.source " +
           "where r.currentEdition = true and r.status = :status")
    List<FactExtractionRun> findAllCurrentSuccessfulWithDocument(
            @Param("status") FactExtractionRun.Status status);

    @Query("select r from FactExtractionRun r join fetch r.rawDoc d join fetch d.source")
    List<FactExtractionRun> findAllWithDocument();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update FactExtractionRun r set r.currentEdition = false, r.supersededAt = :at " +
           "where r.rawDoc.id = :rawDocId and r.currentEdition = true and r.id <> :newRunId")
    int retirePriorCurrentEdition(@Param("rawDocId") Long rawDocId,
                                  @Param("newRunId") Long newRunId,
                                  @Param("at") Instant at);
}
