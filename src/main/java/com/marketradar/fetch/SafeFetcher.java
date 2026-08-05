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
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 *  4. Redirect.NEVER ở HttpClient. SafeFetcher chỉ tự theo tối đa 3 redirect GET
 *     CÙNG exact allowedHost, và chạy lại scheme/SSRF/host guard ở từng hop.
 *     Redirect POST hoặc cross-host vẫn fail-loud.
 *  5. Content-Type phải khớp loại nguồn khai báo (HTML/RSS/PDF) — file .exe đội lốt bị chặn.
 *  6. Giới hạn dung lượng body (đọc stream có cap) + timeout kết nối/request.
 *  7. Nội dung tải về chỉ được xử lý như DỮ LIỆU: trích text bằng parser,
 *     không bao giờ thực thi, không render HTML thô (template chỉ dùng th:text).
 *
 * Lưu ý còn lại (nói thẳng): PDF độc hại nhắm vào lỗ hổng parser vẫn là rủi ro lý thuyết
     * — giảm thiểu bằng size cap + PDFBox bản vá mới + whitelist/authority review.
 * Muốn chặt hơn nữa (ngoài scope MVP): chạy parser trong sandbox/container riêng.
 */
@Component
public class SafeFetcher {

    private static final Logger log = LoggerFactory.getLogger(SafeFetcher.class);
    private static final String MAP_LIFE_HOST = "www.map-life.com.vn";
    private static final String HNX_HOST = "hnx.vn";
    private static final String CA_MAU_POLICE_HOST = "congan.camau.gov.vn";

