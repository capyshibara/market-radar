package com.marketradar.research;

import com.marketradar.domain.RawDoc;
import com.marketradar.domain.Source;
import com.marketradar.fetch.SafeFetcher;
import com.marketradar.parse.ContentParsers;
import com.marketradar.repo.RawDocRepository;
import com.marketradar.repo.SourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * Nguồn 2 (dynamic search) — CHỈ manual-trigger, khác IngestionJob (whitelist, có thể chạy lịch):
 * mỗi lần gọi là fetch mở (không whitelist host) theo 1 câu hỏi/prompt cụ thể do Strategy Expert
 * chủ động nhập, không tự lặp lại. Fact rút ra từ đây (khi Phase 3 xây tầng extract) sẽ có
 * RawDoc.acquisition=RESEARCH_HTTP → tier thấp + cần review theo rule Batch 4 sẵn có, không
 * auto-publish.
 */
@Controller
public class ResearchController {

    private static final Logger log = LoggerFactory.getLogger(ResearchController.class);
    private static final int MAX_CANDIDATES = 8; // chặn 1 query "nổ" ra fetch quá nhiều URL lạ cùng lúc

    private final NewsDiscoveryService discovery;
    private final SafeFetcher fetcher;
    private final ContentParsers parsers;
    private final RawDocRepository rawDocs;
    private final SourceRepository sources;
    private final BrowserRenderService browserRender;
    private final AdhocDocxService adhocDocx;

    public ResearchController(NewsDiscoveryService discovery, SafeFetcher fetcher,
                              ContentParsers parsers, RawDocRepository rawDocs,
                              SourceRepository sources, BrowserRenderService browserRender,
                              AdhocDocxService adhocDocx) {
        this.discovery = discovery;
        this.fetcher = fetcher;
        this.parsers = parsers;
        this.rawDocs = rawDocs;
        this.sources = sources;
        this.browserRender = browserRender;
        this.adhocDocx = adhocDocx;
    }

    @PostMapping("/research/run")
    @ResponseBody
    public String runResearch(@RequestParam(value = "q", required = false) String query) {
        if (query == null || query.isBlank()) {
            return "THIẾU 'q' — cần nhập câu hỏi/từ khoá tìm kiếm";
        }
        Source openResearch = sources.findByCode("OPEN_RESEARCH")
                .orElseThrow(() -> new IllegalStateException(
                        "Thiếu Source OPEN_RESEARCH trong registry — kiểm tra SeedData"));

        List<ContentParsers.RssItem> candidates;
        try {
            candidates = discovery.discover(query);
        } catch (NewsDiscoveryService.DiscoveryFailedException e) {
            return "TÌM KIẾM THẤT BẠI: " + e.getMessage();
        }

        StringBuilder summary = new StringBuilder(
                "Query: \"" + query + "\" — " + candidates.size() + " ứng viên tìm được\n");
        int stored = 0;
        int checked = 0;
        for (var item : candidates) {
            if (checked >= MAX_CANDIDATES) {
                summary.append("(dừng ở ").append(MAX_CANDIDATES).append(" ứng viên — còn lại bỏ qua lượt này)\n");
                break;
            }
            checked++;
            if (item.link() == null) {
                summary.append("- (bỏ qua, thiếu link): ").append(item.title()).append('\n');
                continue;
            }
            try {
                var result = fetcher.fetchOpen(item.link(), SafeFetcher.ExpectedKind.HTML);
                var parsed = parsers.parseGenericArticle(result.body());
                if (storeIfNew(openResearch, item.link(), parsed.title(), item.publishedAt(),
                        parsed.text(), parsed.note())) {
                    stored++;
                    summary.append("- LƯU: ").append(parsed.title()).append(" (").append(item.link()).append(")\n");
                } else {
                    summary.append("- Trùng (hash đã có): ").append(item.link()).append('\n');
                }
            } catch (SafeFetcher.FetchRejectedException e) {
                summary.append("- FETCH REJECTED: ").append(item.link()).append(" — ").append(e.getMessage()).append('\n');
            } catch (ContentParsers.ParseFailedException e) {
                summary.append("- PARSE_ERROR: ").append(item.link()).append(" — ").append(e.getMessage()).append('\n');
            } catch (Exception e) {
                log.error("UNEXPECTED khi xử lý ứng viên {}", item.link(), e);
                summary.append("- LỖI KHÔNG XÁC ĐỊNH: ").append(item.link()).append(" — ").append(e.getMessage()).append('\n');
            }
        }
        summary.append("Tổng: +").append(stored).append(" RawDoc mới (acquisition=RESEARCH_HTTP)\n");
        return summary.toString();
    }

