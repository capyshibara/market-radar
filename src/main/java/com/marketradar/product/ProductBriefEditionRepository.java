package com.marketradar.product;

import com.marketradar.domain.Department;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.time.LocalDate;

public interface ProductBriefEditionRepository extends JpaRepository<ProductBriefEdition, Long> {
    Optional<ProductBriefEdition> findTopByDepartmentOrderByCreatedAtDesc(Department department);
    Optional<ProductBriefEdition> findTopByDepartmentAndWindowStartAndWindowEndOrderByCreatedAtDesc(
            Department department, LocalDate windowStart, LocalDate windowEnd);
    Optional<ProductBriefEdition> findByEditionCode(String editionCode);
    Optional<ProductBriefEdition> findTopByDepartmentAndAlgorithmVersionAndSourceFingerprintOrderByCreatedAtDesc(
            Department department, String algorithmVersion, String sourceFingerprint);
}
