package com.marketradar.research;

import com.marketradar.fetch.SafeFetcher;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.WaitUntilState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/**
 * Nguồn 3 (browser thật — render JS) — dùng khi trang là SPA/lazy-load mà fetchOpen (HTTP thô,
 * nguồn 2) không lấy được nội dung thật (VD case MOF VN đã ghi chú trong IngestionJob: site render
 * bằng JS nên SafeFetcher chỉ lấy shell rỗng). Chỉ manual-trigger — mỗi lần gọi tự khởi/đóng
 * Chromium (không giữ tiến trình thường trực), phù hợp tần suất thấp.
 *
 * Playwright tự host (đã chốt ở phần thảo luận chi phí dài hạn — build nặng 1 lần, không có chi
 * phí biên theo lượt như rendering API trả phí).
 *
 * AN TOÀN: Playwright.navigate() TỰ ĐỘNG follow redirect (khác HttpClient của SafeFetcher —
 * Redirect.NEVER). Nếu chỉ kiểm SSRF ở URL ban đầu, một redirect 3xx có thể lách guard và trỏ
 * vào mạng nội bộ. Vá bằng page.route(): chặn SSRF cho TỪNG request thật (kể cả sau redirect),
 * dùng lại nguyên SafeFetcher.assertSafeUrl() — không có 2 nơi giữ logic SSRF.
 */
@Service
public class BrowserRenderService {

    private static final Logger log = LoggerFactory.getLogger(BrowserRenderService.class);

    private final SafeFetcher fetcher;
    private final String executablePath;
    private final boolean headless;
    private final int timeoutSeconds;

    public BrowserRenderService(
            SafeFetcher fetcher,
            @Value("${marketradar.browser.executable-path:}") String executablePath,
            @Value("${marketradar.browser.headless:true}") boolean headless,
            @Value("${marketradar.browser.timeout-seconds:20}") int timeoutSeconds) {
        this.fetcher = fetcher;
        this.executablePath = executablePath;
        this.headless = headless;
        this.timeoutSeconds = timeoutSeconds;
    }

    /** Render 1 URL bằng Chromium thật (JS đã chạy xong), trả về HTML cuối cùng. */
    public String renderHtml(String url) throws BrowserRenderException {
        try {
            fetcher.assertSafeUrl(url); // chặn ngay từ đầu nếu URL gốc đã không an toàn
        } catch (SafeFetcher.FetchRejectedException e) {
            throw new BrowserRenderException("URL không an toàn: " + e.getMessage());
        }

        try (Playwright playwright = Playwright.create()) {
            BrowserType.LaunchOptions launchOpts = new BrowserType.LaunchOptions().setHeadless(headless);
            if (!executablePath.isBlank()) {
                launchOpts.setExecutablePath(Path.of(executablePath));
            }
            try (Browser browser = playwright.chromium().launch(launchOpts)) {
                Page page = browser.newPage();

                // Chặn SSRF cho MỌI request (kể cả sau redirect) — không tin riêng URL ban đầu.
                page.route("**/*", route -> {
                    try {
                        fetcher.assertSafeUrl(route.request().url());
                        route.resume();
                    } catch (SafeFetcher.FetchRejectedException e) {
                        log.warn("Chặn request không an toàn khi render {}: {}", url, e.getMessage());
                        route.abort();
                    }
                });

                page.navigate(url, new Page.NavigateOptions()
                        .setTimeout(timeoutSeconds * 1000L)
                        .setWaitUntil(WaitUntilState.NETWORKIDLE));
                return page.content();
            }
        } catch (PlaywrightException e) {
            throw new BrowserRenderException("Render lỗi cho " + url + ": " + e.getMessage());
        }
    }

    /** fail loud — không âm thầm trả HTML rỗng khi render lỗi/timeout. */
    public static class BrowserRenderException extends Exception {
        public BrowserRenderException(String message) { super(message); }
    }
}
