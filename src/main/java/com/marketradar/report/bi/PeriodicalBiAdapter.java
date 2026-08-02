package com.marketradar.report.bi;

import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.InterpretedClaim;
import com.marketradar.intelligence.CompetitorRegistry;
import com.marketradar.product.CurrentProductNewsItem;
import com.marketradar.product.ProductBriefInsight;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bản BI report của MỘT kỳ report định kỳ (tuần/tháng/quý).
 *
 * Nguồn nội dung, theo đúng quy trình 5 bước của Strategy (thu thập chọn lọc →
 * chọn lọc/xếp hạng → xác thực → phân tích → quyết định):
 *
 *  1. CLAIM ĐÃ DUYỆT TAY ở /review (APPROVED/EDITED_APPROVED/FORCE_APPROVED, cộng
 *     AUTO_APPROVED vốn đòi ENTAILED) — đây là kênh CHÍNH: công sức của người duyệt
 *     chảy thẳng vào báo cáo. Trước đây kênh này KHÔNG tồn tại (duyệt xong không đi
 *     đâu cả) — đó là lý do báo cáo trống dù hàng đợi duyệt đầy.
 *  2. Insight đã tổng hợp của Product brief (executiveInsights/watchSignals) — kênh
 *     máy, giữ nguyên fail-closed như cũ.
 *  3. Tin hiện hành (currentNews) — kênh máy, CHỈ những tài liệu chưa có claim nào
 *     được duyệt (bản đã qua tay người thay thế bản thô của cùng tài liệu).
 *
 * subjectKey của claim/tin suy từ CompetitorRegistry (tên công ty thật được nhắc
 * trong nội dung) — nhờ vậy trang "Điểm nổi bật đối thủ" nhóm theo đúng công ty
 * thay vì theo nhãn chủ đề chung chung.
 */
@Component
public class PeriodicalBiAdapter {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

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
        Set<Long> curatedDocIds = new HashSet<>();
        for (InterpretedClaim claim : approved) {
            if (claim.getRawDoc() != null) curatedDocIds.add(claim.getRawDoc().getId());
            List<BiCitation> citations = citationsFor(claim);
            citations.forEach(cit -> sourceLines.add(
                    cit.label() + (cit.tierNote() != null ? " (" + cit.tierNote() + ")" : "")));
            String subject = registry.detectCompetitor(claim.getTextVi() + "\n"
                            + (claim.getRawDoc() != null && claim.getRawDoc().getTitle() != null
                                    ? claim.getRawDoc().getTitle() : ""))
                    .orElse(null);
            boolean reportLevel = claim.getSlot() == InterpretedClaim.Slot.EXEC_SUMMARY
                    || claim.getSlot() == InterpretedClaim.Slot.NARRATIVE;
            findings.add(new BiFinding(
                    reportLevel ? BiFinding.COMPETITIVE_THEME : BiFinding.COMPANY_EVENT,
                    subject,
                    claim.getTextVi(), claim.getTextEn(),
                    claim.getSlot() == InterpretedClaim.Slot.EXEC_SUMMARY,
                    citations));
        }

        // ---- Kênh 2: insight tổng hợp của Product brief (giữ fail-closed cũ) ----
        for (ProductBriefInsight insight : snapshot.executiveInsights()) {
            findings.add(toFinding(insight, snapshot, true));
        }
        for (ProductBriefInsight insight : snapshot.watchSignals()) {
            findings.add(toFinding(insight, snapshot, false));
        }

        // ---- Kênh 3: tin hiện hành, trừ tài liệu đã có bản duyệt tay ----
        for (CurrentProductNewsItem item : snapshot.currentNews()) {
            if (curatedDocIds.contains(item.rawDocId())) continue;
            String subject = registry.detectCompetitor(
                            item.title() + "\n" + item.verbatimEvidenceSpan())
                    .orElse(item.getTopicLabelVi());
            findings.add(new BiFinding(BiFinding.COMPANY_EVENT, subject,
                    item.title() + (item.getDisplaySummaryVi() != null && !item.getDisplaySummaryVi().isBlank()
                            ? " — " + item.getDisplaySummaryVi() : ""),
                    item.title() + (item.getDisplaySummaryEn() != null && !item.getDisplaySummaryEn().isBlank()
                            ? " — " + item.getDisplaySummaryEn() : ""),
                    false,
                    List.of(new BiCitation(item.sourceName(), "T" + item.sourceTier(),
                            item.hasExternalSourceLink() ? item.sourceUrl() : null))));
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
        openGaps.add("Vĩ mô ngành, Lịch công bố sắp tới, Thị phần/Giải thưởng, Tín hiệu Tech/AI, "
                + "So sánh chiến lược — kho whitelist crawl chưa có nguồn cho các mục này; "
                + "dùng Deep Research để bổ sung theo yêu cầu cụ thể.");
        if (homeCompany == null || homeCompany.isBlank()) {
            openGaps.add("So sánh chiến lược cần cấu hình marketradar.home-company để xác định công ty gốc.");
        }

        return new BiReportContent(title, period, homeCompany,
                ZonedDateTime.now().format(TS_FMT), docCount,
                findings, List.copyOf(sourceLines), openGaps);
    }

