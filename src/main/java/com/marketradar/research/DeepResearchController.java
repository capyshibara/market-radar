package com.marketradar.research;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
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

    /** Mỗi dòng không rỗng trong prompts = 1 job riêng, nộp cùng lúc — dùng CHUNG 1 khung thời
     *  gian (rangeStart/rangeEnd, tuỳ chọn) cho cả lô, vì đây là điều kiện lọc nguồn chứ không
     *  phải nội dung câu hỏi (không cần tách riêng theo từng dòng). */
    @PostMapping("/research/deep/enqueue")
    public String enqueue(@RequestParam(value = "prompts", required = false) String prompts,
                          @RequestParam(value = "rangeStart", required = false)
                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate rangeStart,
                          @RequestParam(value = "rangeEnd", required = false)
                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate rangeEnd,
                          @RequestParam(value = "vietnamOnly", required = false) Boolean vietnamOnly,
                          Model model) {
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
        if ((rangeStart == null) != (rangeEnd == null)) {
            model.addAttribute("promptError", "Cần nhập ĐỦ CẢ 2 mốc (từ ngày / đến ngày) hoặc để trống cả hai.");
            return "research-deep-create";
        }
        if (rangeStart != null && rangeEnd.isBefore(rangeStart)) {
            model.addAttribute("promptError", "\"Đến ngày\" phải sau hoặc bằng \"Từ ngày\".");
            return "research-deep-create";
        }
        boolean vnOnly = Boolean.TRUE.equals(vietnamOnly);
        for (String prompt : lines) {
            queue.enqueue(prompt, rangeStart, rangeEnd, vnOnly);
        }
        return "redirect:/research/history";
    }
}
