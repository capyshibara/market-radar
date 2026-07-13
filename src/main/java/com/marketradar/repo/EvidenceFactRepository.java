package com.marketradar.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.RawDoc;
import java.util.List;

public interface EvidenceFactRepository extends JpaRepository<EvidenceFact, Long> {

    /** Batch 8 (extraction): guard idempotent — doc đã trích fact thì không trích lại. */
    boolean existsByRawDoc(RawDoc rawDoc);

    /**
     * JOIN FETCH rawDoc + source để render template ngoài transaction
     * (open-in-view = false) mà không dính LazyInitializationException.
     */
    @Query("select f from EvidenceFact f " +
           "join fetch f.rawDoc d join fetch d.source " +
           "order by f.eventDate desc")
    List<EvidenceFact> findAllForReport();

    /** Fix 2026-07-13: cùng lý do với InterpretedClaimRepository.findAllClaimCodes()
     * — count()+1 vỡ khi có row bị xoá. Tính max ở tầng Java. */
    @Query("select f.factCode from EvidenceFact f")
    List<String> findAllFactCodes();
}
