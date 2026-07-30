package com.marketradar.research;

import com.marketradar.fetch.SafeFetcher;
import com.marketradar.parse.ContentParsers;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 6 — chế độ "ad-hoc nhanh": Strategy Expert cần tra cứu 1 câu hỏi rời rạc, KHÔNG cần đưa
 * vào BI report chính thức. Chạy nguồn 2 (dynamic search) hoặc nguồn 3 (browser render) y hệt
 * ResearchController/BrowserRenderService, nhưng xuất thẳng ra .docx — KHÔNG ghi RawDoc/
 * EvidenceFact. Đây là chế độ 2 trong 2 chế độ tiêu thụ ad-hoc đã chốt khi thiết kế:
 *   (1) ad-hoc → nạp vào BI report: phải qua RawDoc (tier "ad-hoc"/"chưa xác minh") để có F-00x
 *       citation thật — đó là luồng /research/run + /research/render đã có (Phase 2).
 *   (2) ad-hoc rời rạc, chỉ cần đọc nhanh: KHÔNG đụng DB — chính là class này.
 * Không lẫn 2 luồng: không tái dùng RawDocRepository/EvidenceFactRepository ở đây dù về mặt kỹ
 * thuật có thể — cố tình tách để khỏi làm loãng dữ liệu report chính thức bằng câu hỏi tạm thời.
 *
 * Dù không qua Gate L1, mỗi đoạn văn KHÔNG được rời khỏi context nguồn của nó — mỗi mục vẫn giữ
 * URL + thời điểm fetch + cách lấy (RESEARCH_HTTP/RESEARCH_BROWSER) làm audit trail tối thiểu.
 */
@Service
public class AdhocDocxService {

    private static final Logger log = LoggerFactory.getLogger(AdhocDocxService.class);
    private static final int MAX_CANDIDATES = 8; // giống ResearchController — chặn 1 query "nổ" ra quá nhiều fetch
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final NewsDiscoveryService discovery;
    private final SafeFetcher fetcher;
    private final ContentParsers parsers;
    private final BrowserRenderService browserRender;

    public AdhocDocxService(NewsDiscoveryService discovery, SafeFetcher fetcher,
                            ContentParsers parsers, BrowserRenderService browserRender) {
        this.discovery = discovery;
        this.fetcher = fetcher;
        this.parsers = parsers;
        this.browserRender = browserRender;
    }

    private record Item(String title, String url, String acquisition, String text, String note) {}

    /** Nguồn 2 (dynamic search): 1 query tự do → N tài liệu, gộp thành 1 file docx. */
    public byte[] buildFromQuery(String query) throws NewsDiscoveryService.DiscoveryFailedException {
        List<ContentParsers.RssItem> candidates = discovery.discover(query);
        List<Item> items = new ArrayList<>();
        int checked = 0;
        for (var c : candidates) {
            if (checked >= MAX_CANDIDATES) break;
            checked++;
            if (c.link() == null) continue;
            try {
                var result = fetcher.fetchOpen(c.link(), SafeFetcher.ExpectedKind.HTML);
                var parsed = parsers.parseGenericArticle(result.body());
                items.add(new Item(parsed.title(), c.link(), "RESEARCH_HTTP (dynamic search)",
                        parsed.text(), parsed.note()));
            } catch (SafeFetcher.FetchRejectedException e) {
                items.add(new Item(c.title() != null ? c.title() : c.link(), c.link(),
                        "RESEARCH_HTTP (dynamic search)", null, "FETCH REJECTED: " + e.getMessage()));
            } catch (ContentParsers.ParseFailedException e) {
                items.add(new Item(c.title() != null ? c.title() : c.link(), c.link(),
                        "RESEARCH_HTTP (dynamic search)", null, "PARSE_ERROR: " + e.getMessage()));
            } catch (Exception e) {
                log.error("UNEXPECTED khi xử lý ad-hoc candidate {}", c.link(), e);
                items.add(new Item(c.link(), c.link(), "RESEARCH_HTTP (dynamic search)", null,
                        "LỖI KHÔNG XÁC ĐỊNH: " + e.getMessage()));
            }
        }
        return renderDocx("Câu hỏi/prompt", query, items);
    }

