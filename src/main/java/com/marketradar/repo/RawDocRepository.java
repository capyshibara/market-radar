package com.marketradar.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import com.marketradar.domain.RawDoc;

import java.util.Optional;

public interface RawDocRepository extends JpaRepository<RawDoc, Long> {
    boolean existsByContentHash(String contentHash);

    /**
     * Batch 9 (fix Hanh 2026-07-14): thay cho existsByUrlAndParseStatus cũ — check
     * theo fullTextFetched, KHÔNG phải chỉ "URL tồn tại". Doc title-only từ trước
     * khi có tính năng này (fullTextFetched=false mặc định) vẫn được backfill.
     */
    Optional<RawDoc> findFirstByUrlOrderByIdAsc(String url);
}
