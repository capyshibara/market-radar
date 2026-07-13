package com.marketradar.fetch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * Lớp fetch AN TOÀN duy nhất của hệ thống — mọi request ra ngoài PHẢI đi qua đây.
 *
 * Các lớp phòng thủ (theo yêu cầu "crawl không dính mã độc"):
 *  1. Scheme: chỉ https (config https-only).
 *  2. Host whitelist: URL phải khớp CHÍNH XÁC allowedHost của source — kể cả link
 *     lấy từ trong RSS entry cũng phải qua kiểm tra này.
 *  3. SSRF guard: resolve DNS rồi CHẶN mọi IP private/loopback/link-local/multicast
 *     (chống trường hợp nguồn bị chiếm quyền trả về redirect/URL trỏ vào mạng nội bộ).
 *  4. KHÔNG follow redirect (Redirect.NEVER) — 3xx là fail-loud, log và skip,
 *     vì redirect là vector phổ biến để thoát whitelist.
 *  5. Content-Type phải khớp loại nguồn khai báo (HTML/RSS/PDF) — file .exe đội lốt bị chặn.
 *  6. Giới hạn dung lượng body (đọc stream có cap) + timeout kết nối/request.
 *  7. Nội dung tải về chỉ được xử lý như DỮ LIỆU: trích text bằng parser,
 *     không bao giờ thực thi, không render HTML thô (template chỉ dùng th:text).
 *
 * Lưu ý còn lại (nói thẳng): PDF độc hại nhắm vào lỗ hổng parser vẫn là rủi ro lý thuyết
 * — giảm thiểu bằng size cap + PDFBox bản vá mới + chỉ ingest PDF từ nguồn tier 1-2.
 * Muốn chặt hơn nữa (ngoài scope MVP): chạy parser trong sandbox/container riêng.
 */
@Component
public class SafeFetcher {

    private static final Logger log = LoggerFactory.getLogger(SafeFetcher.class);

    private final HttpClient client;
    private final long maxBodyBytes;
    private final Duration requestTimeout;
    private final boolean httpsOnly;

