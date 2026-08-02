package com.marketradar.source;

import com.marketradar.domain.Source;
import com.marketradar.fetch.SafeFetcher;
import com.marketradar.repo.SourceRepository;
import com.marketradar.research.BrowserRenderService;
import com.marketradar.research.BrowserRenderService.BrowserRenderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Kiểm tra hàng loạt: mỗi nguồn active trong whitelist có fetch được không, và
 * bằng đường nào — SafeFetcher (HTTP thường) trước, Playwright/BrowserRenderService
 * (trình duyệt headless) làm phương án dự phòng khi HTTP thất bại.
 *
 * KHÔNG ghi RawDoc/EvidenceFact nào — đây thuần là chẩn đoán trước khi crawl thật,
 * không phải một đường ingest. Chạy TUẦN TỰ (không song song) để không mở nhiều
 * Chromium cùng lúc (BrowserRenderService tự khởi/đóng trình duyệt mỗi lần gọi).
 *
 * NGOẠI LỆ DUY NHẤT với "không ghi gì" ở trên: một nguồn kiểm tra OK (HTTP hoặc
 * BROWSER) thì được set urlUnverified=false trên chính Source đó (không đụng
 * RawDoc/EvidenceFact/ingest). Trước khi có việc này, cờ "URL verified" trên
 * /sources đứng yên mãi mãi ở "Unverified" cho toàn bộ nguồn seed sẵn (được người
 * viết verify tay lúc phát triển nhưng chưa từng set cờ) — gây hiểu nhầm cờ đó
 * phản ánh tình trạng sống hiện tại, trong khi nó chỉ phản ánh lúc tạo nguồn.
 *
 * QUAN TRỌNG (đọc kỹ khi diễn giải kết quả "OK qua BROWSER"): IngestionJob hiện
 * KHÔNG dùng BrowserRenderService làm phương án dự phòng tự động — trạng thái
 * "OK qua BROWSER" chỉ nghĩa là ĐANG TỒN TẠI cách lấy được nội dung, không nghĩa là
 * lần crawl định kỳ tiếp theo sẽ tự làm vậy. Nguồn nào rơi vào nhóm này cần nhập
 * thủ công qua Nghiên cứu → Render trình duyệt (/research/render) cho tới khi khớp
 * nối tự động được xây (xem javadoc IngestionJob).
 */
@Service
public class SourceHealthCheckService {

    private static final Logger log = LoggerFactory.getLogger(SourceHealthCheckService.class);
    /** Ngưỡng loại "vỏ trang trống" (SPA chưa render xong) khỏi bị tính là thành công. */
    private static final int MIN_MEANINGFUL_HTML_CHARS = 200;

    public enum Method { HTTP, BROWSER, NONE }

    public record Result(String code, String name, int tier, Method method, boolean ok,
                         String detail, long elapsedMs) {}

