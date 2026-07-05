package com.marketradar.review;

import org.springframework.stereotype.Service;
import com.marketradar.domain.InterpretedClaim;
import com.marketradar.domain.RawDoc;

/**
 * ⚠️ PLACEHOLDER (Batch 4) — Impact Scorer thật (công thức P + 2 điểm
 * Consequence/Uncertainty, bước 9 sequence) sẽ THAY THẾ class này.
 * Rule tối thiểu, deterministic, auditable:
 *
 *   1. Doc từ nguồn tier 1 (chính phủ/regulator) → T3
 *      — chính là hard-override "văn bản pháp lý chính thức → tối thiểu T3"
 *        trong kiến trúc đầy đủ, nên rule này SỐNG SÓT qua cả Impact Scorer thật.
 *   2. EXEC_SUMMARY (rawDoc null) → T3 — câu cấp report, consequence cao.
 *   3. DEMO_INJECT → T3 — đảm bảo demo claim vào Reviewer Console.
 *   4. Còn lại (tin sản phẩm từ media, scope MVP) → T1
 *      — diện "sample" theo E1: ENTAILED thì tự xuất bản.
 *
 * Ánh xạ tier → review theo E1: T0-T1 sample · T2 một reviewer ·
 * T3 functional owner (+legal) · T4 dual approval. MVP chỉ có MỘT hàng đợi
 * review chung — phân biệt reviewer theo vai trò là việc của bản pilot.
 */
@Service
public class RiskTierRouter {

    // Logic thuần nằm ở ReviewRules (dep-free, test standalone được) — đây chỉ là adapter domain.
    public String assignTier(RawDoc doc, InterpretedClaim.Origin origin) {
        boolean demo = origin == InterpretedClaim.Origin.DEMO_INJECT;
        boolean exec = doc == null;
        int sourceTier = (doc != null && doc.getSource() != null) ? doc.getSource().getTier() : 3;
        return ReviewRules.assignTier(demo, exec, sourceTier);
    }

    public boolean requiresHumanReview(String tier) {
        return ReviewRules.requiresHumanReview(tier);
    }
}
