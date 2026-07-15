package com.marketradar.product;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** Explicit operator action for rebuilding the exact 7/30/90-day Product editions. */
@Service
public class ProductCadenceRegenerationService {

    private final ProductBriefService briefs;

    public ProductCadenceRegenerationService(ProductBriefService briefs) {
        this.briefs = briefs;
    }

    public List<Result> regenerateAll() {
        List<Result> results = new ArrayList<>();
        for (ProductReportCadence cadence : ProductReportCadence.values()) {
            try {
                ProductBriefEdition edition = briefs.regenerate(cadence.days());
                results.add(new Result(cadence, edition.getStatus(), edition.getEditionCode(),
                        edition.getFailureMessage()));
            } catch (RuntimeException failure) {
                results.add(new Result(cadence, ProductBriefEdition.Status.GENERATION_FAILED,
                        null, failure.getMessage()));
            }
        }
        return List.copyOf(results);
    }

    public record Result(ProductReportCadence cadence, ProductBriefEdition.Status status,
                         String editionCode, String detail) {}
}
