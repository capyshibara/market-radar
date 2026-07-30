package com.marketradar.research;

import com.marketradar.fetch.SafeFetcher;
import com.marketradar.parse.ContentParsers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Nguồn 2 (dynamic search, phương án zero-cost): tìm URL ứng viên qua RSS tìm kiếm CÔNG KHAI,
 * không cần API key trả phí. Google/Bing News RSS là endpoint KHÔNG chính thức (không tài liệu
 * hoá, không SLA, có thể đổi định dạng/chặn bất cứ lúc nào) — đây là đánh đổi đã CHỦ ĐỘNG chọn
 * (tiền lấy rủi ro ToS/độ ổn định), không phải sơ suất. Google News RSS là nguồn chính; Bing
 * News RSS là dự phòng khi Google lỗi.
 *
 * fetch qua SafeFetcher.fetchOpen() — KHÔNG whitelist host (đúng bản chất "web mở"), nhưng vẫn
 * giữ nguyên SSRF guard/no-redirect/content-type-check/size-cap của SafeFetcher.
 */
@Service
public class NewsDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(NewsDiscoveryService.class);

    private final SafeFetcher fetcher;
    private final ContentParsers parsers;

    public NewsDiscoveryService(SafeFetcher fetcher, ContentParsers parsers) {
        this.fetcher = fetcher;
        this.parsers = parsers;
    }

    /** Trả danh sách URL ứng viên (title, link, mô tả, ngày) cho 1 câu hỏi/prompt tự do. */
    public List<ContentParsers.RssItem> discover(String query) {
        String q = URLEncoder.encode(query, StandardCharsets.UTF_8);
        try {
            var result = fetcher.fetchOpen(
                    "https://news.google.com/rss/search?q=" + q + "&hl=vi&gl=VN&ceid=VN:vi",
                    SafeFetcher.ExpectedKind.RSS);
            return parsers.parseRss(result.body());
        } catch (Exception e) {
            log.warn("Google News RSS lỗi ('{}'), thử Bing News RSS: {}", query, e.getMessage());
        }
        try {
            var result = fetcher.fetchOpen(
                    "https://www.bing.com/news/search?q=" + q + "&format=RSS",
                    SafeFetcher.ExpectedKind.RSS);
            return parsers.parseRss(result.body());
        } catch (Exception e) {
            throw new DiscoveryFailedException(
                    "Cả Google News RSS lẫn Bing News RSS đều lỗi cho query '" + query + "': " + e.getMessage());
        }
    }

    /** fail loud — không âm thầm trả danh sách rỗng khi cả 2 nguồn RSS đều lỗi. */
    public static class DiscoveryFailedException extends RuntimeException {
        public DiscoveryFailedException(String message) { super(message); }
    }
}
