package com.marketradar.review;

import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.InterpretedClaim;
import com.marketradar.intelligence.CompetitorRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Chốt chặn quy kết thực thể (bước "Validate" trong quy trình 5 bước của Strategy):
 * claim nói về công ty X thì bằng chứng phải thực sự nói về X — không phải về một
 * thực thể trùng thương hiệu ở thị trường khác.
 *
 * Deterministic, KHÔNG gọi LLM — chạy được cả khi verifier ở chế độ stub, và kết
 * quả tái lập được (cùng input luôn cùng cảnh báo). Guard chỉ CẢNH BÁO cho người
 * duyệt, không tự chặn: quyết định cuối cùng vẫn là của con người, đúng nguyên
 * tắc "máy lọc thô, người là cổng cuối".
 *
 * Hai loại cảnh báo:
 *  - CONFUSABLE_ENTITY (nặng): bằng chứng chứa marker của thực thể dễ nhầm
 *    (vd claim về Prudential VN nhưng evidence nhắc "Prudential Financial"/"PGIM"
 *    — công ty Mỹ không liên quan).
 *  - SUBJECT_NOT_IN_EVIDENCE (nhẹ): claim nêu tên công ty nhưng không bí danh
 *    nào của công ty đó xuất hiện trong bằng chứng được trích dẫn.
 */
@Component
public class EntityAttributionGuard {

    public enum Code { CONFUSABLE_ENTITY, SUBJECT_NOT_IN_EVIDENCE }

    public record Warning(Code code, String competitor, String messageVi, String messageEn) {}

    private final CompetitorRegistry registry;

    public EntityAttributionGuard(CompetitorRegistry registry) {
        this.registry = registry;
    }

    public List<Warning> check(InterpretedClaim claim, List<EvidenceFact> citedFacts) {
        if (claim == null) return List.of();
        String claimText = (claim.getTextVi() == null ? "" : claim.getTextVi()) + "\n"
                + (claim.getTextEn() == null ? "" : claim.getTextEn());
        StringBuilder evidence = new StringBuilder();
        if (claim.getRawDoc() != null && claim.getRawDoc().getTitle() != null) {
            evidence.append(claim.getRawDoc().getTitle()).append('\n');
        }
        if (citedFacts != null) {
            for (EvidenceFact f : citedFacts) {
                if (f == null) continue;
                if (f.getSpanText() != null) evidence.append(f.getSpanText()).append('\n');
                if (f.getRawDoc() != null && f.getRawDoc().getTitle() != null) {
                    evidence.append(f.getRawDoc().getTitle()).append('\n');
                }
            }
        }
        String evidenceText = evidence.toString();

        List<Warning> warnings = new ArrayList<>();
        for (String competitor : registry.detectAllCompetitors(claimText)) {
            for (CompetitorRegistry.Confusable cf
                    : registry.confusableMarkersIn(competitor, evidenceText)) {
                warnings.add(new Warning(Code.CONFUSABLE_ENTITY, competitor,
                        "Bằng chứng có dấu hiệu nói về \"" + cf.confusableName()
                                + "\" — KHÔNG phải " + competitor
                                + ". Kiểm tra kỹ trước khi duyệt: hai thực thể chỉ trùng thương hiệu.",
                        "Evidence appears to reference \"" + cf.confusableName()
                                + "\" — NOT " + competitor
                                + ". Verify before approving: these entities merely share a brand name."));
            }
            if (!evidenceText.isBlank() && !registry.mentions(competitor, evidenceText)) {
                warnings.add(new Warning(Code.SUBJECT_NOT_IN_EVIDENCE, competitor,
                        "Claim nhắc đến " + competitor
                                + " nhưng không bí danh nào của công ty này xuất hiện trong bằng chứng trích dẫn.",
                        "The claim mentions " + competitor
                                + " but no alias of this company appears in the cited evidence."));
            }
        }
        return List.copyOf(warnings);
    }
}
