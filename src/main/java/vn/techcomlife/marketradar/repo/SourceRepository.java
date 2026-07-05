package vn.techcomlife.marketradar.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.techcomlife.marketradar.domain.Source;
import java.util.List;

public interface SourceRepository extends JpaRepository<Source, Long> {
    List<Source> findByActiveTrue();
    List<Source> findAllByOrderByTierAsc();
}