    /**
     * Nguồn 3 (browser thật) — nhận 1 URL CỤ THỂ (khác nguồn 2 nhận query tự do), dùng khi Strategy
     * Expert đã biết trang này cần JS render (VD nguồn 1/2 lấy về rỗng vì site là SPA). Tái dùng
     * đúng parseGenericArticle() của nguồn 2 — bài toán "trang lạ, không biết cấu trúc" là như nhau.
     */
    @PostMapping("/research/render")
    @ResponseBody
    public String runBrowserRender(@RequestParam(value = "url", required = false) String url) {
        if (url == null || url.isBlank()) {
            return "THIẾU 'url' — cần nhập URL cụ thể cần render";
        }
        Source openResearch = sources.findByCode("OPEN_RESEARCH")
                .orElseThrow(() -> new IllegalStateException(
                        "Thiếu Source OPEN_RESEARCH trong registry — kiểm tra SeedData"));
        try {
            String html = browserRender.renderHtml(url);
            var parsed = parsers.parseGenericArticle(html.getBytes(StandardCharsets.UTF_8));
            if (storeIfNew(openResearch, url, parsed.title(), null, parsed.text(), parsed.note(),
                    RawDoc.Acquisition.RESEARCH_BROWSER)) {
                return "LƯU: " + parsed.title() + " (" + url + ")"
                        + (parsed.note() != null ? "\nGhi chú: " + parsed.note() : "");
            }
            return "Trùng (hash đã có): " + url;
        } catch (BrowserRenderService.BrowserRenderException e) {
            return "RENDER THẤT BẠI: " + e.getMessage();
        } catch (ContentParsers.ParseFailedException e) {
            return "PARSE_ERROR: " + e.getMessage();
        }
    }

    private static final MediaType DOCX_TYPE = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    /**
     * Phase 6 — chế độ ad-hoc nhanh (nguồn 2): xuất thẳng .docx, KHÔNG ghi RawDoc/EvidenceFact.
     * Dùng khi Strategy Expert chỉ cần tra cứu 1 câu hỏi rời rạc, không đưa vào BI report chính
     * thức (khác /research/run — endpoint đó GHI DB để sau này có F-00x citation cho BI report).
     */
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

    /** Phase 6 — chế độ ad-hoc nhanh (nguồn 3): 1 URL cụ thể cần JS render → docx, không ghi DB. */
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

    private boolean storeIfNew(Source source, String url, String title, Instant publishedAt,
                               String text, String note) {
        return storeIfNew(source, url, title, publishedAt, text, note, RawDoc.Acquisition.RESEARCH_HTTP);
    }

    private boolean storeIfNew(Source source, String url, String title, Instant publishedAt,
                               String text, String note, RawDoc.Acquisition acquisition) {
        String hash = sha256(text);
        if (rawDocs.existsByContentHash(hash)) return false;
        RawDoc doc = new RawDoc(source, url, title, publishedAt, Instant.now(),
                hash, text, source.getLanguage(), RawDoc.ParseStatus.OK, note);
        doc.setAcquisition(acquisition);
        rawDocs.save(doc);
        return true;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 không khả dụng", e);
        }
    }
}
