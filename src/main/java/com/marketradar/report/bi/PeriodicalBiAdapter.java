package com.marketradar.report.bi;

import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.InterpretedClaim;
import com.marketradar.domain.RawDoc;
import com.marketradar.domain.Source;
import com.marketradar.intelligence.CompetitorRegistry;
import com.marketradar.product.ProductMarketScopeClassifier;
import com.marketradar.repo.EvidenceFactRepository;
import com.marketradar.repo.InterpretedClaimRepository;
import com.marketradar.report.ProductReportAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bản BI report của MỘT kỳ report định kỳ (tuần/tháng/quý).
 *
 * Nguồn nội dung DUY NHẤT: CLAIM ĐÃ DUYỆT TAY ở /review (APPROVED/EDITED_APPROVED/
 * FORCE_APPROVED, cộng AUTO_APPROVED vốn đòi ENTAILED) — công sức của người duyệt
 * chảy thẳng vào báo cáo.
 *
 * 2026-08-03 (feedback: "bỏ bớt agent dư thừa" + Router mới): trước đây có thêm 2 kênh
 * máy — Insight Product brief (executiveInsights/watchSignals) và Tin hiện hành
 * (currentNews) — cả 2 thuộc nhánh Product/Sales/Compliance đã inactivate
 * (marketradar.legacy-desks.enabled=false, xem LegacyDeskAccessGuard). Bỏ hẳn 2 kênh
 * đó khỏi luồng Strategy: báo cáo giờ chỉ còn 1 nguồn duy nhất, sạch hơn và không phụ
 * thuộc dữ liệu từ 1 nhánh đã tắt.
 *
 * Nhãn bucket/subjectKey/severity/kpi... giờ đọc TRỰC TIẾP từ EvidenceFact đã qua
 * Router (xem FactExtractionJob#route) — không còn suy từ "claim đến từ bước nào của
 * pipeline" (rule cũ reportLevel?COMPETITIVE_THEME:COMPANY_EVENT). Claim/fact CHƯA qua
 * Router (crawl trước khi có Router, hoặc chạy STUB) vẫn rơi về đúng rule cũ đó —
 * không mất dữ liệu, chỉ là chưa được phân loại chính xác bằng nội dung.
 */
@Component
public class PeriodicalBiAdapter {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /** 2026-08-03: 5 bucket mà Interpreter có thể tự gán cho claim WHY_MATTERS (xem
     *  Interpreter#VALID_BI_BUCKETS) — claim với bucket khác/null rơi về COMPETITIVE_THEME/
     *  COMPANY_EVENT như trước. Dùng chung danh sách này để biết mục nào THẬT SỰ trống trong
     *  kỳ này, thay vì luôn báo cả 5 mục là "chưa có nguồn" bất kể có claim hay không. */
    private static final Set<String> SPECIAL_BUCKETS = Set.of(
            BiFinding.MACRO_ECONOMIC, BiFinding.SCHEDULED_EVENT, BiFinding.MARKET_SHARE_OR_AWARD,
            BiFinding.TECH_AI_SIGNAL, BiFinding.STRATEGIC_COMPARISON);

    private static final Map<String, String> BUCKET_LABEL_VI = Map.of(
            BiFinding.MACRO_ECONOMIC, "Vĩ mô ngành",
            BiFinding.SCHEDULED_EVENT, "Lịch công bố sắp tới",
            BiFinding.MARKET_SHARE_OR_AWARD, "Thị phần/Giải thưởng",
            BiFinding.TECH_AI_SIGNAL, "Tín hiệu Tech/AI",
            BiFinding.STRATEGIC_COMPARISON, "So sánh chiến lược");

    private final String homeCompany;
    private final InterpretedClaimRepository claims;
    private final EvidenceFactRepository facts;
    private final CompetitorRegistry registry;

    public PeriodicalBiAdapter(@Value("${marketradar.home-company:}") String homeCompany,
                               InterpretedClaimRepository claims,
                               EvidenceFactRepository facts,
                               CompetitorRegistry registry) {
        this.homeCompany = homeCompany;
        this.claims = claims;
        this.facts = facts;
        this.registry = registry;
    }

    public BiReportContent adapt(String title, String period, ProductReportAdapter.Snapshot snapshot, long docCount) {
        List<BiFinding> findings = new ArrayList<>();
        Set<String> sourceLines = new LinkedHashSet<>();

        // ---- Kênh 1: claim đã duyệt (người là cổng cuối) ----
        List<InterpretedClaim> approved = claims.findForBiReport().stream()
                .filter(c -> inWindow(c, snapshot.windowStart(), snapshot.windowEnd()))
                .toList();
        for (InterpretedClaim claim : approved) {
            List<EvidenceFact> citedFacts = resolveFacts(claim);
            List<BiCitation> citations = citationsFor(claim, citedFacts);
            citations.forEach(cit -> sourceLines.add(
                    cit.label() + (cit.tierNote() != null ? " (" + cit.tierNote() + ")" : "")));
            boolean reportLevel = claim.getSlot() == InterpretedClaim.Slot.EXEC_SUMMARY
                    || claim.getSlot() == InterpretedClaim.Slot.NARRATIVE;
            RoutedLabels routed = resolveRouting(claim, citedFacts, reportLevel);
            // "company" KHÔNG được truyền = subject (tên đối thủ ĐÃ CHUẨN HOÁ theo registry, vd
            // "Prudential Việt Nam" cho mọi claim nhắc "Prudential"): làm vậy sẽ khiến MỌI claim
            // về một đối thủ đã đăng ký bị gắn "Việt Nam" bất kể bằng chứng thực nói về công ty
            // nào — tái tạo đúng lỗi CFO nêu (Prudential plc bị lẫn với Prudential Financial Inc.)
            // ngay trong chính tính năng được xây để hiển thị rủi ro đó minh bạch hơn. Chỉ dùng
            // tín hiệu khách quan: ngôn ngữ/host của NGUỒN đăng ký + host của URL tài liệu.
            Source claimSource = claim.getRawDoc() != null ? claim.getRawDoc().getSource() : null;
            ProductMarketScopeClassifier.MarketPosition market = ProductMarketScopeClassifier.classify(
                    claimSource == null ? null : claimSource.getCode(),
                    claimSource == null ? null : claimSource.getLanguage(),
                    claimSource == null ? null : claimSource.getAllowedHost(),
                    claim.getRawDoc() == null ? null : claim.getRawDoc().getUrl(),
                    claim.getRawDoc() == null ? null : claim.getRawDoc().getPublisherName(),
                    null);
            // subjectKey: ưu tiên Router (gán riêng cho ĐÚNG fact này, từ nguyên văn span) —
            // chỉ rơi về CompetitorRegistry (chuẩn hoá tên nhưng KHÔNG gắn theo fact cụ thể)
            // khi fact chưa qua Router.
            String subject = routed.subjectKey() != null ? routed.subjectKey()
                    : registry.detectCompetitor(claim.getTextVi() + "\n"
                                    + (claim.getRawDoc() != null && claim.getRawDoc().getTitle() != null
                                            ? claim.getRawDoc().getTitle() : ""))
                            .orElse(null);
            findings.add(new BiFinding(
                    routed.bucket(), subject,
                    claim.getTextVi(), claim.getTextEn(),
                    routed.highlight(),
                    citations, routed.severity(), null,
                    market.scope(), market.geography(), null,
                    routed.highlightCardLabel(), routed.severityTrend(),
                    routed.kpiLabel(), routed.kpiValue(),
                    routed.eventDateRangeStart(), routed.eventDateRangeEnd()));
        }

        for (EvidenceFact f : snapshot.references()) {
            sourceLines.add(f.getRawDoc().getSource().getName() + " (T" + f.getRawDoc().getSource().getTier() + ")");
        }

        List<String> openGaps = new ArrayList<>();
        if (approved.isEmpty()) {
            openGaps.add("Chưa có nhận định nào được duyệt ở Reviewer Console (/review) trong kỳ này — "
                    + "duyệt claim ở đó là cách trực tiếp nhất để làm dày báo cáo (mỗi claim duyệt "
                    + "xong xuất hiện ngay tại đây).");
        }
        // 2026-08-03: chỉ báo "chưa có nguồn" cho ĐÚNG mục nào thật sự không có finding nào
        // trong kỳ này — trước đây báo cả 5 mục vô điều kiện dù claim đã duyệt CÓ THỂ đã thuộc
        // đúng mục đó (Interpreter tự gán bi_bucket từ 2026-08-03, xem InterpretedClaim#biBucket).
        Set<String> presentBuckets = findings.stream().map(BiFinding::bucket).collect(java.util.stream.Collectors.toSet());
        List<String> missingBuckets = SPECIAL_BUCKETS.stream()
                .filter(b -> !presentBuckets.contains(b))
                .map(BUCKET_LABEL_VI::get)
                .toList();
        if (!missingBuckets.isEmpty()) {
            openGaps.add(String.join(", ", missingBuckets) + " — chưa có claim đã duyệt nào thuộc "
                    + (missingBuckets.size() == 1 ? "mục này" : "các mục này") + " trong kỳ; "
                    + "dùng Deep Research để bổ sung theo yêu cầu cụ thể, hoặc duyệt thêm claim liên quan ở /review.");
        }
        if (homeCompany == null || homeCompany.isBlank()) {
            openGaps.add("So sánh chiến lược cần cấu hình marketradar.home-company để xác định công ty gốc.");
        }

        return new BiReportContent(title, period, homeCompany,
                ZonedDateTime.now().format(TS_FMT), docCount,
                findings, List.copyOf(sourceLines), openGaps);
    }

    /**
     * Giữ báo cáo trung thực với KỲ của nó: claim vào kỳ theo ngày đăng của tài liệu gốc; claim
     * cấp report (rawDoc null, vd tóm tắt điều hành) thì theo ngày claim được tạo — đây là ngày
     * DUY NHẤT có ý nghĩa cho loại claim này nên không phải "đoán".
     *
     * 2026-08-03 (feedback: Deep Research tìm rộng khắp web, gặp nhiều trang không có metadata
     * ngày rõ ràng hơn hẳn so với crawl whitelist đã chọn lọc từ trước): TRƯỚC ĐÂY, khi RawDoc CÓ
     * nhưng không xác định được publishedAt, code từng fallback về ngày TẠO CLAIM — vô tình biến
     * 1 sự kiện cũ (vd hợp tác ký từ 2015) thành như thể mới xảy ra tuần này chỉ vì trang nguồn
     * thiếu metadata ngày. Giờ trường hợp đó bị LOẠI khỏi báo cáo theo kỳ thẳng — "không xác định
     * được ngày thật" phải nghĩa là "không đưa vào kỳ nào", không phải "coi như hôm nay".
     */
    private static boolean inWindow(InterpretedClaim claim, LocalDate start, LocalDate end) {
        if (start == null || end == null) return true;
        LocalDate anchor;
        if (claim.getRawDoc() == null) {
            if (claim.getCreatedAt() == null) return false;
            anchor = claim.getCreatedAt().atZone(REPORT_ZONE).toLocalDate();
        } else {
            if (claim.getRawDoc().getPublishedAt() == null) return false; // ngày thật không rõ — loại, không đoán
            anchor = claim.getRawDoc().getPublishedAt().atZone(REPORT_ZONE).toLocalDate();
        }
        return !anchor.isBefore(start) && !anchor.isAfter(end);
    }

    /** Resolve MỘT LẦN các EvidenceFact 1 claim cite — dùng chung cho cả citationsFor lẫn
     *  resolveRouting, tránh query facts 2 lần/claim. */
    private List<EvidenceFact> resolveFacts(InterpretedClaim claim) {
        List<String> codes = claim.getFactCodesCsv() == null ? List.of()
                : Arrays.stream(claim.getFactCodesCsv().split(","))
                        .map(String::strip).filter(s -> !s.isEmpty()).toList();
        return codes.isEmpty() ? List.of() : facts.findAllByFactCodeInForAudit(codes);
    }

    /** Nhãn Router đã gán (bucket/subjectKey/...) cho claim này, suy từ fact ĐẦU TIÊN trong
     *  danh sách cite đã qua Router (biBucket != null) — hoặc rơi về rule cũ khi chưa fact nào
     *  qua Router (xem javadoc lớp: crawl trước khi có Router, hoặc chạy STUB). */
    private record RoutedLabels(String bucket, String subjectKey, String highlightCardLabel,
                                String severity, String severityTrend,
                                String kpiLabel, String kpiValue,
                                LocalDate eventDateRangeStart, LocalDate eventDateRangeEnd,
                                boolean highlight) {}

    private RoutedLabels resolveRouting(InterpretedClaim claim, List<EvidenceFact> citedFacts, boolean reportLevel) {
        EvidenceFact routed = citedFacts.stream().filter(f -> f.getBiBucket() != null).findFirst().orElse(null);
        if (routed != null) {
            return new RoutedLabels(routed.getBiBucket(), routed.getSubjectKey(), routed.getHighlightCardLabel(),
                    routed.getSeverity(), routed.getSeverityTrend(), routed.getKpiLabel(), routed.getKpiValue(),
                    routed.getEventDateRangeStart(), routed.getEventDateRangeEnd(), routed.isHighlight());
        }
        // claim.getBiBucket() null cho tuyệt đại đa số claim (tin công ty thông thường) —
        // SPECIAL_BUCKETS là Set.of(...) nên contains(null) tự ném NPE, phải chặn trước.
        String legacyBucket = claim.getBiBucket() != null && SPECIAL_BUCKETS.contains(claim.getBiBucket())
                ? claim.getBiBucket()
                : (reportLevel ? BiFinding.COMPETITIVE_THEME : BiFinding.COMPANY_EVENT);
        return new RoutedLabels(legacyBucket, null, null, null, null, null, null, null, null,
                claim.getSlot() == InterpretedClaim.Slot.EXEC_SUMMARY);
    }

    /** Trích dẫn của claim = các fact nó cite (Invariant #1: luôn có factCodes khi L1 PASS);
     *  fallback về nguồn của tài liệu gốc nếu fact không resolve được (fact bị deactivate
     *  sau khi duyệt) — vẫn truy vết được, không bao giờ trích dẫn rỗng lặng lẽ. */
    private List<BiCitation> citationsFor(InterpretedClaim claim, List<EvidenceFact> citedFacts) {
        Map<String, BiCitation> unique = new LinkedHashMap<>();
        for (EvidenceFact f : citedFacts) {
            String label = f.getRawDoc().getPublisherName() != null
                    && !f.getRawDoc().getPublisherName().isBlank()
                    ? f.getRawDoc().getPublisherName()
                    : f.getRawDoc().getSource().getName();
            unique.putIfAbsent(label, new BiCitation(label, tierLabel(f.getRawDoc()), f.getRawDoc().getUrl()));
        }
        if (unique.isEmpty() && claim.getRawDoc() != null) {
            String label = claim.getRawDoc().getPublisherName() != null
                    && !claim.getRawDoc().getPublisherName().isBlank()
                    ? claim.getRawDoc().getPublisherName()
                    : claim.getRawDoc().getSource().getName();
            unique.put(label, new BiCitation(label, tierLabel(claim.getRawDoc()), claim.getRawDoc().getUrl()));
        }
        return List.copyOf(unique.values());
    }

    /** 2026-08-03 (feedback: "cần đánh dấu vào đâu đó để biết nguồn của nó là từ deep research"):
     *  claim từ tài liệu Deep Research đi qua ĐÚNG pipeline xác thực như claim thường (xem
     *  DeepResearchService#runVerificationPipeline) nên không có cột/bảng riêng nào để tách —
     *  tín hiệu duy nhất phân biệt được là RawDoc#intakeMethod (OPEN_SEARCH/BROWSER_RENDER thay
     *  vì CRAWLED). Gắn thẳng vào tierNote hiện có (đã là "T1".."T3" hoặc ghi chú tự do theo
     *  design) thay vì thêm field mới vào BiCitation — ít thay đổi hơn, hiển thị ngay ở mọi nơi
     *  đã render tierNote (không cần sửa template). */
    private static String tierLabel(RawDoc rawDoc) {
        String tier = "T" + rawDoc.getSource().getTier();
        boolean fromDeepResearch = rawDoc.getIntakeMethod() == RawDoc.IntakeMethod.OPEN_SEARCH
                || rawDoc.getIntakeMethod() == RawDoc.IntakeMethod.BROWSER_RENDER;
        return fromDeepResearch ? tier + " · Deep Research" : tier;
    }

}
