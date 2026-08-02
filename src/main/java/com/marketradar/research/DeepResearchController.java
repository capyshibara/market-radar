package com.marketradar.research;

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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Deep Research (use case 1): người dùng nhập 1 prompt tự do, agent tự quyết định tìm kiếm mở
 * hay render trình duyệt, tự lặp tới khi đủ thông tin (xem DeepResearchService), rồi hiển thị
 * kết quả bằng ĐÚNG template Meridian dùng chung với bản BI report định kỳ. Không ghi DB — kết
 * quả chỉ tồn tại trong DeepResearchResultCache (bộ nhớ tiến trình) để phục vụ nút tải PDF/docx
 * ngay sau khi xem, không phải một nguồn dữ liệu lâu dài.
 */
@Controller
public class DeepResearchController {

    /** Chạy vòng lặp agent nền cho SSE — không dùng @Async để tránh phụ thuộc cấu hình
     *  Spring task executor toàn cục cho một tính năng dùng thấp tần suất, đơn giản hơn khi
     *  đọc/bảo trì. Đóng khi app shutdown qua @PreDestroy. */
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool();

    private final DeepResearchService deepResearch;
    private final DeepResearchResultCache cache;
    private final PdfExportService pdfExport;
    private final BiReportDocxService docxExport;

    public DeepResearchController(DeepResearchService deepResearch, DeepResearchResultCache cache,
                                  PdfExportService pdfExport, BiReportDocxService docxExport) {
        this.deepResearch = deepResearch;
        this.cache = cache;
        this.pdfExport = pdfExport;
        this.docxExport = docxExport;
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        streamExecutor.shutdownNow();
    }

    /** /research là trang chính (mode toggle Tìm kiếm nhanh / Deep Research) — giữ route cũ
     *  để không hỏng link/bookmark cũ. */
    @GetMapping("/research/deep")
    public String form() {
        return "redirect:/research";
    }

    /** Fallback KHÔNG-JS cho Deep Research (JS tắt thì form submit thẳng vào đây, chạy đồng bộ
     *  không có activity feed trực tiếp — /research/deep/stream mới có SSE). */
    @PostMapping("/research/deep")
    public String run(@RequestParam(value = "prompt", required = false) String prompt, Model model) {
        if (prompt == null || prompt.isBlank()) {
            model.addAttribute("promptError", "Cần nhập yêu cầu nghiên cứu.");
            return "research";
        }
        BiReportContent content = deepResearch.research(prompt.strip());
        String id = cache.put(content);
        Map<String, Object> reportModel = BiReportPageBuilder.toTemplateModel(content, true);
        reportModel.put("pdfHref", "/research/deep/" + id + ".pdf");
        reportModel.put("docxHref", "/research/deep/" + id + ".docx");
        reportModel.put("langHrefVi", "/research/deep/view/" + id + "?lang=vi");
        reportModel.put("langHrefEn", "/research/deep/view/" + id + "?lang=en");
        model.addAllAttributes(reportModel);
        return "bi-report";
    }

    /**
     * SSE — stream tiến trình từng bước của agent (giống Claude/ChatGPT/Gemini Deep Research
     * hiện "đang tìm X", "đang đọc Y" thay vì 1 spinner câm). Sự kiện cuối "done" mang id cache
     * để client điều hướng sang /research/deep/view/{id} (không dùng response body của chính
     * request SSE này — SseEmitter không trả HTML report được).
     */
    @GetMapping(value = "/research/deep/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String prompt) {
        // 2026-08-02: 180s → 30 phút. Nâng MAX_ITERATIONS/MAX_SOURCES ở DeepResearchService
        // kéo dài thời gian chạy thật (nhiều vòng tìm + fetch + giờ còn thêm bước nạp pipeline
        // xác thực) — timeout cũ ngắt kết nối SSE giữa chừng dù agent vẫn đang chạy nền tốt.
        SseEmitter emitter = new SseEmitter(1_800_000L);
        streamExecutor.execute(() -> {
            try {
                BiReportContent content = deepResearch.research(prompt.strip(), step -> {
                    try {
                        emitter.send(SseEmitter.event().name("step").data(step));
                    } catch (IOException e) {
                        // Client đã đóng kết nối (đóng tab, mất mạng) — bước tiếp theo cứ thử gửi,
                        // IOException lặp lại sẽ không chặn agent, chỉ là gửi vô ích.
                    }
                });
                String id = cache.put(content);
                emitter.send(SseEmitter.event().name("done").data(id));
                emitter.complete();
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage() == null ? "Lỗi không xác định" : e.getMessage()));
                } catch (IOException ignored) { /* client đã đóng */ }
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    /** Điểm đến sau khi SSE báo "done" — render đúng report đã cache, không chạy lại agent. */
    @GetMapping("/research/deep/view/{id}")
    public String view(@PathVariable String id, @RequestParam(defaultValue = "vi") String lang, Model model) {
        BiReportContent content = cache.get(id);
        if (content == null) {
            model.addAttribute("promptError", "Không tìm thấy kết quả (cache đã hết hạn hoặc app vừa khởi động lại) — hãy chạy lại yêu cầu.");
            return "research";
        }
        Map<String, Object> reportModel = BiReportPageBuilder.toTemplateModel(content, isVi(lang));
        reportModel.put("pdfHref", "/research/deep/" + id + ".pdf?lang=" + (isVi(lang) ? "vi" : "en"));
        reportModel.put("docxHref", "/research/deep/" + id + ".docx");
        reportModel.put("langHrefVi", "/research/deep/view/" + id + "?lang=vi");
        reportModel.put("langHrefEn", "/research/deep/view/" + id + "?lang=en");
        model.addAllAttributes(reportModel);
        return "bi-report";
    }

    @GetMapping("/research/deep/{id}.pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable String id, @RequestParam(defaultValue = "vi") String lang) {
        BiReportContent content = cache.get(id);
        if (content == null) return notFound();
        Map<String, Object> model = BiReportPageBuilder.toTemplateModel(content, isVi(lang));
        byte[] pdf = pdfExport.renderBiReportPdf(model);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"deep-research.pdf\"")
                .body(pdf);
    }

    private static boolean isVi(String lang) {
        return !"en".equalsIgnoreCase(lang);
    }

    @GetMapping("/research/deep/{id}.docx")
    public ResponseEntity<byte[]> docx(@PathVariable String id) {
        BiReportContent content = cache.get(id);
        if (content == null) return notFound();
        byte[] docx = docxExport.render(content);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"deep-research.docx\"")
                .body(docx);
    }

    private static ResponseEntity<byte[]> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.TEXT_PLAIN)
                .body(("Không tìm thấy kết quả — cache Deep Research chỉ giữ tạm trong bộ nhớ "
                        + "(mất khi app khởi động lại) và chỉ giữ 20 kết quả gần nhất. Hãy chạy lại yêu cầu.")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
