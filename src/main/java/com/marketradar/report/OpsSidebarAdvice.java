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
    private final boolean demoMode;

    public OpsSidebarAdvice(InterpretedClaimRepository claims,
                            @org.springframework.beans.factory.annotation.Value(
                                    "${marketradar.demo-mode:false}") boolean demoMode) {
        this.claims = claims;
        this.demoMode = demoMode;
    }

    @ModelAttribute("queueCount")
    public long queueCount() {
        return claims.countByReviewStatus(ReviewStatus.PENDING_REVIEW);
    }

    /**
     * marketradar.demo-mode=true thu gọn điều hướng cho demo Strategy: ẩn các trang
     * vận hành pipeline/audit kỹ thuật khỏi sidebar, chỉ giữ luồng 5 bước (nguồn
     * chọn lọc → duyệt → báo cáo → research). CHỈ ẩn khỏi menu — mọi route vẫn
     * hoạt động; phòng ban khác bật lại bằng cách tắt cờ, không mất chức năng nào.
     */
    @ModelAttribute("demoMode")
    public boolean demoMode() {
        return demoMode;
    }
}
