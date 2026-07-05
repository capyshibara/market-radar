package vn.techcomlife.marketradar.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.techcomlife.marketradar.domain.RawDoc;

public interface RawDocRepository extends JpaRepository<RawDoc, Long> {
    boolean existsByContentHash(String contentHash);
}
