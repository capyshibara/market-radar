package com.marketradar.synthesize;

import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.InterpretedClaim.Bucket;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Phase 3 — gom EvidenceFact xuyên nhiều RawDoc thành candidate theo 7 bucket của BI report
 * (xem chats/chat1.md phần thảo luận kiến trúc). CODE THUẦN, không AI — 3/4 tiêu chí materiality
 * (specificity/recency/corroboration) tính được bằng code; chỉ "decision relevance" cần LLM
 * (Interpreter.interpretSynthesis tự đánh giá lúc viết).
 *
 * MACRO_ECONOMIC: KHÔNG buildable với EvidenceFact hiện tại (không có trường chỉ số vĩ mô
 * GDP/FDI/lạm phát nào) — bỏ qua CÓ CHỦ ĐÍCH, không giả lập bằng field không đúng bản chất.
 * Cần domain model riêng (VD MacroIndicator) khi thực sự có nhu cầu.
 */
@Component
public class BucketGrouper {

    private static final List<String> TECH_KEYWORDS = List.of(
            "ai", "trí tuệ nhân tạo", "insurtech", "số hoá", "số hóa", "chuyển đổi số",
            "digital", "chatbot", "ứng dụng di động", "app ");

    public record Candidate(Bucket bucket, String subjectKey, List<EvidenceFact> facts, List<String> openGaps) {}

    /**
     * @param homeCompany  tên công ty nhà (marketradar.home-company) — rỗng thì bỏ qua STRATEGIC_COMPARISON,
     *                     KHÔNG hardcode brand (đã chốt khi thiết kế).
     * @param recencyDays  chỉ tính fact có eventDate không cũ hơn N ngày (null eventDate luôn được cho qua —
     *                     không đoán khi thiếu dữ liệu).
     */
    public List<Candidate> groupCandidates(List<EvidenceFact> facts, String homeCompany, int recencyDays) {
        List<Candidate> out = new ArrayList<>();
        LocalDate cutoff = LocalDate.now().minusDays(recencyDays);

        // Materiality code-based: specificity (có company/product/ngày) + recency (không quá cũ).
        List<EvidenceFact> eligible = facts.stream()
                .filter(f -> f.getCompany() != null || f.getProductName() != null || f.getEventDate() != null)
                .filter(f -> f.getEventDate() == null || !f.getEventDate().isBefore(cutoff))
                .toList();

        Map<String, List<EvidenceFact>> byCompany = groupBy(eligible, EvidenceFact::getCompany);
        for (var e : byCompany.entrySet()) {
            out.add(new Candidate(Bucket.COMPANY_EVENT, e.getKey(), e.getValue(), openGaps(e.getValue())));
        }

        Map<String, List<EvidenceFact>> byCategory = groupBy(eligible, EvidenceFact::getCategory);
        for (var e : byCategory.entrySet()) {
            if (distinctCompanies(e.getValue()) >= 2) {
                out.add(new Candidate(Bucket.COMPETITIVE_THEME, e.getKey(), e.getValue(), openGaps(e.getValue())));
            }
        }

        if (homeCompany != null && !homeCompany.isBlank()) {
            // Gộp fact xuyên TẤT CẢ category mà 2 công ty cùng có mặt vào 1 candidate/cặp —
            // không lặp theo từng category riêng (nếu không, 2 công ty trùng ≥2 category sẽ
            // sinh ≥2 candidate CÙNG subjectKey, SynthesisJob dedup theo subjectKey chỉ giữ
            // candidate đầu → bằng chứng từ category còn lại bị rơi mất khỏi claim so sánh).
            Set<String> homeCategories = eligible.stream()
                    .filter(f -> homeCompany.equalsIgnoreCase(f.getCompany()))
                    .map(EvidenceFact::getCategory).filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            Set<String> competitors = eligible.stream()
                    .filter(f -> f.getCategory() != null && homeCategories.contains(f.getCategory()))
                    .map(EvidenceFact::getCompany)
                    .filter(c -> c != null && !c.equalsIgnoreCase(homeCompany))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            for (String competitor : competitors) {
                List<EvidenceFact> pairFacts = eligible.stream()
                        .filter(f -> f.getCategory() != null && homeCategories.contains(f.getCategory()))
                        .filter(f -> homeCompany.equalsIgnoreCase(f.getCompany())
                                || competitor.equalsIgnoreCase(f.getCompany()))
                        .toList();
                out.add(new Candidate(Bucket.STRATEGIC_COMPARISON,
                        homeCompany + " vs " + competitor, pairFacts, openGaps(pairFacts)));
            }
        }

        Map<String, List<EvidenceFact>> metricsByCategory = groupBy(
                eligible.stream().filter(f -> f.getFactType() == EvidenceFact.FactType.METRIC).toList(),
                EvidenceFact::getCategory);
        for (var e : metricsByCategory.entrySet()) {
            if (distinctCompanies(e.getValue()) >= 2) {
                out.add(new Candidate(Bucket.MARKET_SHARE_OR_AWARD, e.getKey(), e.getValue(), openGaps(e.getValue())));
            }
        }

        // SCHEDULED_EVENT: khác chiều với recency ở trên — ở ĐÂY cần eventDate TƯƠNG LAI,
        // nên dùng facts gốc (không qua eligible/cutoff quá khứ).
        Map<String, List<EvidenceFact>> futureByCompany = groupBy(
                facts.stream().filter(f -> f.getEventDate() != null && f.getEventDate().isAfter(LocalDate.now())).toList(),
                EvidenceFact::getCompany);
        for (var e : futureByCompany.entrySet()) {
            out.add(new Candidate(Bucket.SCHEDULED_EVENT, e.getKey(), e.getValue(), openGaps(e.getValue())));
        }

        List<EvidenceFact> techFacts = eligible.stream().filter(BucketGrouper::looksLikeTechAiSignal).toList();
        if (!techFacts.isEmpty()) {
            out.add(new Candidate(Bucket.TECH_AI_SIGNAL, "AI/Insurtech", techFacts, openGaps(techFacts)));
        }

        return out;
    }

    private static Map<String, List<EvidenceFact>> groupBy(
            List<EvidenceFact> facts, java.util.function.Function<EvidenceFact, String> key) {
        Map<String, List<EvidenceFact>> out = new LinkedHashMap<>();
        for (EvidenceFact f : facts) {
            String k = key.apply(f);
            if (k != null) out.computeIfAbsent(k, x -> new ArrayList<>()).add(f);
        }
        return out;
    }

    private static long distinctCompanies(List<EvidenceFact> facts) {
        return facts.stream().map(EvidenceFact::getCompany).filter(Objects::nonNull).distinct().count();
    }

    private static boolean looksLikeTechAiSignal(EvidenceFact f) {
        String hay = (nullToEmpty(f.getCategory()) + " " + nullToEmpty(f.getProductName())).toLowerCase(Locale.ROOT);
        return TECH_KEYWORDS.stream().anyMatch(hay::contains);
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    /** Corroboration (materiality tiêu chí thứ 3): chỉ 1 nguồn thì gắn cờ, KHÔNG loại — để LLM/reviewer tự cân nhắc. */
    private static List<String> openGaps(List<EvidenceFact> facts) {
        long distinctSources = facts.stream()
                .map(f -> f.getRawDoc().getSource().getId()).distinct().count();
        return distinctSources < 2
                ? List.of("Chỉ 1 nguồn cho nhóm này — chưa có xác nhận chéo (corroboration thấp)")
                : List.of();
    }
}
