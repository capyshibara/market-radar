package com.marketradar.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import com.marketradar.domain.EvidenceFact;
import java.util.List;

public interface EvidenceFactRepository extends JpaRepository<EvidenceFact, Long> {

    /**
     * JOIN FETCH rawDoc + source để render template ngoài transaction
     * (open-in-view = false) mà không dính LazyInitializationException.
     */
    @Query("select f from EvidenceFact f " +
           "join fetch f.rawDoc d join fetch d.source " +
           "order by f.eventDate desc")
    List<EvidenceFact> findAllForReport();
}
