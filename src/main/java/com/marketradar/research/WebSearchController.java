package com.marketradar.research;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Web Search (chế độ "nhanh"): 1 lượt tìm+đọc, không AI — xem WebSearchService. Tách biệt hẳn
 * khỏi Deep Research (agentic, xem DeepResearchController) theo đúng cấu trúc menu Strategy yêu
 * cầu: mỗi chế độ có trang Create riêng + trang Management (lịch sử) riêng.
 */
@Controller
public class WebSearchController {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final WebSearchService webSearch;
    private final WebSearchRunRepository runs;

    public WebSearchController(WebSearchService webSearch, WebSearchRunRepository runs) {
        this.webSearch = webSearch;
        this.runs = runs;
    }

    /** Legacy entry point — giữ cho link/bookmark cũ. */
    @GetMapping("/research")
    public String legacyRedirect() {
        return "redirect:/research/web";
    }

    @GetMapping("/research/web")
    public String create() {
        return "research-web-create";
    }

    @PostMapping("/research/web/search")
    public String search(@RequestParam(value = "q", required = false) String query, Model model) {
        if (query == null || query.isBlank()) {
            model.addAttribute("searchError", "Cần nhập câu hỏi/từ khoá tìm kiếm.");
            return "research-web-create";
        }
        try {
            WebSearchService.Result result = webSearch.search(query.strip());
            runs.save(new WebSearchRun(query.strip(), result.items().size()));
            model.addAttribute("searchResult", result);
        } catch (NewsDiscoveryService.DiscoveryFailedException e) {
            model.addAttribute("searchError", "TÌM KIẾM THẤT BẠI: " + e.getMessage());
        }
        return "research-web-create";
    }

    public record RunRow(String query, String createdAtLabel, int resultCount) {}

    @GetMapping("/research/web/history")
    public String history(Model model) {
        List<RunRow> rows = runs.findAllByOrderByCreatedAtDesc().stream()
                .map(r -> new RunRow(r.getQuery(), TS_FMT.format(r.getCreatedAt().atZone(ZONE)), r.getResultCount()))
                .toList();
        model.addAttribute("runs", rows);
        return "research-web-history";
    }
}
