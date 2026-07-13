package com.marketradar.report;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.transaction.annotation.Transactional;
import com.marketradar.domain.Classification;
import com.marketradar.pipeline.ClassificationJob;
import com.marketradar.repo.ClassificationRepository;
import com.marketradar.repo.LlmCallLogRepository;
import com.marketradar.repo.RoutingRuleRepository;

@Controller
public class ClassificationController {

    private final ClassificationRepository classifications;
    private final RoutingRuleRepository routingRules;
    private final ClassificationJob job;
    private final LlmCallLogRepository callLog;

    public ClassificationController(ClassificationRepository classifications,
                                    RoutingRuleRepository routingRules,
                                    ClassificationJob job,
                                    LlmCallLogRepository callLog) {
        this.classifications = classifications;
        this.routingRules = routingRules;
        this.job = job;
        this.callLog = callLog;
    }

    /** Trang audit: nhãn + vote + trạng thái + routing của từng doc */
    @GetMapping("/classifications")
    public String list(Model model) {
        model.addAttribute("items", classifications.findAllForDisplay());
        model.addAttribute("rules", routingRules.findAll());
        return "classifications";
    }

    @PostMapping("/classify/run")
    @ResponseBody
    public String run() {
        return job.runOnce();
    }

    /**
     * Force Retry mirror của ClaimController#forceRetry (batch 9): xoá Classification
     * + cache LLM ("CLASSIFY") của MỘT doc đang UNCERTAIN_REVIEW/NO_LABEL_REVIEW để lần
     * chạy /classify/run tiếp theo xử lý lại — thay cho SQL tay. CHỈ xoá khi doc đang ở
     * 1 trong 2 trạng thái review đó, tránh xoá nhầm CONFIRMED/OUT_OF_SCOPE đã có kết quả.
     */
    @PostMapping("/classify/force-retry/{rawDocId}")
    @ResponseBody
    @Transactional
    public String forceRetry(@PathVariable Long rawDocId) {
        boolean needsRetry = classifications.findAllForDisplay().stream()
                .anyMatch(c -> c.getRawDoc() != null && rawDocId.equals(c.getRawDoc().getId())
                        && (c.getStatus() == Classification.Status.UNCERTAIN_REVIEW
                            || c.getStatus() == Classification.Status.NO_LABEL_REVIEW));
        if (!needsRetry) {
            return "Không tìm thấy classification UNCERTAIN_REVIEW/NO_LABEL_REVIEW cho doc#" + rawDocId
                    + " — không có gì để retry.";
        }
        classifications.deleteByRawDocId(rawDocId);
        callLog.deleteByPurposeAndRawDocId("CLASSIFY", rawDocId);
        return "Đã xoá classification + cache của doc#" + rawDocId
                + " — chạy lại POST /classify/run (hoặc bấm Run classify + routing ở /classifications) để thử lại.";
    }
}
