package com.marketradar.report;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.InterpretedClaim;
import com.marketradar.domain.InterpretedClaim.Bucket;
import com.marketradar.domain.InterpretedClaim.ReviewStatus;
import com.marketradar.domain.InterpretedClaim.Slot;
import com.marketradar.domain.LabelLog;
import com.marketradar.repo.EvidenceFactRepository;
import com.marketradar.repo.InterpretedClaimRepository;
import com.marketradar.repo.LabelLogRepository;
import com.marketradar.repo.RawDocRepository;
import com.marketradar.repo.SourceRepository;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Phase 4 — Business Intelligence Report: 19-trang-tương-đương nhưng LINH HOẠT số trang thật
 * (xem BiPage). Nguồn dữ liệu: InterpretedClaim slot=SYNTHESIS (do SynthesisJob sinh ra, Phase 3),
 * gom theo Bucket. Chỉ nhận claim đã publish (4 trạng thái *_APPROVED) — giống hệt report tuần,
 * dùng lại nguyên review pipeline, không có đường publish riêng cho BI report.
 *
 * "Macro Update" (bucket MACRO_ECONOMIC) không xuất hiện — EvidenceFact chưa có trường chỉ số
 * vĩ mô nào, không có claim nào thuộc bucket này để hiển thị (xem BucketGrouper) — không phải bug,
 * ghi rõ ở trang Sources & Method thay vì âm thầm bỏ qua.
 */
@Controller
public class BiReportController {

    private final InterpretedClaimRepository claims;
    private final EvidenceFactRepository facts;
    private final SourceRepository sources;
    private final RawDocRepository rawDocs;
    private final LabelLogRepository labelLogs;
    private final PdfExportService pdfExport;
    private final String homeCompany;

    public BiReportController(InterpretedClaimRepository claims, EvidenceFactRepository facts,
                              SourceRepository sources, RawDocRepository rawDocs,
                              LabelLogRepository labelLogs, PdfExportService pdfExport,
                              @Value("${marketradar.home-company:}") String homeCompany) {
        this.claims = claims;
        this.facts = facts;
        this.sources = sources;
        this.rawDocs = rawDocs;
        this.labelLogs = labelLogs;
        this.pdfExport = pdfExport;
        this.homeCompany = homeCompany;
    }

    @GetMapping("/report/bi")
    public String biReport(Model model) {
        model.addAllAttributes(buildBiModel());
        return "bi-report";
    }

