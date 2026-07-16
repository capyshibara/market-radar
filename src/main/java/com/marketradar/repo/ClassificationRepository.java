package com.marketradar.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import com.marketradar.domain.Classification;
import com.marketradar.domain.RawDoc;
import java.util.List;
import java.util.Optional;

public interface ClassificationRepository extends JpaRepository<Classification, Long> {
    boolean existsByRawDoc(RawDoc rawDoc);

    Optional<Classification> findByRawDoc(RawDoc rawDoc);

    @Query("select c from Classification c join fetch c.rawDoc d join fetch d.source " +
           "order by c.createdAt desc")
    List<Classification> findAllForDisplay();

    /** Targeted read for a report candidate set; does not mutate classifications. */
    @Query("select c from Classification c join fetch c.rawDoc d join fetch d.source " +
           "where d.id in :rawDocIds")
    List<Classification> findByRawDocIdIn(@org.springframework.data.repository.query.Param("rawDocIds")
                                           List<Long> rawDocIds);
}