    /** Nguồn 3 (browser render): 1 URL cụ thể cần JS render → 1 tài liệu, xuất docx. */
    public byte[] buildFromUrl(String url) throws BrowserRenderService.BrowserRenderException {
        Item item;
        try {
            String html = browserRender.renderHtml(url);
            var parsed = parsers.parseGenericArticle(html.getBytes(StandardCharsets.UTF_8));
            item = new Item(parsed.title(), url, "RESEARCH_BROWSER (render JS)", parsed.text(), parsed.note());
        } catch (ContentParsers.ParseFailedException e) {
            item = new Item(url, url, "RESEARCH_BROWSER (render JS)", null, "PARSE_ERROR: " + e.getMessage());
        }
        return renderDocx("URL cần render", url, List.of(item));
    }

    private byte[] renderDocx(String queryLabel, String queryValue, List<Item> items) {
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph title = doc.createParagraph();
            XWPFRun titleRun = title.createRun();
            titleRun.setText("Market Radar — Ghi chú nghiên cứu ad-hoc");
            titleRun.setBold(true);
            titleRun.setFontSize(18);

            XWPFParagraph disclaimer = doc.createParagraph();
            XWPFRun disclaimerRun = disclaimer.createRun();
            disclaimerRun.setText("Tài liệu tạm thời — CHƯA qua Gate L1/review, KHÔNG phải nguồn đã publish "
                    + "trong Market Radar. Chỉ dùng để tham khảo nhanh, không trích dẫn lại như fact đã kiểm chứng.");
            disclaimerRun.setItalic(true);
            disclaimerRun.setColor("8A8878");
            disclaimerRun.setFontSize(10);

            XWPFParagraph meta = doc.createParagraph();
            XWPFRun metaRun = meta.createRun();
            metaRun.setText(queryLabel + ": " + queryValue
                    + "  ·  Tạo lúc " + ZonedDateTime.now().format(TS_FMT));
            metaRun.setFontSize(10);
            metaRun.setColor("4A4A45");

            doc.createParagraph(); // dòng trống

            if (items.isEmpty()) {
                XWPFRun emptyRun = doc.createParagraph().createRun();
                emptyRun.setText("Không tìm được tài liệu nào cho yêu cầu này.");
                emptyRun.setItalic(true);
            }

            for (Item item : items) {
                XWPFParagraph heading = doc.createParagraph();
                heading.setSpacingBefore(200);
                XWPFRun headingRun = heading.createRun();
                headingRun.setText(item.title() != null ? item.title() : "(không có tiêu đề)");
                headingRun.setBold(true);
                headingRun.setFontSize(13);

                XWPFParagraph itemMeta = doc.createParagraph();
                XWPFRun itemMetaRun = itemMeta.createRun();
                itemMetaRun.setText("Nguồn: " + item.url() + "  ·  Cách lấy: " + item.acquisition()
                        + "  ·  Lúc: " + ZonedDateTime.now().format(TS_FMT));
                itemMetaRun.setFontSize(9);
                itemMetaRun.setColor("8A8878");
                itemMetaRun.setItalic(true);

                if (item.note() != null) {
                    XWPFParagraph noteP = doc.createParagraph();
                    XWPFRun noteRun = noteP.createRun();
                    noteRun.setText("Ghi chú: " + item.note());
                    noteRun.setFontSize(9);
                    noteRun.setColor("96600D");
                }

                if (item.text() != null) {
                    for (String block : item.text().split("\n\n+")) {
                        if (block.isBlank()) continue;
                        XWPFParagraph body = doc.createParagraph();
                        body.setAlignment(ParagraphAlignment.LEFT);
                        XWPFRun bodyRun = body.createRun();
                        bodyRun.setText(block.strip());
                        bodyRun.setFontSize(11);
                    }
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Xuất docx thất bại: " + e.getMessage(), e);
        }
    }
}
