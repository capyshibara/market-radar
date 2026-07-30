package com.marketradar.research;

import com.marketradar.fetch.SafeFetcher;
import com.marketradar.parse.ContentParsers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Web Search (chế độ "nhanh"): 1 lượt tìm-mở + đọc top N kết quả, KHÔNG có vòng lặp AI quyết
 * định bước tiếp theo (khác hẳn DeepResearchService) — không có tổng hợp AI, chỉ liệt kê nguồn
 * + trích đoạn thật. Đây là điểm khác biệt CỐ Ý với Deep Research: nhanh, xác định (không phụ
 * thuộc LLM/STUB), phù hợp câu hỏi "có tin gì về X" đơn giản. Muốn AI tự lặp nhiều vòng, quyết
 * định công cụ (search/browser) và tổng hợp theo 7-bucket thì dùng Deep Research.
 */
@Service
public class WebSearchService {

    private static final Logger log = LoggerFactory.getLogger(WebSearchService.class);
    private static final int MAX_RESULTS = 5;

    private final NewsDiscoveryService discovery;
    private final SafeFetcher fetcher;
    private final ContentParsers parsers;

    public WebSearchService(NewsDiscoveryService discovery, SafeFetcher fetcher, ContentParsers parsers) {
        this.discovery = discovery;
        this.fetcher = fetcher;
        this.parsers = parsers;
    }

    public record Item(String title, String url, String excerpt, String note) {}
    public record Result(String query, List<Item> items) {}

    public Result search(String query) throws NewsDiscoveryService.DiscoveryFailedException {
        List<NewsDiscoveryService.Candidate> candidates = discovery.discover(query);
        List<Item> items = new ArrayList<>();
        for (var c : candidates) {
            if (items.size() >= MAX_RESULTS) break;
            if (c.publisherUrl() == null) {
                items.add(new Item(c.title(), null, null, c.note()));
                continue;
            }
            try {
                var fetched = fetcher.fetchDocument(c.publisherUrl());
                boolean pdf = "application/pdf".equalsIgnoreCase(fetched.contentType());
                var parsed = pdf ? parsers.parsePdf(fetched.body()) : parsers.parseArticleHtml(fetched.body());
                String excerpt = parsed.text() == null ? "" : parsed.text().strip();
                if (excerpt.length() > 500) excerpt = excerpt.substring(0, 500) + "…";
                items.add(new Item(parsed.title() == null || parsed.title().isBlank() ? c.title() : parsed.title(),
                        c.publisherUrl(), excerpt, parsed.note()));
            } catch (Exception e) {
                log.warn("Web Search: bỏ qua nguồn {} ({})", c.publisherUrl(), e.getMessage());
                items.add(new Item(c.title(), c.publisherUrl(), null, "Không đọc được: " + e.getMessage()));
            }
        }
        return new Result(query, items);
    }
}
