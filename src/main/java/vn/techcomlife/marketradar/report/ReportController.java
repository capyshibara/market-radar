package vn.techcomlife.marketradar.report;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import vn.techcomlife.marketradar.pipeline.IngestionJob;
import vn.techcomlife.marketradar.domain.EvidenceFact;
import vn.techcomlife.marketradar.domain.InterpretedClaim;
import vn.techcomlife.marketradar.repo.EvidenceFactRepository;
import vn.techcomlife.marketradar.repo.InterpretedClaimRepository;
import vn.techcomlife.marketradar.repo.RawDocRepository;
import vn.techcomlife.marketradar.repo.SourceRepository;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
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
    private final PdfExportService pdfExport;

    public ReportController(EvidenceFactRepository facts, SourceRepository sources,
                            RawDocRepository rawDocs, IngestionJob ingestionJob,
                            InterpretedClaimRepository claims, PdfExportService pdfExport) {
        this.facts = facts;
        this.sources = sources;
        this.rawDocs = rawDocs;
        this.ingestionJob = ingestionJob;
        this.claims = claims;
        this.pdfExport = pdfExport;
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

        // Batch 3: chỉ claim PASS gate L1. Batch 4 siết thêm: phải qua review pipeline —
        // 4 trạng thái *_APPROVED mới được xuất bản. FORCE_APPROVED là override có log
        // của con người (vẫn bắt buộc có citation — Invariant #1); Batch 5 gắn nhãn
        // riêng trong template để người đọc report phân biệt được.
        var passed = claims.findPublishable(List.of(
                InterpretedClaim.ReviewStatus.AUTO_APPROVED,
                InterpretedClaim.ReviewStatus.APPROVED,
                InterpretedClaim.ReviewStatus.EDITED_APPROVED,
                InterpretedClaim.ReviewStatus.FORCE_APPROVED));
        model.put("execClaims", passed.stream()
                .filter(c -> c.getSlot() == InterpretedClaim.Slot.EXEC_SUMMARY).toList());
        Map<Long, java.util.List<InterpretedClaim>> claimsByDoc = new HashMap<>();
        for (InterpretedClaim c : passed) {
            if (c.getRawDoc() != null && c.getRawDoc().getDuplicateOfId() == null) {
                claimsByDoc.computeIfAbsent(c.getRawDoc().getId(),
                        k -> new java.util.ArrayList<>()).add(c);
            }
        }
        model.put("claimsByDoc", claimsByDoc);
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
