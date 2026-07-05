package com.marketradar.report;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.InterpretedClaim;
import com.marketradar.domain.LabelLog;
import com.marketradar.pipeline.IngestionJob;
import com.marketradar.repo.EvidenceFactRepository;
import com.marketradar.repo.InterpretedClaimRepository;
import com.marketradar.repo.LabelLogRepository;
import com.marketradar.repo.RawDocRepository;
import com.marketradar.repo.SourceRepository;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Batch 1 endpoints:
 *  GET  /report/weekly — tuần san (template Mục 7, fact đặt tay)
 *  GET  /sources       — nhịp demo #1: config auditable (nguồn + tier)
 *  POST /ingest/run    — chạy ingest tay (demo deterministic, không cần cron)
 * Batch 5:
 *  GET  /report/weekly.pdf — CÙNG model, CÙNG template → xuất PDF (OpenHTMLtoPDF)
 *  Report lọc bỏ fact/claim thuộc doc bị đánh dấu TRÙNG (dedup bước 9).
 * Batch 6 (report redesign — xem chats/chat1.md): template chuyển từ bảng sang
 *  mini-article/narrative theo layout "Market Radar Report.dc.html". Mã fact/claim
 *  (F-001, C-001) vẫn tồn tại làm anchor id ẩn cho jump-link, nhưng KHÔNG hiển thị
 *  dạng text nữa — citation hiện ra dưới tên nguồn thật (pill), mở tab mới khi bấm.
 *
 * An toàn hiển thị: template CHỈ dùng th:text (auto-escape) — không th:utext,
 * nên text crawl về không bao giờ được render như HTML/script.
 */
@Controller
public class ReportController {

    private final EvidenceFactRepository facts;
    private final SourceRepository sources;
    private final RawDocRepository rawDocs;
    private final IngestionJob ingestionJob;
    private final InterpretedClaimRepository claims;
    private final LabelLogRepository labelLogs;
    private final PdfExportService pdfExport;
    private final EmailPngExportService emailPngExport;

    public ReportController(EvidenceFactRepository facts, SourceRepository sources,
                            RawDocRepository rawDocs, IngestionJob ingestionJob,
                            InterpretedClaimRepository claims, LabelLogRepository labelLogs,
                            PdfExportService pdfExport, EmailPngExportService emailPngExport) {
        this.facts = facts;
        this.sources = sources;
        this.rawDocs = rawDocs;
        this.ingestionJob = ingestionJob;
        this.claims = claims;
        this.labelLogs = labelLogs;
        this.pdfExport = pdfExport;
        this.emailPngExport = emailPngExport;
    }

    @GetMapping("/report/weekly")
    public String weeklyReport(Model model) {
        model.addAllAttributes(buildWeeklyModel());
        return "weekly-report";
    }

