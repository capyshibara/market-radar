package com.marketradar.report;

import com.marketradar.domain.InterpretedClaim.ReviewStatus;
import com.marketradar.repo.InterpretedClaimRepository;
import org.springframework.beans.factory.annotation.Value;
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
    private final boolean legacyDesksEnabled;

    public OpsSidebarAdvice(InterpretedClaimRepository claims,
                            @Value("${marketradar.demo-mode:false}") boolean demoMode,
                            @Value("${marketradar.legacy-desks.enabled:false}") boolean legacyDesksEnabled) {
        this.claims = claims;
        this.demoMode = demoMode;
        this.legacyDesksEnabled = legacyDesksEnabled;
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

    /**
     * marketradar.legacy-desks.enabled=false (default): Product/Sales/Compliance desk
     * pipeline không còn ai dùng — KHÁC demoMode ở trên (chỉ ẩn menu), cờ này còn được
     * LegacyDeskAccessGuard dùng để 404 hẳn route tương ứng. Sidebar dùng lại đúng cờ
     * này để không hiện link dẫn tới route đã tắt.
     */
    @ModelAttribute("legacyDesksEnabled")
    public boolean legacyDesksEnabled() {
        return legacyDesksEnabled;
    }
}