    /**
     * Giữ báo cáo trung thực với KỲ của nó: claim vào kỳ theo ngày đăng của tài liệu
     * gốc; tài liệu không có ngày đăng (nguồn không cung cấp — không đoán) hoặc claim
     * cấp report (rawDoc null) thì theo ngày claim được tạo.
     */
    private static boolean inWindow(InterpretedClaim claim, LocalDate start, LocalDate end) {
        if (start == null || end == null) return true;
        LocalDate anchor = null;
        if (claim.getRawDoc() != null && claim.getRawDoc().getPublishedAt() != null) {
            anchor = claim.getRawDoc().getPublishedAt().atZone(REPORT_ZONE).toLocalDate();
        } else if (claim.getCreatedAt() != null) {
            anchor = claim.getCreatedAt().atZone(REPORT_ZONE).toLocalDate();
        }
        if (anchor == null) return false;
        return !anchor.isBefore(start) && !anchor.isAfter(end);
    }

    /** Trích dẫn của claim = các fact nó cite (Invariant #1: luôn có factCodes khi L1 PASS);
     *  fallback về nguồn của tài liệu gốc nếu fact không resolve được (fact bị deactivate
     *  sau khi duyệt) — vẫn truy vết được, không bao giờ trích dẫn rỗng lặng lẽ. */
    private List<BiCitation> citationsFor(InterpretedClaim claim) {
        List<String> codes = claim.getFactCodesCsv() == null ? List.of()
                : Arrays.stream(claim.getFactCodesCsv().split(","))
                        .map(String::strip).filter(s -> !s.isEmpty()).toList();
        Map<String, BiCitation> unique = new LinkedHashMap<>();
        if (!codes.isEmpty()) {
            for (EvidenceFact f : facts.findAllByFactCodeInForAudit(codes)) {
                String label = f.getRawDoc().getPublisherName() != null
                        && !f.getRawDoc().getPublisherName().isBlank()
                        ? f.getRawDoc().getPublisherName()
                        : f.getRawDoc().getSource().getName();
                unique.putIfAbsent(label, new BiCitation(label,
                        "T" + f.getRawDoc().getSource().getTier(), f.getRawDoc().getUrl()));
            }
        }
        if (unique.isEmpty() && claim.getRawDoc() != null) {
            String label = claim.getRawDoc().getPublisherName() != null
                    && !claim.getRawDoc().getPublisherName().isBlank()
                    ? claim.getRawDoc().getPublisherName()
                    : claim.getRawDoc().getSource().getName();
            unique.put(label, new BiCitation(label,
                    "T" + claim.getRawDoc().getSource().getTier(), claim.getRawDoc().getUrl()));
        }
        return List.copyOf(unique.values());
    }

    private BiFinding toFinding(ProductBriefInsight insight, ProductReportAdapter.Snapshot snapshot, boolean highlight) {
        List<BiCitation> citations = snapshot.evidenceByInsight()
                .getOrDefault(insight.getId(), List.of()).stream()
                .map(f -> new BiCitation(f.getRawDoc().getSource().getName(),
                        "T" + f.getRawDoc().getSource().getTier(), null))
                .distinct()
                .toList();
        String textVi = insight.getHeadlineVi()
                + (insight.getSoWhatVi() != null && !insight.getSoWhatVi().isBlank() ? " " + insight.getSoWhatVi() : "");
        String textEn = insight.getHeadlineEn()
                + (insight.getSoWhatEn() != null && !insight.getSoWhatEn().isBlank() ? " " + insight.getSoWhatEn() : "");
        return new BiFinding(BiFinding.COMPETITIVE_THEME, insight.getThemeCode(), textVi, textEn, highlight, citations);
    }
}
