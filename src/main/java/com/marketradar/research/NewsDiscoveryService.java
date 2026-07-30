package com.marketradar.research;

import com.marketradar.fetch.SafeFetcher;
import com.marketradar.parse.ContentParsers;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Open research (nguồn tìm-kiếm-mở, manual-trigger only): tìm URL bài viết ứng viên cho một
 * câu hỏi tự do qua RSS tìm kiếm CÔNG KHAI — zero chi phí API, đánh đổi đã chọn chủ động:
 * Google/Bing News RSS là endpoint KHÔNG chính thức (không tài liệu hoá, không SLA, có thể đổi
 * định dạng/chặn bất kỳ lúc nào). Google là nguồn chính, Bing là dự phòng khi Google lỗi.
 *
 * Điểm khác biệt quan trọng với crawl whitelist: link trong 2 feed này KHÔNG phải bài viết thật —
 * Google trả link trung gian news.google.com (redirect), Bing bọc trong apiclick.aspx?url=…
 * Service này GIẢI về URL nhà-xuất-bản gốc một cách deterministic (đọc thẻ <a> trong description
 * HTML / decode query param) — item không giải được thì trả về kèm lý do, KHÔNG đoán (fail loud
 * per-item; tầng trên quyết định bỏ qua hay báo người dùng).
 *
 * Fetch feed đi qua SafeFetcher.fetch() với host của chính feed làm one-shot allowlist — giữ
 * nguyên mọi lớp SSRF/redirect/content-type/size-cap; bài viết ứng viên sau đó đi qua
 * ManualDocumentIntakeService.importUrl() (fetchDocument + parseArticleHtml + validate sẵn có).
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

    /** Ứng viên đã giải link: publisherUrl null ⇒ không giải được, lý do nằm trong note. */
    public record Candidate(String title, String publisherUrl, Instant publishedAt, String note) {}

    public List<Candidate> discover(String query) {
        String q = URLEncoder.encode(query, StandardCharsets.UTF_8);
        try {
            var result = fetcher.fetch(
                    "https://news.google.com/rss/search?q=" + q + "&hl=vi&gl=VN&ceid=VN:vi",
                    "news.google.com", SafeFetcher.ExpectedKind.RSS);
            return resolveAll(parsers.parseRss(result.body()));
        } catch (Exception e) {
            log.warn("Google News RSS lỗi ('{}'), thử Bing News RSS: {}", query, e.getMessage());
        }
        try {
            var result = fetcher.fetch(
                    "https://www.bing.com/news/search?q=" + q + "&format=RSS",
                    "www.bing.com", SafeFetcher.ExpectedKind.RSS);
            return resolveAll(parsers.parseRss(result.body()));
        } catch (Exception e) {
            throw new DiscoveryFailedException(
                    "Cả Google News RSS lẫn Bing News RSS đều lỗi cho query '" + query + "': " + e.getMessage());
        }
    }

    private List<Candidate> resolveAll(List<ContentParsers.RssItem> items) {
        List<Candidate> out = new ArrayList<>();
        for (var item : items) out.add(resolve(item));
        return out;
    }

    /** Giải link feed → URL nhà xuất bản thật. Deterministic, không network call thêm. */
    private Candidate resolve(ContentParsers.RssItem item) {
        String link = item.link();
        if (link == null || link.isBlank()) {
            return new Candidate(item.title(), null, item.publishedAt(), "item không có link");
        }
        String host = hostOf(link);

        // Google News: link item là trang trung gian news.google.com — URL thật nằm trong
        // thẻ <a href> đầu tiên của description HTML (định dạng ổn định của feed này).
        if (host != null && host.endsWith("news.google.com")) {
            String fromDesc = firstNonGoogleHref(item.descriptionHtml());
            return fromDesc != null
                    ? new Candidate(item.title(), fromDesc, item.publishedAt(), null)
                    : new Candidate(item.title(), null, item.publishedAt(),
                            "link Google News không giải được về URL nhà xuất bản (description không có href)");
        }

        // Bing News: link dạng bing.com/news/apiclick.aspx?...&url=<URL thật đã encode>
        if (host != null && host.endsWith("bing.com")) {
            String fromParam = queryParam(link, "url");
            return fromParam != null
                    ? new Candidate(item.title(), fromParam, item.publishedAt(), null)
                    : new Candidate(item.title(), null, item.publishedAt(),
                            "link Bing News không có query param 'url' để giải");
        }

        // Link đã là URL trực tiếp
        return new Candidate(item.title(), link, item.publishedAt(), null);
    }

    private static String firstNonGoogleHref(String descriptionHtml) {
        if (descriptionHtml == null || descriptionHtml.isBlank()) return null;
        for (var a : Jsoup.parse(descriptionHtml).select("a[href]")) {
            String href = a.attr("href");
            String host = hostOf(href);
            if (host != null && !host.endsWith("google.com") && href.startsWith("https://")) return href;
        }
        return null;
    }

    private static String queryParam(String url, String name) {
        try {
            String rawQuery = URI.create(url).getRawQuery();
            if (rawQuery == null) return null;
            for (String pair : rawQuery.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && pair.substring(0, eq).equals(name)) {
                    String decoded = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                    return decoded.startsWith("https://") ? decoded : null;
                }
            }
        } catch (Exception ignored) { /* URL lạ → không giải được, trả null (note ở caller) */ }
        return null;
    }

    private static String hostOf(String url) {
        try {
            String h = URI.create(url).getHost();
            return h == null ? null : h.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    /** fail loud — không âm thầm trả danh sách rỗng khi cả 2 nguồn RSS đều lỗi. */
    public static class DiscoveryFailedException extends RuntimeException {
        public DiscoveryFailedException(String message) { super(message); }
    }
}
