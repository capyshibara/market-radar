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

    public enum ExpectedKind { HTML, RSS, PDF }

    /**
     * Fetch một URL với đầy đủ kiểm tra. Trả về FetchResult (bytes + content type),
     * hoặc ném FetchRejectedException với LÝ DO RÕ RÀNG (fail loud, phục vụ audit log).
     */
    public FetchResult fetch(String url, String allowedHost, ExpectedKind kind)
            throws FetchRejectedException {

        final URI uri = parseUri(url);

        // #2 host whitelist — exact match (CHỈ áp dụng cho fetch() whitelist; fetchOpen() bỏ qua bước này)
        String host = uri.getHost();
        if (host == null || !host.equalsIgnoreCase(allowedHost)) {
            throw new FetchRejectedException(
                    "Host '" + host + "' không khớp whitelist '" + allowedHost + "'");
        }

        return doFetch(uri, kind);
    }

    /**
     * Batch — Phase 2 (nguồn 2, dynamic search): fetch KHÔNG whitelist host — dùng cho URL
     * do NewsDiscoveryService tìm ra, không biết trước domain. Vẫn giữ NGUYÊN mọi lớp phòng thủ
     * còn lại (scheme/SSRF/no-redirect/content-type/size cap) — chỉ bỏ riêng bước #2.
     * Gọi hàm này KHÔNG được gắn vào Source có allowedHost thật — chỉ dùng cho luồng nghiên cứu mở.
     */
    public FetchResult fetchOpen(String url, ExpectedKind kind) throws FetchRejectedException {
        return doFetch(parseUri(url), kind);
    }

    private URI parseUri(String url) throws FetchRejectedException {
        try {
            return URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new FetchRejectedException("URL không hợp lệ: " + url);
        }
    }

    /**
     * Kiểm scheme (#1) + SSRF guard (#3) — tách riêng để dùng cho MỌI đường lấy dữ liệu ra ngoài,
     * kể cả đường KHÔNG qua HttpClient của lớp này (VD Playwright ở BrowserRenderService, nguồn 3).
     * Đây là "cổng an toàn" duy nhất — không được có đường lấy dữ liệu ngoài nào bỏ qua hàm này.
     */
    public void assertSafeUrl(String url) throws FetchRejectedException {
        assertSafeUrl(parseUri(url));
    }

    private void assertSafeUrl(URI uri) throws FetchRejectedException {
        String url = uri.toString();
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (httpsOnly && !"https".equals(scheme)) {
            throw new FetchRejectedException("Từ chối scheme '" + scheme + "' (chỉ cho phép https): " + url);
        }
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new FetchRejectedException("Scheme không được hỗ trợ: " + scheme);
        }
        String host = uri.getHost();
        try {
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (addr.isLoopbackAddress() || addr.isSiteLocalAddress()
                        || addr.isLinkLocalAddress() || addr.isMulticastAddress()
                        || addr.isAnyLocalAddress()) {
                    throw new FetchRejectedException(
                            "Host resolve về IP nội bộ (" + addr.getHostAddress() + ") — chặn SSRF");
                }
            }
        } catch (UnknownHostException e) {
            throw new FetchRejectedException("Không resolve được host: " + host);
        }
    }

    /** Lõi dùng chung cho fetch() và fetchOpen(): #1, #3-#6 (mọi phòng thủ TRỪ host whitelist #2). */
    private FetchResult doFetch(URI uri, ExpectedKind kind) throws FetchRejectedException {
        String url = uri.toString();
        assertSafeUrl(uri); // #1 scheme + #3 SSRF

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("User-Agent", "MarketRadar-MVP/0.1 (internal research; contact: market-radar)")
                .header("Accept", acceptHeaderFor(kind))
                .GET()
                .build();

        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new FetchRejectedException("Lỗi mạng khi fetch " + url + ": " + e.getMessage());
        }

        int status = response.statusCode();
        // #4 redirect = fail loud
        if (status >= 300 && status < 400) {
            String location = response.headers().firstValue("Location").orElse("(không có Location)");
            throw new FetchRejectedException(
                    "Nguồn trả redirect " + status + " → " + location
                    + " — không follow (chính sách an toàn). Cập nhật fetchUrl trong registry nếu URL đã đổi.");
        }
        if (status != 200) {
            throw new FetchRejectedException("HTTP " + status + " từ " + url);
        }

        // #5 content-type check
        String contentType = response.headers().firstValue("Content-Type")
                .orElse("").split(";")[0].trim().toLowerCase(Locale.ROOT);
        if (!allowedTypesFor(kind).contains(contentType)) {
            throw new FetchRejectedException(
                    "Content-Type '" + contentType + "' không khớp loại nguồn " + kind);
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
                            "Body vượt giới hạn " + cap + " bytes — chặn: " + url);
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new FetchRejectedException("Lỗi đọc body từ " + url + ": " + e.getMessage());
        }
    }

    private static String acceptHeaderFor(ExpectedKind kind) {
        return switch (kind) {
            case HTML -> "text/html,application/xhtml+xml";
            case RSS  -> "application/rss+xml,application/atom+xml,application/xml,text/xml";
            case PDF  -> "application/pdf";
        };
    }

    private static Set<String> allowedTypesFor(ExpectedKind kind) {
        return switch (kind) {
            case HTML -> HTML_TYPES;
            case RSS  -> RSS_TYPES;
            case PDF  -> PDF_TYPES;
        };
    }

    public record FetchResult(byte[] body, String contentType) {}

    public static class FetchRejectedException extends Exception {
        public FetchRejectedException(String message) { super(message); }
    }
}
