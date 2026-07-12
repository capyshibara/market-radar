package com.marketradar.pipeline;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import com.marketradar.extract.FactExtractionJob;
import com.marketradar.interpret.InterpretationJob;
import com.marketradar.verify.VerificationJob;

/**
 * Batch 8 — Pipeline Runner (/pipeline): trang chạy tay TOÀN pipeline bằng nút,
 * thay cho chuỗi curl trong terminal (yêu cầu demo: mọi bước là button trên UI).
 * Mỗi nút gọi ĐÚNG job như endpoint curl tương ứng — không thêm logic mới,
 * chỉ là bề mặt UI. Output stage gần nhất hiển thị ngay dưới hàng nút.
 *
 * Lưu ý demo: các stage gọi LLM thật có thể chạy PHÚT (DeepSeek giờ cao điểm) —
 * demo nên chạy trên data đã có sẵn trong DB file + replay cache (0 API call).
 */
@Controller
public class PipelineRunnerController {

    private final IngestionJob ingest;
    private final ClassificationJob classify;
    private final FactExtractionJob extract;
    private final InterpretationJob interpret;
    private final VerificationJob verify;

    public PipelineRunnerController(IngestionJob ingest, ClassificationJob classify,
                                    FactExtractionJob extract, InterpretationJob interpret,
                                    VerificationJob verify) {
        this.ingest = ingest;
        this.classify = classify;
        this.extract = extract;
        this.interpret = interpret;
        this.verify = verify;
    }

    @GetMapping("/pipeline")
    public String page(Model model) {
        model.addAllAttributes(java.util.Map.of()); // flash attrs (stage/output) tự bind nếu có
        return "pipeline";
    }

    @PostMapping("/pipeline/run/{stage}")
    public String run(@PathVariable String stage, RedirectAttributes redirect) {
        String output;
        try {
            output = switch (stage) {
                case "ingest" -> ingest.runOnce();
                case "classify" -> classify.runOnce();   // gồm cả dedup pre-pass
                case "extract" -> extract.runOnce();
                case "interpret" -> interpret.runOnce();
                case "verify" -> verify.runOnce();
                default -> "Stage không tồn tại: " + stage;
            };
        } catch (Exception e) {
            output = "LỖI stage " + stage + ": " + e.getMessage(); // fail loud lên UI, không nuốt lỗi
        }
        redirect.addFlashAttribute("stage", stage);
        redirect.addFlashAttribute("output", output);
        return "redirect:/pipeline";
    }
}
