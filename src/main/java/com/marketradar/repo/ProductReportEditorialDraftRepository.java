package com.marketradar.repo;

import com.marketradar.product.ProductReportCadence;
import com.marketradar.product.ProductReportEditorialDraft;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProductReportEditorialDraftRepository
        extends JpaRepository<ProductReportEditorialDraft, Long> {
    Optional<ProductReportEditorialDraft> findByCadenceAndLanguage(
            ProductReportCadence cadence, String language);
}