    /** Batch 5: PDF export — đúng model + template của bản HTML (một nguồn sự thật). */
    @GetMapping("/report/weekly.pdf")
    public ResponseEntity<byte[]> weeklyReportPdf() {
        byte[] pdf = pdfExport.renderWeeklyReportPdf(buildWeeklyModel());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"market-radar-tuan-san.pdf\"")
                .body(pdf);
    }

    /** Email PNG export: same model as HTML/PDF, compact "email-summary" template rasterized to PNG. */
    @GetMapping("/report/weekly/email.png")
    public ResponseEntity<byte[]> weeklyReportEmailPng() {
        byte[] png = emailPngExport.renderWeeklySummaryPng(buildWeeklyModel());
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(png);
    }

    /**
     * Model dùng chung HTML + PDF. Batch 5: mọi fact/claim thuộc doc có
     * duplicateOfId != null bị LỌC khỏi report (bản trùng thua theo rule dedup);
     * dữ liệu KHÔNG bị xoá — vẫn audit được ở /claims, /dedup, H2 console.
     */
    private Map<String, Object> buildWeeklyModel() {
        Map<String, Object> model = new HashMap<>();
        LocalDate today = LocalDate.now();
        int week = today.get(WeekFields.of(Locale.forLanguageTag("vi")).weekOfWeekBasedYear());
        model.put("reportPeriod", "Tuần " + week + " · " + today.getYear());
        model.put("generatedAt",
                java.time.ZonedDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")));
        model.put("sources", sources.findAllByOrderByTierAsc());
        model.put("docCount", rawDocs.count());

        List<EvidenceFact> visibleFacts = facts.findAllForReport().stream()
                .filter(f -> f.getRawDoc().getDuplicateOfId() == null)
                .toList();
        model.put("allFacts", visibleFacts);
        model.put("factsCount", visibleFacts.size());
        model.put("hasSampleData", visibleFacts.stream().anyMatch(f -> f.getRawDoc().isSampleData()));

        // Batch 6: F-code -> fact, để nghị luận/exec-summary/final-recommendation tra ra
        // tên nguồn + link + tier cho citation pill mà KHÔNG cần hiện mã F-00x ra mặt chữ.
        Map<String, EvidenceFact> factByCode = new HashMap<>();
        for (EvidenceFact f : visibleFacts) factByCode.put(f.getFactCode(), f);
        model.put("factByCode", factByCode);

        // Batch 3: chỉ claim PASS gate L1. Batch 4 siết thêm: phải qua review pipeline —
        // 4 trạng thái *_APPROVED mới được xuất bản. FORCE_APPROVED là override có log
        // của con người (vẫn bắt buộc có citation — Invariant #1); report không dán nhãn
        // to nữa (Batch 6) nhưng vẫn lộ ra bằng một dòng ghi chú nhỏ kèm lý do/người duyệt.
        var passed = claims.findPublishable(List.of(
                InterpretedClaim.ReviewStatus.AUTO_APPROVED,
                InterpretedClaim.ReviewStatus.APPROVED,
                InterpretedClaim.ReviewStatus.EDITED_APPROVED,
                InterpretedClaim.ReviewStatus.FORCE_APPROVED));

        List<InterpretedClaim> execClaims = passed.stream()
                .filter(c -> c.getSlot() == InterpretedClaim.Slot.EXEC_SUMMARY).toList();
        model.put("execClaims", execClaims);

        Map<Long, List<InterpretedClaim>> claimsByDoc = new HashMap<>();
        for (InterpretedClaim c : passed) {
            if (c.getRawDoc() != null && c.getRawDoc().getDuplicateOfId() == null) {
                claimsByDoc.computeIfAbsent(c.getRawDoc().getId(), k -> new ArrayList<>()).add(c);
            }
        }
        model.put("claimsByDoc", claimsByDoc);

        // Batch 6: "Khuyến nghị cuối" của report tái dùng claim slot IMPLICATION đã publish
        // (không thêm slot mới — IMPLICATION vốn đã viết dạng hàm ý hành động kinh doanh).
        List<InterpretedClaim> recommendations = passed.stream()
                .filter(c -> c.getSlot() == InterpretedClaim.Slot.IMPLICATION)
                .filter(c -> c.getRawDoc() != null && c.getRawDoc().getDuplicateOfId() == null)
                .sorted(Comparator.comparing(InterpretedClaim::getRiskTier).reversed()
                        .thenComparing(InterpretedClaim::getId))
                .toList();
        model.put("recommendations", recommendations);

        // Batch 6: pull-quote — claim IMPLICATION rủi ro cao nhất (fallback WHY_MATTERS)
        // làm câu trích nổi bật đầu report; không bịa — chọn xác định từ dữ liệu đã publish.
        InterpretedClaim pullQuote = passed.stream()
                .filter(c -> c.getSlot() == InterpretedClaim.Slot.IMPLICATION)
                .filter(c -> c.getRawDoc() != null && c.getRawDoc().getDuplicateOfId() == null)
                .max(Comparator.comparing(InterpretedClaim::getRiskTier)
                        .thenComparing(InterpretedClaim::getCreatedAt))
                .orElseGet(() -> passed.stream()
                        .filter(c -> c.getSlot() == InterpretedClaim.Slot.WHY_MATTERS)
                        .filter(c -> c.getRawDoc() != null && c.getRawDoc().getDuplicateOfId() == null)
                        .max(Comparator.comparing(InterpretedClaim::getRiskTier)
                                .thenComparing(InterpretedClaim::getCreatedAt))
                        .orElse(null));
        model.put("pullQuoteClaim", pullQuote);

        // Batch 6: ghi chú override cho reviewer — chỉ tra log cho claim FORCE_APPROVED
        // thực sự xuất hiện trong report (tránh query thừa cho toàn bộ label_log).
        Map<String, LabelLog> forceApproveNotes = new HashMap<>();
        for (InterpretedClaim c : passed) {
            if (c.getReviewStatus() == InterpretedClaim.ReviewStatus.FORCE_APPROVED) {
                labelLogs.findByClaimCodeOrderByCreatedAtDescIdDesc(c.getClaimCode()).stream()
                        .filter(l -> l.getAction() == LabelLog.Action.FORCE_APPROVE)
                        .findFirst()
                        .ifPresent(l -> forceApproveNotes.put(c.getClaimCode(), l));
            }
        }
        model.put("forceApproveNotes", forceApproveNotes);

        return model;
    }

    @GetMapping("/sources")
    public String sourceRegistry(Model model) {
        model.addAttribute("sources", sources.findAllByOrderByTierAsc());
        return "sources";
    }

    @PostMapping("/ingest/run")
    @ResponseBody
    public String runIngest() {
        return "Kết quả ingest:\n" + ingestionJob.runOnce();
    }
}
