package com.marketradar.research;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.List;

/**
 * Deep Research (use case 1): người dùng nhập 1 HOẶC NHIỀU prompt (mỗi dòng 1 yêu cầu), mỗi
 * prompt trở thành 1 {@link DeepResearchRun} ở trạng thái QUEUED, xử lý TUẦN TỰ bởi
 * {@link DeepResearchQueueService} (xem class đó để biết vì sao không chạy song song).
 *
 * 2026-08-03 (v2, thay thế bản SSE cũ): trước đây tiến trình chỉ xem được qua kết nối SSE sống
 * cùng đúng 1 request/tab — đóng tab là mất theo dõi, và không nộp được nhiều prompt cùng lúc.
 * Giờ nộp xong là xong ngay (redirect sang /research/history), tiến trình xem ở đó bằng cách
 * poll /research/queue/status.json (xem DeepResearchHistoryController) — sống độc lập với tab đã
 * mở nó, xem lại được từ máy khác.
 */
@Controller
public class DeepResearchController {

    private final DeepResearchQueueService queue;

    public DeepResearchController(DeepResearchQueueService queue) {
        this.queue = queue;
    }

    /** Giữ route cũ cho bookmark/link cũ. */
    @GetMapping("/research/deep")
    public String legacyRedirect() {
        return "redirect:/research/deep/create";
    }

    @GetMapping("/research/deep/create")
    public String create() {
        return "research-deep-create";
    }

    /** Mỗi dòng không rỗng trong prompts = 1 job riêng, nộp cùng lúc. */
    @PostMapping("/research/deep/enqueue")
    public String enqueue(@RequestParam(value = "prompts", required = false) String prompts, Model model) {
        List<String> lines = new ArrayList<>();
        if (prompts != null) {
            for (String line : prompts.split("\\R")) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty()) lines.add(trimmed);
            }
        }
        if (lines.isEmpty()) {
            model.addAttribute("promptError", "Cần nhập ít nhất 1 yêu cầu nghiên cứu (mỗi dòng 1 yêu cầu).");
            return "research-deep-create";
        }
        for (String prompt : lines) {
            queue.enqueue(prompt);
        }
        return "redirect:/research/history";
    }
}
