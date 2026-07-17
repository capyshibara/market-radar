package com.marketradar.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.RawDoc;
import java.time.Instant;
import java.util.List;

public interface EvidenceFactRepository extends JpaRepository<EvidenceFact, Long> {

    /** Batch 8 (extraction): guard idempotent — doc đã trích fact thì không trích lại. */
    boolean existsByRawDoc(RawDoc rawDoc);

    boolean existsByRawDocAndActiveTrue(RawDoc rawDoc);

    /**
     * JOIN FETCH rawDoc + source để render template ngoài transaction
     * (open-in-view = false) mà không dính LazyInitializationException.
     */
    @Query("select f from EvidenceFact f " +
           "join fetch f.rawDoc d join fetch d.source " +
           "where f.active = true " +
           "order by f.eventDate desc")
    List<EvidenceFact> findAllForReport();

    /**
     * Read-only candidate set for the "Current Product News" layer.  This is
     * intentionally broader than the decision-brief query: the service applies
     * its separate editorial policy and never changes fact or document state.
     * Fetching document/source here keeps the report read safe with
     * open-in-view disabled.
     */
    @Query("select f from EvidenceFact f " +
           "join fetch f.rawDoc d join fetch d.source s " +
           "where f.active = true and s.active = true " +
           "and d.fullTextFetched = true and d.sampleData = false " +
           "and d.duplicateOfId is null and s.tier between 1 and 3")
    List<EvidenceFact> findCurrentProductNewsCandidates();

    @Query("select f from EvidenceFact f where f.active = true order by f.id")
    List<EvidenceFact> findAllActiveOrderById();

    /** Desk feed: resolve one story link per routed document. Read-only. */
    @Query("select f from EvidenceFact f where f.rawDoc.id in :rawDocIds and f.active = true order by f.id")
    List<EvidenceFact> findActiveByRawDocIdIn(@Param("rawDocIds") List<Long> rawDocIds);

    /** Immutable editions must keep resolving their original evidence after a newer
     * extraction edition supersedes it. This audit read intentionally includes inactive rows. */
    @Query("select f from EvidenceFact f " +
           "join fetch f.rawDoc d join fetch d.source " +
           "where f.factCode in :factCodes order by f.id")
    List<EvidenceFact> findAllByFactCodeInForAudit(@Param("factCodes") List<String> factCodes);

    /** Preserve old rows while atomically switching the active extraction edition. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update EvidenceFact f set f.active = false, f.supersededAt = :at, " +
           "f.supersededByRunId = :newRunId " +
           "where f.rawDoc.id = :rawDocId and f.active = true " +
           "and (f.extractionRun is null or f.extractionRun.id <> :newRunId)")
    int supersedeOtherActiveFacts(@Param("rawDocId") Long rawDocId,
                                  @Param("newRunId") Long newRunId,
                                  @Param("at") Instant at);

    /** Fix 2026-07-13: cùng lý do với InterpretedClaimRepository.findAllClaimCodes()
     * — count()+1 vỡ khi có row bị xoá. Tính max ở tầng Java. */
    @Query("select f.factCode from EvidenceFact f")
    List<String> findAllFactCodes();
}
