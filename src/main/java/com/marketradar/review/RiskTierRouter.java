package com.marketradar.review;

import org.springframework.stereotype.Service;
import com.marketradar.domain.InterpretedClaim;
import com.marketradar.domain.RawDoc;
import com.marketradar.domain.SourceAuthority;

/**
 * ⚠️ PLACEHOLDER (Batch 4) — Impact Scorer thật (công thức P + 2 điểm
 * Consequence/Uncertainty, bước 9 sequence) sẽ THAY THẾ class này.
 * Rule tối thiểu, deterministic, auditable:
 *
 *   1. Regulatory/statutory material → T3 because consequence is high.
 *   2. EXEC_SUMMARY (rawDoc null) → T3 — câu cấp report, consequence cao.
 *   3. DEMO_INJECT → T3 — đảm bảo demo claim dễ nhận diện trong audit.
 *   4. Còn lại (tin sản phẩm từ media, scope MVP) → T1
 *
 * 2026-08-02: tier KHÔNG còn quyết định auto-publish hay bắt buộc review (xem
 * ReviewRules.autoPublishable — giờ chỉ dựa verdict Gate L2). Tier vẫn được gán và lưu trên
 * claim, dùng để hiển thị/sắp ưu tiên trong Reviewer Queue — thuần thông tin, không còn là gate.
 */
@Service
public class RiskTierRouter {

    // Logic thuần nằm ở ReviewRules (dep-free, test standalone được) — đây chỉ là adapter domain.
    public String assignTier(RawDoc doc, InterpretedClaim.Origin origin) {
        boolean demo = origin == InterpretedClaim.Origin.DEMO_INJECT;
        boolean exec = doc == null;
        SourceAuthority authority = (doc != null && doc.getSource() != null)
                ? doc.getSource().getAuthority() : SourceAuthority.UNKNOWN;
        boolean highConsequence = authority == SourceAuthority.REGULATOR
                || authority == SourceAuthority.STATUTORY_DISCLOSURE;
        return ReviewRules.assignTier(demo, exec, highConsequence);
    }
}