    private final HttpClient client;
    private final HttpClient mapLifeClient;
    private final HttpClient hnxClient;
    private final HttpClient caMauPoliceClient;
    private final long maxBodyBytes;
    private final Duration requestTimeout;
    private final boolean httpsOnly;
    private final int maxTransientRetries;
    private final long retryBackoffMillis;
    private final ExecutorService bodyReadExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "safe-fetch-body-reader");
        t.setDaemon(true);
        return t;
    });

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
        this.mapLifeClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSec))
                .followRedirects(HttpClient.Redirect.NEVER)
                .sslContext(PinnedIntermediateSslContext.create(
                        "/tls/globalsign-rsa-ov-ssl-ca-2018.pem"))
                .build();
        this.hnxClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSec))
                .followRedirects(HttpClient.Redirect.NEVER)
                .sslContext(PinnedIntermediateSslContext.create(
                        "/tls/globalsign-gcc-r3-ev-tls-ca-2025.pem"))
                .build();
        this.caMauPoliceClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSec))
                .followRedirects(HttpClient.Redirect.NEVER)
                .sslContext(PinnedIntermediateSslContext.create(
                        "/tls/globalsign-gcc-r3-dv-tls-ca-2020.pem"))
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
    private static final Set<String> XML_TYPES  = Set.of("application/rss+xml", "application/atom+xml",
                                                         "application/xml", "text/xml");
    private static final Set<String> PDF_TYPES  = Set.of("application/pdf");
    private static final Set<String> JSON_TYPES = Set.of("application/json", "text/json");
    private static final Set<String> TEXT_TYPES = Set.of("text/plain", "text/markdown");

    public enum ExpectedKind { HTML, RSS, SITEMAP, PDF, JSON, TEXT }

    /**
     * One-shot operator import for a specific public article or PDF. The URL's
     * own host becomes the exact one-request allowlist; every other SSRF,
     * redirect, content-type, timeout and body-size guard remains unchanged.
     */
    public FetchResult fetchDocument(String url) throws FetchRejectedException {
        final URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException invalid) {
            throw new FetchRejectedException("Invalid URL: " + url);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new FetchRejectedException("Document URL has no public host");
        }
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        ExpectedKind expected = path.endsWith(".pdf") ? ExpectedKind.PDF : ExpectedKind.HTML;
        try {
            return fetch(url, host, expected, null, MAX_BYTES_OVERRIDE_CEILING);
        } catch (FetchRejectedException first) {
            // Some download endpoints do not end in .pdf. Retry only when the
            // server proved that the content type—not URL safety—was the mismatch.
            if (expected == ExpectedKind.HTML
                    && first.getMessage() != null
                    && first.getMessage().contains("does not match source type HTML")) {
                return fetch(url, host, ExpectedKind.PDF, null, MAX_BYTES_OVERRIDE_CEILING);
            }
            throw first;
        }
    }

    /**
     * Kiểm scheme (#1) + SSRF guard (#3) cho MỌI đường lấy dữ liệu ra ngoài — kể cả đường KHÔNG
     * đi qua HttpClient của lớp này (BrowserRenderService/Playwright tự follow redirect nên phải
     * chặn từng request thật qua page.route(), không chỉ URL ban đầu). Không nhân đôi luật SSRF:
     * fetch() nội bộ cũng gọi đúng hàm này.
     */
    public void assertSafeUrl(String url) throws FetchRejectedException {
        final URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new FetchRejectedException("Invalid URL: " + url);
        }
        assertSafeUri(uri);
    }

    private void assertSafeUri(URI uri) throws FetchRejectedException {
        String url = uri.toString();
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (httpsOnly && !"https".equals(scheme)) {
            throw new FetchRejectedException("Rejected scheme '" + scheme + "' (https only): " + url);
        }
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new FetchRejectedException("Unsupported scheme: " + scheme);
        }
        String host = uri.getHost();
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
    }

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
    private static final long MAX_BYTES_OVERRIDE_CEILING = 32L * 1024 * 1024;

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

        // #1 scheme + #3 SSRF (dùng chung với assertSafeUrl — một nơi giữ luật duy nhất)
        assertSafeUri(uri);

        // #2 host whitelist — exact match
        String host = uri.getHost();
        if (host == null || !host.equalsIgnoreCase(allowedHost)) {
            throw new FetchRejectedException(
                    "Host '" + host + "' does not match whitelist '" + allowedHost + "'");
        }

        URI currentUri = uri;
        HttpRequest request = buildRequest(currentUri, kind, postJsonBody);

        // A network failure can leave the remote side uncertain about whether a POST
        // was received. GET is safe to retry; POST remains single-attempt even though
        // current registry POST endpoints are read APIs.
        HttpResponse<InputStream> response = sendWithTransientRetry(
                request, currentUri.toString(), postJsonBody == null);

        // Several legitimate Vietnam sites canonicalise paths within the same host
        // (for example /news -> /tin-tuc). Following those redirects is required to
        // reach the article body, but every hop remains inside the exact source host
        // and is re-checked for SSRF. Cross-host redirects are never followed.
        java.util.Set<String> visited = new java.util.HashSet<>();
        visited.add(currentUri.normalize().toString());
        int redirects = 0;
        while (response.statusCode() >= 300 && response.statusCode() < 400) {
            String location = response.headers().firstValue("Location").orElse("").strip();
            if (postJsonBody != null) {
                closeQuietly(response.body());
                throw new FetchRejectedException("Source returned redirect " + response.statusCode()
                        + " for POST — not followed (safety policy): " + currentUri);
            }
            if (location.isBlank()) {
                closeQuietly(response.body());
                throw new FetchRejectedException("Source returned redirect " + response.statusCode()
                        + " without Location header: " + currentUri);
            }
            if (redirects++ >= 3) {
                closeQuietly(response.body());
                throw new FetchRejectedException("More than 3 redirects from " + url + " — rejected");
            }
            final URI next;
            try {
                next = currentUri.resolve(location);
            } catch (IllegalArgumentException invalid) {
                closeQuietly(response.body());
                throw new FetchRejectedException("Invalid redirect Location '" + location + "' from " + currentUri);
            }
            assertSafeUri(next);
            String nextHost = next.getHost();
            if (nextHost == null || !nextHost.equalsIgnoreCase(allowedHost)) {
                closeQuietly(response.body());
                throw new FetchRejectedException("Cross-host redirect rejected: " + currentUri
                        + " → " + next + " (allowed host: " + allowedHost + ")");
            }
            String normalized = next.normalize().toString();
            if (!visited.add(normalized)) {
                closeQuietly(response.body());
                throw new FetchRejectedException("Redirect loop rejected at " + next);
            }
            closeQuietly(response.body());
            currentUri = next;
            request = buildRequest(currentUri, kind, null);
            response = sendWithTransientRetry(request, currentUri.toString(), true);
        }

        int status = response.statusCode();
        if (status != 200) {
            closeQuietly(response.body());
            throw new FetchRejectedException("HTTP " + status + " from " + currentUri);
        }

        // #5 content-type check
        String contentType = response.headers().firstValue("Content-Type")
                .orElse("").split(";")[0].trim().toLowerCase(Locale.ROOT);
        if (!allowedTypesFor(kind).contains(contentType)) {
            closeQuietly(response.body());
            throw new FetchRejectedException(
                    "Content-Type '" + contentType + "' does not match source type " + kind);
        }

        // #6 đọc body có giới hạn dung lượng
        // HttpRequest.timeout() stops at the point where BodyHandlers.ofInputStream()
        // hands us the response stream. A server can therefore send headers and then
        // stall the body forever unless the stream read has its own deadline.
        byte[] body = readWithCapTimed(response.body(), effectiveCap, currentUri.toString());
        String contentEncoding = response.headers().firstValue("Content-Encoding")
                .orElse("").split(";")[0].trim().toLowerCase(Locale.ROOT);
        if (!contentEncoding.isEmpty() && !"identity".equals(contentEncoding)) {
            body = decodeCompressedBody(body, contentEncoding, effectiveCap, currentUri.toString());
        }
        Instant lastModified = response.headers().firstValue("Last-Modified")
                .flatMap(SafeFetcher::parseHttpInstant)
                .orElse(null);
        log.info("Fetched OK: {} ({} bytes, {}{})", currentUri, body.length, contentType,
                contentEncoding.isEmpty() ? "" : ", was " + contentEncoding);
        return new FetchResult(body, contentType, lastModified);
    }

    private static java.util.Optional<Instant> parseHttpInstant(String raw) {
        try {
            return java.util.Optional.of(java.time.ZonedDateTime.parse(raw,
                    java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
        } catch (Exception ignored) {
            return java.util.Optional.empty();
        }
    }

    private HttpRequest buildRequest(URI uri, ExpectedKind kind, String postJsonBody) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("User-Agent", "MarketRadar-MVP/0.1 (internal research; contact: market-radar)")
                .header("Accept", acceptHeaderFor(kind))
                // 2026-08-04 (feedback: bản BI xem nhanh có 1 nguồn hiện chữ RÁC — hoá ra byte gzip
                // bị đọc thẳng như text). java.net.http.HttpClient KHÔNG tự giải nén — trước đây
                // không khai Accept-Encoding nên nhiều server (CDN/proxy) vẫn nén mặc định bất kể
                // client có xin hay không, và body nén bị parseArticleHtml() đọc như UTF-8 → rác.
                // Khai rõ để server biết ta hỗ trợ, rồi tự giải nén đúng Content-Encoding trả về
                // (xem decodeCompressedBody) — không còn im lặng chấp nhận byte nén làm "đọc được".
                .header("Accept-Encoding", "gzip, deflate");
        if (postJsonBody != null) {
            builder.header("Content-Type", "application/json")
                   .POST(HttpRequest.BodyPublishers.ofString(postJsonBody, StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }
        return builder.build();
    }

    private static void closeQuietly(InputStream in) {
        try {
            if (in != null) in.close();
        } catch (IOException ignored) {
            // Best-effort connection release on a rejected response.
        }
    }

    private byte[] readWithCapTimed(InputStream in, long cap, String url)
            throws FetchRejectedException {
        Future<byte[]> read = bodyReadExecutor.submit(() -> readWithCap(in, cap, url));
        try {
            return read.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timedOut) {
            try { in.close(); } catch (IOException ignored) {}
            read.cancel(true);
            throw new FetchRejectedException("Timed out reading response body after "
                    + requestTimeout.toSeconds() + "s: " + url);
        } catch (InterruptedException interrupted) {
            try { in.close(); } catch (IOException ignored) {}
            read.cancel(true);
            Thread.currentThread().interrupt();
            throw new FetchRejectedException("Fetch interrupted while reading body: " + url);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof FetchRejectedException rejected) throw rejected;
            throw new FetchRejectedException("Error reading body from " + url + ": "
                    + (cause == null ? failed.getMessage() : cause.getMessage()));
        }
    }

    /** Giải nén body theo ĐÚNG Content-Encoding server khai báo — không đoán, không im lặng đọc
     *  byte nén như text. br (Brotli) chưa có decoder built-in trong JDK và app chưa có thư viện
     *  ngoài cho việc này — fail loud thay vì trả về rác, còn hơn 1 "nguồn đọc được" thực ra là
     *  byte nén lọt vào báo cáo (xem lịch sử: đúng ca thật đã xảy ra với gzip). Cap dung lượng SAU
     *  giải nén giữ nguyên effectiveCap của lần gọi này — chặn decompression-bomb dù nguồn đã qua
     *  SSRF/host-whitelist. */
    static byte[] decodeCompressedBody(byte[] compressed, String contentEncoding, long cap, String url)
            throws FetchRejectedException {
        InputStream raw = switch (contentEncoding) {
            case "gzip", "x-gzip" -> {
                try {
                    yield new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(compressed));
                } catch (IOException e) {
                    throw new FetchRejectedException("Content-Encoding: gzip nhưng body không phải gzip hợp lệ ("
                            + url + "): " + e.getMessage());
                }
            }
            case "deflate" -> new java.util.zip.InflaterInputStream(new java.io.ByteArrayInputStream(compressed));
            default -> throw new FetchRejectedException("Server trả Content-Encoding '" + contentEncoding
                    + "' không hỗ trợ giải nén (" + url + ") — từ chối đọc byte nén như text thô.");
        };
        return readWithCap(raw, cap, url);
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
                String requestHost = request.uri().getHost();
                HttpClient selectedClient = MAP_LIFE_HOST.equalsIgnoreCase(requestHost)
                        ? mapLifeClient
                        : HNX_HOST.equalsIgnoreCase(requestHost) || ("www." + HNX_HOST).equalsIgnoreCase(requestHost)
                                ? hnxClient
                                : CA_MAU_POLICE_HOST.equalsIgnoreCase(requestHost)
                                        ? caMauPoliceClient : client;
                return selectedClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
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

    private static byte[] readWithCap(InputStream in, long cap, String url) throws FetchRejectedException {
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
            case RSS, SITEMAP -> "application/rss+xml,application/atom+xml,application/xml,text/xml";
            case PDF  -> "application/pdf";
            case JSON -> "application/json";
            case TEXT -> "text/plain,text/markdown";
        };
    }

    private static Set<String> allowedTypesFor(ExpectedKind kind) {
        return switch (kind) {
            case HTML -> HTML_TYPES;
            case RSS, SITEMAP -> XML_TYPES;
            case PDF  -> PDF_TYPES;
            case JSON -> JSON_TYPES;
            case TEXT -> TEXT_TYPES;
        };
    }

    public record FetchResult(byte[] body, String contentType, Instant lastModified) {
        /** Compatibility constructor retained for focused unit-test fakes. */
        public FetchResult(byte[] body, String contentType) {
            this(body, contentType, null);
        }
    }

    public static class FetchRejectedException extends Exception {
        public FetchRejectedException(String message) { super(message); }
    }
}
