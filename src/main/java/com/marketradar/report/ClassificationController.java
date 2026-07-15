package com.marketradar.report;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import com.marketradar.domain.Classification;
import com.marketradar.pipeline.ClassificationJob;
import com.marketradar.repo.ClassificationRepository;
import com.marketradar.repo.RoutingRuleRepository;

@Controller
public class ClassificationController {

    private final ClassificationRepository classifications;
    private final RoutingRuleRepository routingRules;
    private final ClassificationJob job;

    public ClassificationController(ClassificationRepository classifications,
                                    RoutingRuleRepository routingRules,
                                    ClassificationJob job) {
        this.classifications = classifications;
        this.routingRules = routingRules;
        this.job = job;
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
     * Append-only retry for one review-state document. The prior active result and
     * every LLM call remain auditable; replay-cache reads are bypassed for this call.
     */
    @PostMapping("/classify/force-retry/{rawDocId}")
    @ResponseBody
    public String forceRetry(@PathVariable Long rawDocId) {
        boolean needsRetry = classifications.findAllForDisplay().stream()
                .anyMatch(c -> c.getRawDoc() != null && rawDocId.equals(c.getRawDoc().getId())
                        && (c.getStatus() == Classification.Status.UNCERTAIN_REVIEW
                            || c.getStatus() == Classification.Status.NO_LABEL_REVIEW));
        if (!needsRetry) {
            return "Không tìm thấy classification UNCERTAIN_REVIEW/NO_LABEL_REVIEW cho doc#" + rawDocId
                    + " — không có gì để retry.";
        }
        return "Safe classification retry:\n" + job.retryOne(rawDocId);
    }
}
