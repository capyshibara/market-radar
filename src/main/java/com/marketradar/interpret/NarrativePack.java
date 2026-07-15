package com.marketradar.interpret;

import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.InterpretedClaim;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Batch 10 — input của Interpreter#interpretChapterNarrative. KHÔNG phải EvidencePack thô:
 * model thấy CẢ khung phân tích đã qua Gate L1 (approvedClaims: why/implication) LẪN span
 * nguyên văn gốc mà chúng trích dẫn (facts) — tổng hợp xuyên tài liệu dựa trên công sức
 * Extract→Interpret đã kiểm chứng, không đi vòng lại raw doc và không bịa phân tích mới
 * ngoài evidence pack đã có.
 */
public record NarrativePack(Chapter chapter, List<InterpretedClaim> approvedClaims, List<EvidenceFact> facts) {

    public Map<String, EvidenceFact> byCode() {
        return facts.stream().collect(Collectors.toMap(EvidenceFact::getFactCode, Function.identity()));
    }

    public Set<String> codes() {
        return facts.stream().map(EvidenceFact::getFactCode).collect(Collectors.toSet());
    }

    /** CHAPTER FOCUS (góc nhìn riêng của chương) + APPROVED ANALYSIS (claim đã qua Gate L1)
     * + EVIDENCE (span nguyên văn, tái dùng EvidencePack#renderForPrompt để không lệch
     * định dạng giữa hai loại pack). */
    public String renderForPrompt() {
        StringBuilder sb = new StringBuilder("CHAPTER FOCUS — ")
                .append(chapter.titleVi).append(":\n")
                .append(chapter.narrativeFocusVi()).append("\n\n")
                .append("APPROVED ANALYSIS:\n");
        for (InterpretedClaim c : approvedClaims) {
            sb.append("--- ").append(c.getClaimCode()).append(" (").append(c.getSlot()).append(") ---\n");
            sb.append("vi: ").append(c.getTextVi()).append('\n');
            sb.append("en: ").append(c.getTextEn()).append('\n');
            sb.append("fact_codes: ").append(c.getFactCodesCsv()).append('\n');
        }
        sb.append('\n').append(new EvidencePack(null, facts).renderForPrompt());
        return sb.toString();
    }
}
