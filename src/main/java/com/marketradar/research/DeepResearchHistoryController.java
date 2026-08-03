package com.marketradar.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketradar.domain.InterpretedClaim;
import com.marketradar.repo.InterpretedClaimRepository;
import com.marketradar.report.PdfExportService;
import com.marketradar.report.bi.BiReportContent;
import com.marketradar.report.bi.BiReportDocxService;
import com.marketradar.report.bi.BiReportPageBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 2026-08-03: lịch sử các lần chạy Deep Research (xem DeepResearchRun) — trước đây một lần chạy
 * chỉ xem được NGAY LÚC vừa xong (qua link SSE trả về hoặc cache tạm 20 kết quả gần nhất, mất
 * khi app restart), không có nơi nào liệt kê lại. Trang này còn trả lời câu hỏi "kết quả có chui
 * vào báo cáo chính thức không" bằng cách tra claim đã phát sinh từ các RawDoc mà lần chạy đó
 * nạp vào pipeline, và trạng thái duyệt hiện tại của chúng.
 */
@Controller
public class DeepResearchHistoryController {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final DeepResearchRunRepository runs;
    private final InterpretedClaimRepository claims;
    private final PdfExportService pdfExport;
    private final BiReportDocxService docxExport;
    private final ObjectMapper mapper = new ObjectMapper();

    public DeepResearchHistoryController(DeepResearchRunRepository runs, InterpretedClaimRepository claims,
                                         PdfExportService pdfExport, BiReportDocxService docxExport) {
        this.runs = runs;
        this.claims = claims;
        this.pdfExport = pdfExport;
        this.docxExport = docxExport;
    }

    public record RunRow(Long id, String shortPrompt, String createdAtLabel, int sourceCount,
                         int newDocCount, String elapsedLabel) {}

    @GetMapping("/research/history")
    public String history(Model model) {
        List<RunRow> rows = runs.findAllByOrderByCreatedAtDesc().stream()
                .map(r -> new RunRow(r.getId(), r.shortPrompt(),
                        TS_FMT.format(r.getCreatedAt().atZone(ZONE)),
                        r.getSourceCount(), r.getNewDocCount(), elapsedLabel(r.getElapsedMs())))
                .toList();
        model.addAttribute("runs", rows);
        return "research-history";
    }

    public record ClaimFlowSummary(int newDocCount, int totalClaims, int approvedCount,
                                   int pendingCount, int rejectedCount) {}

    @GetMapping("/research/history/{id}")
    public String view(@PathVariable Long id, @RequestParam(defaultValue = "vi") String lang, Model model) {
        DeepResearchRun run = runs.findById(id).orElse(null);
        if (run == null) {
            model.addAttribute("promptError", "Không tìm thấy lần chạy này.");
            return "research";
        }
        boolean vi = !"en".equalsIgnoreCase(lang);
        BiReportContent content = readContent(id);
        if (content == null) {
            model.addAttribute("promptError", "Không đọc được nội dung đã lưu của lần chạy này.");
            return "research";
        }
        Map<String, Object> reportModel = BiReportPageBuilder.toTemplateModel(content, vi);
        reportModel.put("pdfHref", "/research/history/" + id + ".pdf?lang=" + (vi ? "vi" : "en"));
        reportModel.put("docxHref", "/research/history/" + id + ".docx");
        reportModel.put("langHrefVi", "/research/history/" + id + "?lang=vi");
        reportModel.put("langHrefEn", "/research/history/" + id + "?lang=en");
        reportModel.put("claimFlow", claimFlow(run));
        model.addAllAttributes(reportModel);
        return "bi-report";
    }

    @GetMapping("/research/history/{id}.pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable Long id, @RequestParam(defaultValue = "vi") String lang) {
        BiReportContent content = readContent(id);
        if (content == null) return notFound();
        Map<String, Object> model = BiReportPageBuilder.toTemplateModel(content, !"en".equalsIgnoreCase(lang));
        byte[] pdf = pdfExport.renderBiReportPdf(model);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"deep-research-" + id + ".pdf\"")
                .body(pdf);
    }

    @GetMapping("/research/history/{id}.docx")
    public ResponseEntity<byte[]> docx(@PathVariable Long id) {
        BiReportContent content = readContent(id);
        if (content == null) return notFound();
        byte[] docx = docxExport.render(content);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"deep-research-" + id + ".docx\"")
                .body(docx);
    }

    private BiReportContent readContent(Long id) {
        DeepResearchRun run = runs.findById(id).orElse(null);
        if (run == null) return null;
        try {
            return mapper.readValue(run.getContentJson(), BiReportContent.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static ResponseEntity<byte[]> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.TEXT_PLAIN)
                .body("Không tìm thấy lần chạy này.".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private ClaimFlowSummary claimFlow(DeepResearchRun run) {
        List<Long> docIds = run.newDocIds();
        if (docIds.isEmpty()) return new ClaimFlowSummary(0, 0, 0, 0, 0);
        List<InterpretedClaim> found = claims.findByRawDocIdIn(docIds);
        int approved = 0, pending = 0, rejected = 0;
        for (InterpretedClaim c : found) {
            switch (c.getReviewStatus()) {
                case APPROVED, EDITED_APPROVED, FORCE_APPROVED, AUTO_APPROVED -> approved++;
                case PENDING_VERIFICATION, PENDING_REVIEW -> pending++;
                case REJECTED -> rejected++;
            }
        }
        return new ClaimFlowSummary(run.getNewDocCount(), found.size(), approved, pending, rejected);
    }

    private static String elapsedLabel(long elapsedMs) {
        long totalSeconds = elapsedMs / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return minutes > 0 ? minutes + "p" + seconds + "s" : seconds + "s";
    }
}
