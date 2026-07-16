package com.marketradar.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import com.marketradar.domain.Source;
import java.util.List;
import java.util.Optional;

public interface SourceRepository extends JpaRepository<Source, Long> {
    List<Source> findByActiveTrue();
    List<Source> findAllByOrderByTierAsc();
    Optional<Source> findByCode(String code);
    Optional<Source> findFirstByAllowedHostIgnoreCase(String allowedHost);
    boolean existsByCodeIgnoreCase(String code);
    boolean existsByFetchUrl(String fetchUrl);
}
