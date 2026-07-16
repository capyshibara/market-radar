package com.marketradar.fetch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ProtocolException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import javax.net.ssl.SSLException;

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
    private final int maxTransientRetries;
    private final long retryBackoffMillis;

    public SafeFetcher(
            @Value("${marketradar.fetch.connect-timeout-seconds:10}") long connectTimeoutSec,
            @Value("${marketradar.fetch.request-timeout-seconds:30}") long requestTimeoutSec,
            @Value("${marketradar.fetch.max-body-bytes:5242880}") long maxBodyBytes,
            @Value("${marketradar.fetch.https-only:true}") boolean httpsOnly,
            @Value("${marketradar.fetch.max-transient-retries:1}") int maxTransientRetries,
            @Value("${marketradar.fetch.retry-backoff-millis:400}") long retryBackoffMillis) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSec))
                .followRedirects(HttpClient.Redirect.NEVER)   // phòng thủ #4
                .build();
        this.requestTimeout = Duration.ofSeconds(requestTimeoutSec);
        this.maxBodyBytes = maxBodyBytes;
        this.httpsOnly = httpsOnly;
        // Keep a failing source bounded: at most three total attempts, even if a bad
        // deployment value is supplied. Retries are only for transient transport errors.
        this.maxTransientRetries = Math.max(0, Math.min(maxTransientRetries, 2));
        this.retryBackoffMillis = Math.max(0, Math.min(retryBackoffMillis, 2_000));
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
        return fetch(url, allowedHost, kind, postJsonBody, maxBodyBytes);
    }

    /** Trần cứng cho maxBytesOverride — vẫn phải bảo vệ chống payload tấn công dù nguồn cần cap lớn hơn. */
    private static final long MAX_BYTES_OVERRIDE_CEILING = 15L * 1024 * 1024;

    /**
     * Biến thể cho phép NÂNG cap #6 (readWithCap) trên MỘT lần gọi, dùng khi một nguồn cụ thể
     * có payload hợp lệ thật sự lớn hơn cap mặc định (vd FWD_VN /vi/blog/ embed ~331 bài trong
     * __NEXT_DATA__, ~7-8MB) — đã xác nhận thủ công là nội dung thật, không phải tấn công.
     * KHÔNG nới cap mặc định cho MỌI nguồn (giữ nguyên triết lý "không relax gate chung vì
     * một site" — xem ghi chú CafeF trong SeedData) — override chỉ áp dụng đúng 1 lần gọi này,
     * và vẫn bị chặn trần MAX_BYTES_OVERRIDE_CEILING dù caller truyền gì.
     */
    public FetchResult fetch(String url, String allowedHost, ExpectedKind kind,
                             String postJsonBody, long maxBytesOverride)
            throws FetchRejectedException {
        long effectiveCap = Math.min(Math.max(maxBytesOverride, maxBodyBytes), MAX_BYTES_OVERRIDE_CEILING);

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

        // A network failure can leave the remote side uncertain about whether a POST
        // was received. GET is safe to retry; POST remains single-attempt even though
        // current registry POST endpoints are read APIs.
        HttpResponse<InputStream> response = sendWithTransientRetry(request, url, postJsonBody == null);

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
        byte[] body = readWithCap(response.body(), effectiveCap, url);
        log.info("Fetched OK: {} ({} bytes, {})", url, body.length, contentType);
        return new FetchResult(body, contentType);
    }

    /**
     * Retries are deliberately narrow: only GET requests with a temporary transport
     * failure receive another attempt. We never retry POST, a rejected URL, redirect,
     * HTTP response, content-type failure, body-cap failure, DNS/SSL/protocol failure,
     * or an interrupted operator shutdown.
     */
    private HttpResponse<InputStream> sendWithTransientRetry(HttpRequest request, String url, boolean retryable)
            throws FetchRejectedException {
        for (int attempt = 0; ; attempt++) {
            try {
                return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new FetchRejectedException("Fetch interrupted for " + url);
            } catch (IOException error) {
                if (!retryable || !isTransientNetworkFailure(error) || attempt >= maxTransientRetries) {
                    throw new FetchRejectedException("Network error fetching " + url + ": " + error.getMessage());
                }
                log.info("Transient network failure fetching {} (attempt {}/{}): {}; retrying once after {} ms",
                        url, attempt + 1, maxTransientRetries + 1, error.getClass().getSimpleName(), retryBackoffMillis);
                if (!pauseBeforeRetry(url)) {
                    throw new FetchRejectedException("Fetch interrupted while waiting to retry " + url);
                }
            }
        }
    }

    private boolean pauseBeforeRetry(String url) {
        if (retryBackoffMillis == 0) return true;
        try {
            Thread.sleep(retryBackoffMillis);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.info("Interrupted while waiting to retry {}", url);
            return false;
        }
    }

    static boolean isTransientNetworkFailure(IOException error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof UnknownHostException || cause instanceof SSLException
                    || cause instanceof ProtocolException) {
                return false;
            }
            if (cause instanceof HttpConnectTimeoutException || cause instanceof HttpTimeoutException
                    || cause instanceof ConnectException) {
                return true;
            }
            cause = cause.getCause();
        }
        // Java's HttpClient may surface a temporary connection reset/EOF as a plain
        // IOException. The fixed low retry cap keeps that recovery attempt safe.
        return true;
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
