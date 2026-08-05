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
 * Browser render (nguồn render-JS, manual-trigger only): trả về HTML CUỐI CÙNG của một trang sau
 * khi JS đã chạy — dùng cho SPA/lazy-load mà SafeFetcher (HTTP tĩnh) chỉ lấy được shell rỗng
 * (đúng case MOF VN từng ghi nhận trước khi có REST-API crawl). Playwright tự host — build nặng
 * một lần (cài Chromium lúc deploy), đổi lấy zero chi phí biên theo lượt so với rendering API.
 *
 * AN TOÀN — điểm khác biệt then chốt với SafeFetcher: Playwright TỰ follow redirect (SafeFetcher
 * là Redirect.NEVER). Nếu chỉ kiểm SSRF ở URL ban đầu, một redirect 3xx có thể lách guard trỏ vào
 * mạng nội bộ. Vá bằng page.route("**"): kiểm TỪNG request thật (kể cả sau redirect/sub-resource)
 * qua đúng SafeFetcher.assertSafeUrl() — một nơi giữ luật SSRF duy nhất, không nhân đôi.
 *
 * Mỗi lần gọi tự khởi/đóng Chromium (không giữ tiến trình thường trực) — phù hợp tần suất
 * manual-trigger thấp; nếu sau này cần tần suất cao mới đáng đầu tư pool.
 *
 * Deploy thật cần: cài Chromium cho Playwright (java -cp <cp> com.microsoft.playwright.CLI
 * install chromium) hoặc trỏ executable-path tới Chromium có sẵn; nên set
 * PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 để không tự tải cả 3 browser lúc chạy lần đầu.
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
            fetcher.assertSafeUrl(url); // chặn ngay nếu URL gốc đã không an toàn
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

                // Analytics, tag managers and streaming widgets keep many publisher pages
                // permanently network-active. Waiting for NETWORKIDLE therefore rejects a
                // page whose article DOM is already complete (observed on BCG). The article
                // parser needs the rendered DOM, not the end of every tracking request.
                page.navigate(url, new Page.NavigateOptions()
                        .setTimeout(timeoutSeconds * 1000L)
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                page.waitForTimeout(Math.min(1_500L, timeoutSeconds * 100L));
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
