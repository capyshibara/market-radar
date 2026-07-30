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
 * Ghi chú nghiên cứu ad-hoc: Strategy/analyst cần tra cứu MỘT câu hỏi rời rạc, không cần đưa vào
 * kho evidence/report chính thức → chạy tìm-mở hoặc render-JS y hệt ResearchController chế độ A,
 * nhưng xuất thẳng .docx — CỐ TÌNH không ghi RawDoc/EvidenceFact (không làm loãng kho evidence
 * bằng câu hỏi tạm thời; muốn nạp thật thì dùng /research/run — hai luồng tách bạch, không lẫn).
 *
 * Dù không qua gate/review, mỗi mục vẫn giữ URL + thời điểm + cách lấy làm audit trail tối thiểu,
 * và file luôn mở đầu bằng disclaimer "chưa kiểm chứng" — không ai trích lại nhầm như fact thật.
 */
@Service
public class AdhocDocxService {

    private static final Logger log = LoggerFactory.getLogger(AdhocDocxService.class);
    private static final int MAX_CANDIDATES = 8; // cùng cap với ResearchController
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

    /** Tìm mở theo query → N bài gộp thành 1 file docx. Lỗi từng bài giữ lại làm ghi chú, không chặn cả file. */
    public byte[] buildFromQuery(String query) throws NewsDiscoveryService.DiscoveryFailedException {
        List<NewsDiscoveryService.Candidate> candidates = discovery.discover(query);
        List<Item> items = new ArrayList<>();
        int checked = 0;
        for (var c : candidates) {
            if (checked >= MAX_CANDIDATES) break;
            checked++;
            if (c.publisherUrl() == null) {
                items.add(new Item(c.title(), "(không giải được link)", "OPEN_SEARCH", null, c.note()));
                continue;
            }
            try {
                var fetched = fetcher.fetchDocument(c.publisherUrl());
                boolean pdf = "application/pdf".equalsIgnoreCase(fetched.contentType());
                var parsed = pdf ? parsers.parsePdf(fetched.body()) : parsers.parseArticleHtml(fetched.body());
                items.add(new Item(parsed.title() == null || parsed.title().isBlank() ? c.title() : parsed.title(),
                        c.publisherUrl(), "OPEN_SEARCH", parsed.text(), parsed.note()));
            } catch (SafeFetcher.FetchRejectedException e) {
                items.add(new Item(c.title(), c.publisherUrl(), "OPEN_SEARCH", null,
                        "FETCH REJECTED: " + e.getMessage()));
            } catch (ContentParsers.ParseFailedException e) {
                items.add(new Item(c.title(), c.publisherUrl(), "OPEN_SEARCH", null,
                        "PARSE_ERROR: " + e.getMessage()));
            } catch (Exception e) {
                log.error("UNEXPECTED khi xử lý ad-hoc candidate {}", c.publisherUrl(), e);
                items.add(new Item(c.title(), c.publisherUrl(), "OPEN_SEARCH", null,
                        "LỖI KHÔNG XÁC ĐỊNH: " + e.getMessage()));
            }
        }
        return renderDocx("Câu hỏi/prompt", query, items);
    }

    /** Render 1 URL cần JS → 1 bài, xuất docx. */
    public byte[] buildFromUrl(String url) throws BrowserRenderService.BrowserRenderException {
        Item item;
        try {
            String html = browserRender.renderHtml(url);
            var parsed = parsers.parseArticleHtml(html.getBytes(StandardCharsets.UTF_8));
            item = new Item(parsed.title(), url, "BROWSER_RENDER", parsed.text(), parsed.note());
        } catch (ContentParsers.ParseFailedException e) {
            item = new Item(url, url, "BROWSER_RENDER", null, "PARSE_ERROR: " + e.getMessage());
        }
        return renderDocx("URL cần render", url, List.of(item));
    }

    private byte[] renderDocx(String queryLabel, String queryValue, List<Item> items) {
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFRun titleRun = doc.createParagraph().createRun();
            titleRun.setText("Market Radar — Ghi chú nghiên cứu ad-hoc");
            titleRun.setBold(true);
            titleRun.setFontSize(18);

            XWPFRun disclaimerRun = doc.createParagraph().createRun();
            disclaimerRun.setText("Tài liệu tạm thời — CHƯA qua gate/review, KHÔNG phải nguồn đã publish "
                    + "trong Market Radar. Chỉ dùng để tham khảo nhanh, không trích dẫn lại như fact đã kiểm chứng.");
            disclaimerRun.setItalic(true);
            disclaimerRun.setColor("8A8878");
            disclaimerRun.setFontSize(10);

            XWPFRun metaRun = doc.createParagraph().createRun();
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

                XWPFRun itemMetaRun = doc.createParagraph().createRun();
                itemMetaRun.setText("Nguồn: " + item.url() + "  ·  Cách lấy: " + item.acquisition()
                        + "  ·  Lúc: " + ZonedDateTime.now().format(TS_FMT));
                itemMetaRun.setFontSize(9);
                itemMetaRun.setColor("8A8878");
                itemMetaRun.setItalic(true);

                if (item.note() != null) {
                    XWPFRun noteRun = doc.createParagraph().createRun();
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
