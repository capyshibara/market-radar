package com.marketradar.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import com.marketradar.domain.RawDoc;

import java.util.List;
import java.util.Optional;

public interface RawDocRepository extends JpaRepository<RawDoc, Long> {
    boolean existsByContentHash(String contentHash);

    /**
     * Batch 9 (fix Hanh 2026-07-14): thay cho existsByUrlAndParseStatus cũ — check
     * theo fullTextFetched, KHÔNG phải chỉ "URL tồn tại". Doc title-only từ trước
     * khi có tính năng này (fullTextFetched=false mặc định) vẫn được backfill.
     */
    Optional<RawDoc> findFirstByUrlOrderByIdAsc(String url);

    /** Batch 10: join fetch source — open-in-view=false nên lazy access ngoài
     * transaction (vd doc.getSource() ở /pipeline/history) vỡ LazyInitializationException
     * nếu không fetch sẵn (cùng lý do ClassificationRepository.findAllForDisplay() có). */
    @Query("select d from RawDoc d join fetch d.source")
    List<RawDoc> findAllWithSource();
}
