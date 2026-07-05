package com.marketradar.report;

import com.marketradar.domain.InterpretedClaim.ReviewStatus;
import com.marketradar.repo.InterpretedClaimRepository;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Batch 8: the ops sidebar (fragments/ops-sidebar.html) is included on every
 * ops page and shows a live "claims needing review" badge — rather than every
 * controller computing it, a single ControllerAdvice injects it into every
 * model app-wide (harmless extra attribute on pages that don't render the
 * sidebar, e.g. the weekly report).
 */
@ControllerAdvice
public class OpsSidebarAdvice {

    private final InterpretedClaimRepository claims;

    public OpsSidebarAdvice(InterpretedClaimRepository claims) {
        this.claims = claims;
    }

    @ModelAttribute("queueCount")
    public long queueCount() {
        return claims.countByReviewStatus(ReviewStatus.PENDING_REVIEW);
    }
}