    public SafeFetcher(
            @Value("${marketradar.fetch.connect-timeout-seconds:5}") long connectTimeoutSec,
            @Value("${marketradar.fetch.request-timeout-seconds:15}") long requestTimeoutSec,
            @Value("${marketradar.fetch.max-body-bytes:5242880}") long maxBodyBytes,
            @Value("${marketradar.fetch.https-only:true}") boolean httpsOnly) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSec))
                .followRedirects(HttpClient.Redirect.NEVER)   // phòng thủ #4
                .build();
        this.requestTimeout = Duration.ofSeconds(requestTimeoutSec);
        this.maxBodyBytes = maxBodyBytes;
        this.httpsOnly = httpsOnly;
    }

    /** Content-Type hợp lệ theo loại nguồn */
    private static final Set<String> HTML_TYPES = Set.of("text/html", "application/xhtml+xml");
    private static final Set<String> RSS_TYPES  = Set.of("application/rss+xml", "application/atom+xml",
                                                         "application/xml", "text/xml");
    private static final Set<String> PDF_TYPES  = Set.of("application/pdf");
    private static final Set<String> JSON_TYPES = Set.of("application/json", "text/json");

    public enum ExpectedKind { HTML, RSS, PDF, JSON }

    /**
     * Fetch một URL với đầy đủ kiểm tra. Trả về FetchResult (bytes + content type),
     * hoặc ném FetchRejectedException với LÝ DO RÕ RÀNG (fail loud, phục vụ audit log).
     */
    public FetchResult fetch(String url, String allowedHost, ExpectedKind kind)
            throws FetchRejectedException {
        return fetch(url, allowedHost, kind, null);
    }

    /**
     * Biến thể POST (postJsonBody != null): dùng cho API danh sách trả JSON qua POST
     * (vd MOF_ISA /api/article/reads cần body {"rootCategoryId":...}). MỌI lớp phòng
     * thủ #1–#6 GIỮ NGUYÊN — chỉ khác method + body + Content-Type. postJsonBody == null
     * ⇒ GET như cũ (mọi caller cũ không đổi).
     */
    public FetchResult fetch(String url, String allowedHost, ExpectedKind kind, String postJsonBody)
            throws FetchRejectedException {

        final URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new FetchRejectedException("Invalid URL: " + url);
        }

        // #1 scheme
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (httpsOnly && !"https".equals(scheme)) {
            throw new FetchRejectedException("Rejected scheme '" + scheme + "' (https only): " + url);
        }
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new FetchRejectedException("Unsupported scheme: " + scheme);
        }

        // #2 host whitelist — exact match
        String host = uri.getHost();
        if (host == null || !host.equalsIgnoreCase(allowedHost)) {
            throw new FetchRejectedException(
                    "Host '" + host + "' does not match whitelist '" + allowedHost + "'");
        }

        // #3 SSRF guard — chặn IP nội bộ sau khi resolve DNS
        try {
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (addr.isLoopbackAddress() || addr.isSiteLocalAddress()
                        || addr.isLinkLocalAddress() || addr.isMulticastAddress()
                        || addr.isAnyLocalAddress()) {
                    throw new FetchRejectedException(
                            "Host resolved to an internal IP (" + addr.getHostAddress() + ") — SSRF blocked");
                }
            }
        } catch (UnknownHostException e) {
            throw new FetchRejectedException("Could not resolve host: " + host);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("User-Agent", "MarketRadar-MVP/0.1 (internal research; contact: market-radar)")
                .header("Accept", acceptHeaderFor(kind));
        if (postJsonBody != null) {
            builder.header("Content-Type", "application/json")
                   .POST(HttpRequest.BodyPublishers.ofString(postJsonBody, StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }
        HttpRequest request = builder.build();

        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new FetchRejectedException("Network error fetching " + url + ": " + e.getMessage());
        }

        int status = response.statusCode();
        // #4 redirect = fail loud
        if (status >= 300 && status < 400) {
            String location = response.headers().firstValue("Location").orElse("(no Location header)");
            throw new FetchRejectedException(
                    "Source returned redirect " + status + " → " + location
                    + " — not followed (safety policy). Update fetchUrl in the registry if the URL changed.");
        }
        if (status != 200) {
            throw new FetchRejectedException("HTTP " + status + " from " + url);
        }

        // #5 content-type check
        String contentType = response.headers().firstValue("Content-Type")
                .orElse("").split(";")[0].trim().toLowerCase(Locale.ROOT);
        if (!allowedTypesFor(kind).contains(contentType)) {
            throw new FetchRejectedException(
                    "Content-Type '" + contentType + "' does not match source type " + kind);
        }

        // #6 đọc body có giới hạn dung lượng
        byte[] body = readWithCap(response.body(), maxBodyBytes, url);
        log.info("Fetched OK: {} ({} bytes, {})", url, body.length, contentType);
        return new FetchResult(body, contentType);
    }

    private byte[] readWithCap(InputStream in, long cap, String url) throws FetchRejectedException {
        try (in) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > cap) {
                    throw new FetchRejectedException(
                            "Body exceeds " + cap + " byte cap — blocked: " + url);
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new FetchRejectedException("Error reading body from " + url + ": " + e.getMessage());
        }
    }

    private static String acceptHeaderFor(ExpectedKind kind) {
        return switch (kind) {
            case HTML -> "text/html,application/xhtml+xml";
            case RSS  -> "application/rss+xml,application/atom+xml,application/xml,text/xml";
            case PDF  -> "application/pdf";
            case JSON -> "application/json";
        };
    }

    private static Set<String> allowedTypesFor(ExpectedKind kind) {
        return switch (kind) {
            case HTML -> HTML_TYPES;
            case RSS  -> RSS_TYPES;
            case PDF  -> PDF_TYPES;
            case JSON -> JSON_TYPES;
        };
    }

    public record FetchResult(byte[] body, String contentType) {}

    public static class FetchRejectedException extends Exception {
        public FetchRejectedException(String message) { super(message); }
    }
}