    @GetMapping("/report/bi.pdf")
    public ResponseEntity<byte[]> biReportPdf() {
        byte[] pdf = pdfExport.renderBiReportPdf(buildBiModel());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"market-radar-bi-report.pdf\"")
                .body(pdf);
    }

    private Map<String, Object> buildBiModel() {
        Map<String, Object> model = new HashMap<>();
        LocalDate today = LocalDate.now();
        model.put("reportPeriod", "Kỳ báo cáo · " + today.format(DateTimeFormatter.ofPattern("MM/yyyy")));
        model.put("generatedAt", java.time.ZonedDateTime.now()
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")));
        model.put("homeCompany", homeCompany);
        model.put("docCount", rawDocs.count());

        List<EvidenceFact> visibleFacts = facts.findAllForReport().stream()
                .filter(f -> f.getRawDoc().getDuplicateOfId() == null)
                .toList();
        Map<String, EvidenceFact> factByCode = new HashMap<>();
        for (EvidenceFact f : visibleFacts) factByCode.put(f.getFactCode(), f);
        model.put("factByCode", factByCode);

        List<InterpretedClaim> passed = claims.findPublishable(List.of(
                        InterpretedClaim.ReviewStatus.AUTO_APPROVED, ReviewStatus.APPROVED,
                        ReviewStatus.EDITED_APPROVED, ReviewStatus.FORCE_APPROVED)).stream()
                .filter(c -> c.getSlot() == Slot.SYNTHESIS)
                .toList();

        // Sources & Method: chỉ liệt kê nguồn THỰC SỰ được trích dẫn trong báo cáo này — không phải
        // toàn bộ registry (60+ nguồn). Danh sách đầy đủ vừa dài vô ích vừa tràn khỏi trang cố định
        // 816px (bắt được qua render-to-PNG: bảng đè lên folio ở cuối trang).
        List<com.marketradar.domain.Source> citedSources = passed.stream()
                .filter(c -> c.getFactCodesCsv() != null)
                .flatMap(c -> Arrays.stream(c.getFactCodesCsv().split(",")))
                .map(String::trim).map(factByCode::get).filter(Objects::nonNull)
                .map(f -> f.getRawDoc().getSource())
                .distinct()
                .sorted(Comparator.comparing(com.marketradar.domain.Source::getTier))
                .toList();
        model.put("sources", citedSources);

        Map<Bucket, List<InterpretedClaim>> byBucket = passed.stream()
                .collect(Collectors.groupingBy(InterpretedClaim::getBucket, () -> new EnumMap<>(Bucket.class),
                        Collectors.toList()));

        List<InterpretedClaim> themeClaims = byBucket.getOrDefault(Bucket.COMPETITIVE_THEME, List.of());
        List<InterpretedClaim> scheduledClaims = sortByFirstFactDate(
                byBucket.getOrDefault(Bucket.SCHEDULED_EVENT, List.of()), factByCode);
        List<InterpretedClaim> companyEventClaims = sortByFirstFactDate(
                byBucket.getOrDefault(Bucket.COMPANY_EVENT, List.of()), factByCode);
        List<InterpretedClaim> marketShareClaims = byBucket.getOrDefault(Bucket.MARKET_SHARE_OR_AWARD, List.of());
        List<InterpretedClaim> techSignalClaims = byBucket.getOrDefault(Bucket.TECH_AI_SIGNAL, List.of());
        List<InterpretedClaim> comparisonClaims = byBucket.getOrDefault(Bucket.STRATEGIC_COMPARISON, List.of());

        model.put("themeClaims", themeClaims);
        model.put("scheduledClaims", scheduledClaims);
        model.put("companyEventClaims", companyEventClaims);
        model.put("marketShareClaims", marketShareClaims);
        model.put("techSignalClaims", techSignalClaims);
        model.put("comparisonClaims", comparisonClaims);

        // Trang lặp động: 1 trang / công ty (COMPANY_EVENT), 1 trang / cặp so sánh (STRATEGIC_COMPARISON)
        Map<String, List<InterpretedClaim>> competitorPages = companyEventClaims.stream()
                .collect(Collectors.groupingBy(InterpretedClaim::getSubjectKey, LinkedHashMap::new, Collectors.toList()));
        Map<String, List<InterpretedClaim>> comparisonPages = comparisonClaims.stream()
                .collect(Collectors.groupingBy(InterpretedClaim::getSubjectKey, LinkedHashMap::new, Collectors.toList()));
        model.put("competitorPages", competitorPages);
        model.put("comparisonPages", comparisonPages);

        // Tóm tắt điều hành: 3 claim rủi ro cao nhất xuyên mọi bucket (không cần slot riêng —
        // khác report tuần, BI report không có EXEC_SUMMARY riêng vì nội dung đã tổng hợp sẵn).
        List<InterpretedClaim> keyFindings = passed.stream()
                .sorted(Comparator.comparing(InterpretedClaim::getRiskTier).reversed()
                        .thenComparing(InterpretedClaim::getId))
                .limit(3)
                .toList();
        model.put("keyFindings", keyFindings);

        // Plan: danh sách trang THẬT SỰ sẽ in — quyết định TRƯỚC, đánh số ở đây, template chỉ render theo.
        List<BiPage> pages = new ArrayList<>();
        int n = 1;
        pages.add(new BiPage(n++, "COVER", "Bìa", null));
        pages.add(new BiPage(n++, "TOC", "Mục lục", null));
        pages.add(new BiPage(n++, "EXEC", "Tóm tắt điều hành", null));
        if (!themeClaims.isEmpty()) pages.add(new BiPage(n++, "THEME", "Xu hướng cạnh tranh", null));
        if (!scheduledClaims.isEmpty()) pages.add(new BiPage(n++, "SCHEDULED", "Lịch sắp tới", null));
        if (!companyEventClaims.isEmpty()) pages.add(new BiPage(n++, "COMPANY_TIMELINE", "Diễn biến theo công ty", null));
        if (!marketShareClaims.isEmpty()) pages.add(new BiPage(n++, "MARKET_SHARE", "Thị phần / Giải thưởng", null));
        if (!techSignalClaims.isEmpty()) pages.add(new BiPage(n++, "THREATMAP", "Bản đồ tín hiệu AI/Insurtech", null));
        for (String company : competitorPages.keySet()) {
            pages.add(new BiPage(n++, "COMPETITOR", company, company));
        }
        for (String pair : comparisonPages.keySet()) {
            pages.add(new BiPage(n++, "COMPARISON", pair, pair));
        }
        pages.add(new BiPage(n++, "SOURCES", "Nguồn & Phương pháp", null));
        pages.add(new BiPage(n, "BACK", "Trang cuối", null));
        model.put("pages", pages);

        // Sources & Method: khai báo rõ những gì KHÔNG đủ dữ liệu, thay vì âm thầm bỏ qua.
        List<String> openGaps = new ArrayList<>();
        openGaps.add("Macro Update (chỉ số vĩ mô ngành) — chưa có nguồn dữ liệu vĩ mô trong hệ thống, không đưa vào báo cáo.");
        if (homeCompany == null || homeCompany.isBlank()) {
            openGaps.add("So sánh chiến lược (STRATEGIC_COMPARISON) — chưa cấu hình marketradar.home-company nên không tổng hợp mục này.");
        }
        model.put("openGaps", openGaps);

        // Ghi chú override cho reviewer — y hệt ReportController (report tuần), tái dùng nguyên tắc:
        // chỉ tra log cho claim FORCE_APPROVED thực sự xuất hiện trong report này.
        Map<String, LabelLog> forceApproveNotes = new HashMap<>();
        for (InterpretedClaim c : passed) {
            if (c.getReviewStatus() == ReviewStatus.FORCE_APPROVED) {
                labelLogs.findByClaimCodeOrderByCreatedAtDescIdDesc(c.getClaimCode()).stream()
                        .filter(l -> l.getAction() == LabelLog.Action.FORCE_APPROVE)
                        .findFirst()
                        .ifPresent(l -> forceApproveNotes.put(c.getClaimCode(), l));
            }
        }
        model.put("forceApproveNotes", forceApproveNotes);

        model.put("hasAnyContent", !passed.isEmpty());
        return model;
    }

    /** Sắp theo ngày sự kiện SỚM NHẤT trong các fact được trích (dùng cho TIMELINE); null ngày xếp cuối. */
    private static List<InterpretedClaim> sortByFirstFactDate(
            List<InterpretedClaim> claims, Map<String, EvidenceFact> factByCode) {
        return claims.stream()
                .sorted(Comparator.comparing(
                        (InterpretedClaim c) -> earliestEventDate(c, factByCode),
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private static LocalDate earliestEventDate(InterpretedClaim c, Map<String, EvidenceFact> factByCode) {
        if (c.getFactCodesCsv() == null) return null;
        return Arrays.stream(c.getFactCodesCsv().split(","))
                .map(String::trim).map(factByCode::get).filter(Objects::nonNull)
                .map(EvidenceFact::getEventDate).filter(Objects::nonNull)
                .min(Comparator.naturalOrder()).orElse(null);
    }
}
