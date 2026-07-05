package com.marketradar.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import com.marketradar.domain.RawDoc;

public interface RawDocRepository extends JpaRepository<RawDoc, Long> {
    boolean existsByContentHash(String contentHash);
}
