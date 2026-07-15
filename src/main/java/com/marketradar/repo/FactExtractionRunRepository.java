package com.marketradar.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.marketradar.domain.FactExtractionRun;
import com.marketradar.domain.RawDoc;

import java.time.Instant;

public interface FactExtractionRunRepository extends JpaRepository<FactExtractionRun, Long> {

    boolean existsByRawDocAndExtractionSignatureAndStatusAndCurrentEditionTrue(
            RawDoc rawDoc, String extractionSignature, FactExtractionRun.Status status);

    long countByStatus(FactExtractionRun.Status status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update FactExtractionRun r set r.currentEdition = false, r.supersededAt = :at " +
           "where r.rawDoc.id = :rawDocId and r.currentEdition = true and r.id <> :newRunId")
    int retirePriorCurrentEdition(@Param("rawDocId") Long rawDocId,
                                  @Param("newRunId") Long newRunId,
                                  @Param("at") Instant at);
}
