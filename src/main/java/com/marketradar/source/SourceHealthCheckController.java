package com.marketradar.source;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Kiểm tra hàng loạt xem 42 nguồn whitelist có fetch được không, và bằng đường
 * nào (HTTP thường hay trình duyệt headless) — chẩn đoán TRƯỚC khi crawl thật,
 * không ghi gì vào kho dữ liệu. Chạy nền + poll, vì browser fallback cho nhiều
 * nguồn có thể mất vài phút.
 */
@Controller
public class SourceHealthCheckController {

    private final SourceHealthCheckService health;

    public SourceHealthCheckController(SourceHealthCheckService health) {
        this.health = health;
    }

    @PostMapping("/sources/health-check")
    public String trigger(RedirectAttributes redirect) {
        if (!health.trigger()) {
            redirect.addFlashAttribute("intakeError", "A health check is already running.");
        }
        return "redirect:/sources";
    }

    @GetMapping(path = "/sources/health-check/status.json")
    @ResponseBody
    public SourceHealthCheckService.Status status() {
        return health.status();
    }
}
