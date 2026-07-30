package com.marketradar.research;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * /research — trang chính với 2 chế độ tách bạch (mode toggle trong research.html):
 *  - Web Search (nhanh): 1 lượt tìm+đọc, không AI, xem WebSearchService.
 *  - Deep Research (agentic): vòng lặp AI nhiều bước, xem DeepResearchController.
 */
@Controller
public class WebSearchController {

    private final WebSearchService webSearch;

    public WebSearchController(WebSearchService webSearch) {
        this.webSearch = webSearch;
    }

    @GetMapping("/research")
    public String hub() {
        return "research";
    }

    @PostMapping("/research/search")
    public String search(@RequestParam(value = "q", required = false) String query, Model model) {
        if (query == null || query.isBlank()) {
            model.addAttribute("searchError", "Cần nhập câu hỏi/từ khoá tìm kiếm.");
            return "research";
        }
        try {
            model.addAttribute("searchResult", webSearch.search(query.strip()));
        } catch (NewsDiscoveryService.DiscoveryFailedException e) {
            model.addAttribute("searchError", "TÌM KIẾM THẤT BẠI: " + e.getMessage());
        }
        return "research";
    }
}
