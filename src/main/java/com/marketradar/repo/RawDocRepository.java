package com.marketradar.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import com.marketradar.domain.RawDoc;

public interface RawDocRepository extends JpaRepository<RawDoc, Long> {
    boolean existsByContentHash(String contentHash);

    /** Batch 9: guard trước khi fetch toàn văn bài listing — URL đã ingest OK thì khỏi fetch lại. */
    boolean existsByUrlAndParseStatus(String url, RawDoc.ParseStatus parseStatus);
}