    private final SourceRepository sources;
    private final SafeFetcher fetcher;
    private final BrowserRenderService browserRender;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "source-health-check");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, Result> results = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger completed = new AtomicInteger(0);
    private volatile int total = 0;
    private volatile Instant startedAt;
    private volatile Instant finishedAt;

    public SourceHealthCheckService(SourceRepository sources, SafeFetcher fetcher,
                                    BrowserRenderService browserRender) {
        this.sources = sources;
        this.fetcher = fetcher;
        this.browserRender = browserRender;
    }

    /** @return false nếu đã có lượt chạy khác đang chạy — không xếp chồng. */
    public synchronized boolean trigger() {
        if (running.get()) return false;
        List<Source> active = sources.findAll().stream()
                .filter(Source::isActive)
                .sorted(Comparator.comparingInt(Source::getTier).thenComparing(Source::getCode))
                .toList();
        results.clear();
        completed.set(0);
        total = active.size();
        startedAt = Instant.now();
        finishedAt = null;
        running.set(true);
        executor.submit(() -> {
            try {
                for (Source source : active) {
                    long t0 = System.currentTimeMillis();
                    Result result;
                    try {
                        result = checkOne(source);
                    } catch (Throwable crash) {
                        // Một nguồn crash không được phép làm 41 nguồn còn lại không bao giờ chạy —
                        // đây chính là lỗi đã bắt được lúc kiểm thử (Playwright ném lỗi driver chưa
                        // từng thấy, chưa được khai báo, làm cả lượt kiểm tra im lặng dừng ở 0/42).
                        log.error("Health check crashed on {}, continuing with remaining sources", source.getCode(), crash);
                        result = new Result(source.getCode(), source.getName(), source.getTier(), Method.NONE,
                                false, "Crashed: " + crash, System.currentTimeMillis() - t0);
                    }
                    results.put(source.getCode(), result);
                    completed.incrementAndGet();
                }
            } finally {
                finishedAt = Instant.now();
                running.set(false);
            }
        });
        return true;
    }

    /**
     * FWD_VN /vi/blog/ trả về ~7-8MB (nội dung thật, đã xác nhận thủ công — xem
     * IngestionJob.FWD_VN_MAX_BYTES) — vượt cap mặc định 5MB của SafeFetcher.fetch() 3 tham số.
     * IngestionJob đã tự nâng cap cho đúng nguồn này khi crawl thật; health check gọi
     * fetch() 3 tham số (cap mặc định) nên trước khi có dòng này, FWD_VN LUÔN báo "Not
     * reachable" qua HTTP dù nguồn thật sự sống — hai nơi phải khớp cap, không thể chỉ sửa
     * IngestionJob. Giữ đồng bộ nếu IngestionJob.FWD_VN_MAX_BYTES đổi.
     */
    private static final long FWD_VN_MAX_BYTES = 12L * 1024 * 1024;

    private Result checkOne(Source source) {
        long t0 = System.currentTimeMillis();
        try {
            SafeFetcher.FetchResult r = "FWD_VN".equals(source.getCode())
                    ? fetcher.fetch(source.getFetchUrl(), source.getAllowedHost(),
                            expectedKind(source.getType()), null, FWD_VN_MAX_BYTES)
                    : fetcher.fetch(source.getFetchUrl(), source.getAllowedHost(),
                            expectedKind(source.getType()));
            markVerified(source);
            return new Result(source.getCode(), source.getName(), source.getTier(), Method.HTTP, true,
                    "OK — " + r.body().length + " bytes, " + r.contentType(),
                    System.currentTimeMillis() - t0);
        } catch (SafeFetcher.FetchRejectedException httpFail) {
            return tryBrowser(source, httpFail.getMessage(), t0);
        } catch (Exception unexpected) {
            log.warn("Health check unexpected error for {}: {}", source.getCode(), unexpected.toString());
            return new Result(source.getCode(), source.getName(), source.getTier(), Method.NONE, false,
                    "Unexpected error: " + unexpected, System.currentTimeMillis() - t0);
        }
    }

    private Result tryBrowser(Source source, String httpReason, long t0) {
        try {
            String html = browserRender.renderHtml(source.getFetchUrl());
            int len = html == null ? 0 : html.strip().length();
            if (len >= MIN_MEANINGFUL_HTML_CHARS) {
                markVerified(source);
                return new Result(source.getCode(), source.getName(), source.getTier(), Method.BROWSER, true,
                        "HTTP failed (" + httpReason + ") — headless browser succeeded, " + len
                                + " chars. Not auto-wired into scheduled ingest yet; import manually via"
                                + " Research → Browser Render until it is.",
                        System.currentTimeMillis() - t0);
            }
            return new Result(source.getCode(), source.getName(), source.getTier(), Method.NONE, false,
                    "HTTP failed (" + httpReason + "); browser render returned only " + len
                            + " chars — likely still blocked or an empty shell.",
                    System.currentTimeMillis() - t0);
        } catch (Exception browserFail) {
            // Bắt Exception rộng, không chỉ BrowserRenderException khai báo: driver Playwright có
            // thể ném RuntimeException chưa từng thấy (vd lần đầu chạy tự tải trình duyệt thiếu mà
            // mạng bị chặn) — một nguồn lỗi kiểu này không được phép làm chết cả lượt kiểm tra 42
            // nguồn còn lại (đã từng xảy ra: completed dừng ở 0, toàn bộ batch im lặng thoát).
            log.warn("Health check browser fallback failed for {}: {}", source.getCode(), browserFail.toString());
            return new Result(source.getCode(), source.getName(), source.getTier(), Method.NONE, false,
                    "HTTP failed (" + httpReason + "); browser also failed (" + browserFail.getMessage() + ").",
                    System.currentTimeMillis() - t0);
        }
    }

    /** Chỉ set false khi hiện đang true — tránh ghi/flush thừa cho nguồn đã verified từ trước. */
    private void markVerified(Source source) {
        if (source.isUrlUnverified()) {
            source.setUrlUnverified(false);
            sources.save(source);
        }
    }

    private static SafeFetcher.ExpectedKind expectedKind(Source.SourceType type) {
        return switch (type) {
            case RSS -> SafeFetcher.ExpectedKind.RSS;
            case HTML -> SafeFetcher.ExpectedKind.HTML;
            case PDF -> SafeFetcher.ExpectedKind.PDF;
            case JSON -> SafeFetcher.ExpectedKind.JSON;
        };
    }

    public record Status(boolean running, int completed, int total, Instant startedAt,
                         Instant finishedAt, List<Result> results) {}

    public Status status() {
        List<Result> sorted = results.values().stream()
                .sorted(Comparator.comparingInt(Result::tier).thenComparing(Result::code))
                .toList();
        return new Status(running.get(), completed.get(), total, startedAt, finishedAt, sorted);
    }
}
