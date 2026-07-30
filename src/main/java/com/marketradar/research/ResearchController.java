package com.marketradar.research;

import com.marketradar.domain.RawDoc;
import com.marketradar.intake.ManualDocumentIntakeService;
import com.marketradar.intake.ManualDocumentRules;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Open research — manual-trigger only (khác IngestionJob có thể chạy lịch), cùng nhịp thao tác
 * với /ingest/run: gọi tay, trả text tóm tắt từng bước. Hai chế độ tiêu thụ TÁCH BẠCH:
 *
 *  A. NẠP VÀO KHO EVIDENCE (đi tiếp pipeline classify→extract→report):
 *     POST /research/run?q=...      — tìm mở theo câu hỏi, mỗi bài đi qua ĐÚNG đường
 *                                     ManualDocumentIntakeService (validate/metadata/dedup sẵn có),
 *                                     intake = OPEN_SEARCH.
 *     POST /research/render?url=... — trang cần JS render (SPA), qua BrowserRenderService rồi cũng
 *                                     vào intake, intake = BROWSER_RENDER.
 *
 *  B. GHI CHÚ AD-HOC (KHÔNG ghi DB — câu hỏi rời rạc, không cần vào report chính thức):
 *     POST /research/adhoc.docx?q=...          — tìm mở → file .docx tải về.
 *     POST /research/adhoc-render.docx?url=... — render JS → file .docx tải về.
 *
 * Không kênh nào auto-publish: tài liệu nạp qua A vẫn đi qua toàn bộ gate/review như mọi nguồn.
 */
@Controller
public class ResearchController {

    /** Chặn 1 query "nổ" ra fetch quá nhiều URL lạ cùng lượt — còn lại bỏ qua, báo rõ trong output. */
    private static final int MAX_CANDIDATES = 8;

    private static final MediaType DOCX_TYPE = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final NewsDiscoveryService discovery;
    private final BrowserRenderService browserRender;
    private final ManualDocumentIntakeService intake;
    private final AdhocDocxService adhocDocx;

    public ResearchController(NewsDiscoveryService discovery, BrowserRenderService browserRender,
                              ManualDocumentIntakeService intake, AdhocDocxService adhocDocx) {
        this.discovery = discovery;
        this.browserRender = browserRender;
        this.intake = intake;
        this.adhocDocx = adhocDocx;
    }

    // ================= chế độ A — nạp vào kho evidence =================

    @PostMapping("/research/run")
    @ResponseBody
    public String runOpenSearch(@RequestParam(value = "q", required = false) String query) {
        if (query == null || query.isBlank()) return "THIẾU 'q' — cần nhập câu hỏi/từ khoá tìm kiếm";

        List<NewsDiscoveryService.Candidate> candidates;
        try {
            candidates = discovery.discover(query);
        } catch (NewsDiscoveryService.DiscoveryFailedException e) {
            return "TÌM KIẾM THẤT BẠI: " + e.getMessage();
        }

        StringBuilder summary = new StringBuilder(
                "Query: \"" + query + "\" — " + candidates.size() + " ứng viên tìm được\n");
        int imported = 0, checked = 0;
        for (var c : candidates) {
            if (checked >= MAX_CANDIDATES) {
                summary.append("(dừng ở ").append(MAX_CANDIDATES).append(" ứng viên — còn lại bỏ qua lượt này)\n");
                break;
            }
            checked++;
            if (c.publisherUrl() == null) {
                summary.append("- BỎ QUA (").append(c.note()).append("): ").append(c.title()).append('\n');
                continue;
            }
            try {
                var result = intake.importUrl(c.publisherUrl(), RawDoc.IntakeMethod.OPEN_SEARCH,
                        "OPEN_SEARCH | query=\"" + query + "\"");
                if (result.duplicate()) {
                    summary.append("- Trùng (đã có): ").append(c.publisherUrl()).append('\n');
                } else {
                    imported++;
                    summary.append("- NẠP: ").append(c.title()).append(" (").append(c.publisherUrl()).append(")\n");
                }
            } catch (ManualDocumentRules.ValidationException e) {
                summary.append("- LOẠI: ").append(c.publisherUrl()).append(" — ").append(e.getMessage()).append('\n');
            }
        }
        summary.append("Tổng: +").append(imported)
                .append(" tài liệu mới (intake=OPEN_SEARCH). Chạy Classify rồi Extract để đi tiếp pipeline.\n");
        return summary.toString();
    }

    @PostMapping("/research/render")
    @ResponseBody
    public String runBrowserRender(@RequestParam(value = "url", required = false) String url) {
        if (url == null || url.isBlank()) return "THIẾU 'url' — cần nhập URL cụ thể cần render";
        try {
            String html = browserRender.renderHtml(url);
            var result = intake.importRenderedHtml(url, html.getBytes(StandardCharsets.UTF_8));
            return result.duplicate()
                    ? "Trùng (đã có): " + url
                    : "NẠP (intake=BROWSER_RENDER): " + result.message() + " Document #" + result.rawDocId();
        } catch (BrowserRenderService.BrowserRenderException e) {
            return "RENDER THẤT BẠI: " + e.getMessage();
        } catch (ManualDocumentRules.ValidationException e) {
            return "LOẠI: " + e.getMessage();
        }
    }

    // ================= chế độ B — ghi chú ad-hoc, không ghi DB =================

    @PostMapping("/research/adhoc.docx")
    public ResponseEntity<byte[]> adhocFromQuery(@RequestParam(value = "q", required = false) String query) {
        if (query == null || query.isBlank()) {
            return textError(400, "THIẾU 'q' — cần nhập câu hỏi/từ khoá tìm kiếm");
        }
        try {
            return docxResponse(adhocDocx.buildFromQuery(query), "adhoc-research.docx");
        } catch (NewsDiscoveryService.DiscoveryFailedException e) {
            return textError(502, "TÌM KIẾM THẤT BẠI: " + e.getMessage());
        }
    }

    @PostMapping("/research/adhoc-render.docx")
    public ResponseEntity<byte[]> adhocFromUrl(@RequestParam(value = "url", required = false) String url) {
        if (url == null || url.isBlank()) {
            return textError(400, "THIẾU 'url' — cần nhập URL cụ thể cần render");
        }
        try {
            return docxResponse(adhocDocx.buildFromUrl(url), "adhoc-render.docx");
        } catch (BrowserRenderService.BrowserRenderException e) {
            return textError(502, "RENDER THẤT BẠI: " + e.getMessage());
        }
    }

    private ResponseEntity<byte[]> docxResponse(byte[] docx, String filename) {
        return ResponseEntity.ok()
                .contentType(DOCX_TYPE)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(docx);
    }

    private ResponseEntity<byte[]> textError(int status, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.TEXT_PLAIN)
                .body(message.getBytes(StandardCharsets.UTF_8));
    }
}
