package com.marketradar.pipeline;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import com.marketradar.extract.FactExtractionJob;
import com.marketradar.interpret.InterpretationJob;
import com.marketradar.llm.LlmClient;
import com.marketradar.llm.SwitchableLlmClient;
import com.marketradar.verify.VerificationJob;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Batch 8/9 — Pipeline Runner (/pipeline): trang chạy tay TOÀN pipeline bằng nút,
 * thay cho chuỗi curl trong terminal.
 *
 * Batch 9 (feedback Hanh): job giờ chạy TRÊN EXECUTOR NỀN (PipelineRunStatusService)
 * — request POST trả về NGAY, không block cả phút khiến trang trông như "treo".
 * Trang /pipeline poll GET /pipeline/status.json mỗi vài giây để cập nhật badge
 * RUNNING/SUCCESS/FAILED + output, không cần reload. Mỗi stage cũng hiện provider/
 * model hiện tại (đọc trực tiếp từ SwitchableLlmClient — luôn đúng runtime, không
 * chỉ đúng lúc boot) kèm link sang /llm-settings để đổi.
 */
@Controller
public class PipelineRunnerController {

    // Broad refetch was deliberately retired. Recovery now requires the read-only plan and
    // explicit IDs (max 25) with confirm=true via TargetedRefetchController.
    private static final String[] STAGES = {"ingest", "classify", "extract", "interpret", "verify"};

    private final IngestionJob ingest;
    private final ClassificationJob classify;
    private final FactExtractionJob extract;
    private final InterpretationJob interpret;
    private final VerificationJob verify;
    private final PipelineRunStatusService status;
    private final SwitchableLlmClient classifierClient;
    private final SwitchableLlmClient writerClient;
    private final SwitchableLlmClient verifierClient;
    private final MessageSource messages;

    public PipelineRunnerController(IngestionJob ingest, ClassificationJob classify,
                                    FactExtractionJob extract, InterpretationJob interpret,
                                    VerificationJob verify, PipelineRunStatusService status,
                                    @Qualifier("classifierLlmClient") LlmClient classifierClient,
                                    LlmClient writerClient, // @Primary
                                    @Qualifier("verifierLlmClient") LlmClient verifierClient,
                                    MessageSource messages) {
        this.ingest = ingest;
        this.classify = classify;
        this.extract = extract;
        this.interpret = interpret;
        this.verify = verify;
        this.status = status;
        this.classifierClient = (SwitchableLlmClient) classifierClient;
        this.writerClient = (SwitchableLlmClient) writerClient;
        this.verifierClient = (SwitchableLlmClient) verifierClient;
        this.messages = messages;
    }

    @GetMapping("/pipeline")
    public String page(Model model) {
        model.addAttribute("ingestLlmLabel", "No LLM — deterministic fetch + hash dedup");
        model.addAttribute("classifyLlmLabel", llmLabel(classifierClient) + " (dedup pairwise uses Writer)");
        model.addAttribute("extractLlmLabel", llmLabel(writerClient));
        model.addAttribute("interpretLlmLabel", llmLabel(writerClient));
        model.addAttribute("verifyLlmLabel", llmLabel(verifierClient));
        return "pipeline";
    }

    @PostMapping("/pipeline/run/{stage}")
    public String run(@PathVariable String stage) {
        Supplier<String> job = jobFor(stage);
        if (job != null) status.trigger(stage, job);
        return "redirect:/pipeline";
    }

    /** A separate operator action samples the deferred/republication tail. */
    @PostMapping("/pipeline/run/extract-audit")
    public String runExtractionAudit() {
        status.trigger("extract", guarded("extract", extract::runAuditOnce, writerClient));
        return "redirect:/pipeline/researcher-curation";
    }

    /** Poll bằng JS — không dùng flash attribute nữa vì job chạy nền, không có response đồng bộ để redirect kèm theo. */
    @GetMapping("/pipeline/status.json")
    @ResponseBody
    public Map<String, Object> statusJson() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var entry : status.all(STAGES).entrySet()) {
            var s = entry.getValue();
            Map<String, Object> j = new LinkedHashMap<>();
            j.put("state", s.state().name());
            j.put("output", s.output());
            j.put("error", s.error());
            j.put("elapsedSeconds", s.startedAt() == null ? null
                    : Duration.between(s.startedAt(), s.finishedAt() != null ? s.finishedAt() : java.time.Instant.now()).getSeconds());
            PipelineRunStatusService.Progress p = status.getProgress(entry.getKey());
            j.put("completed", p == null ? null : p.completed());
            j.put("total", p == null ? null : p.total());
            out.put(entry.getKey(), j);
        }
        return out;
    }

    /** Read-only classification version plan; no dedup writes and no LLM calls. */
    @GetMapping(value = "/pipeline/classification/plan", produces = "text/plain;charset=UTF-8")
    @ResponseBody
    public String classificationPlan() {
        return classify.dryRunPlan();
    }

    /** Read-only Analyst budget/coverage plan; deliberately no LLM call or state mutation. */
    @GetMapping(value = "/pipeline/analysis/plan", produces = "text/plain;charset=UTF-8")
    @ResponseBody
    public String analysisPlan() {
        return interpret.dryRunInputPlan();
    }

    /** Read-only Researcher budget/priority preview; deliberately no LLM call or state mutation. */
    @GetMapping(value = "/pipeline/extraction/plan", produces = "text/plain;charset=UTF-8")
    @ResponseBody
    public String extractionPlan() {
        return extract.dryRunInputPlan();
    }

    /** Read-only stratified deferred-tail audit preview. */
    @GetMapping(value = "/pipeline/extraction/audit-plan", produces = "text/plain;charset=UTF-8")
    @ResponseBody
    public String extractionAuditPlan() {
        return extract.dryRunAuditPlan();
    }

    /** Human-readable curation control; opening it never calls a model. */
    @GetMapping("/pipeline/researcher-curation")
    public String researcherCuration(Model model) {
        var mainPlan = extract.curationPlan(
                com.marketradar.extract.ResearchCurationPlanner.Mode.MAIN);
        var auditPlan = extract.curationPlan(
                com.marketradar.extract.ResearchCurationPlanner.Mode.AUDIT);
        var assessment = extract.curationAssessment(mainPlan, auditPlan);
        model.addAttribute("mainPlan", mainPlan);
        model.addAttribute("auditPlan", auditPlan);
        model.addAttribute("mainPlanSummary", curationSummary(mainPlan));
        model.addAttribute("auditPlanSummary", curationSummary(auditPlan));
        model.addAttribute("assessment", assessment);
        model.addAttribute("assessmentLabel", message(
                "ops.researcher.assessment." + assessment.recommendation().name() + ".label"));
        model.addAttribute("assessmentMessage", switch (assessment.recommendation()) {
            case RUN_NEXT_BATCH -> message("ops.researcher.assessment.RUN_NEXT_BATCH.message",
                    mainPlan.diagnostics().remainingClusters());
            case RUN_DEFERRED_AUDIT -> message(
                    "ops.researcher.assessment.RUN_DEFERRED_AUDIT.message",
                    auditPlan.diagnostics().auditPoolDocuments());
            case AUDIT_FOUND_ADDITIONAL_VALUE -> message(
                    "ops.researcher.assessment.AUDIT_FOUND_ADDITIONAL_VALUE.message",
                    assessment.latestAudit() == null ? 0 : assessment.latestAudit().getNewEventClusters(),
                    assessment.latestAudit() == null ? 0 : assessment.latestAudit().getNewConflictClusters());
            case OPERATOR_REVIEW_REQUIRED -> message(
                    "ops.researcher.assessment.OPERATOR_REVIEW_REQUIRED.message",
                    mainPlan.diagnostics().exhaustedUnrepresentedClusters());
            case READY_FOR_ANALYST -> message(
                    "ops.researcher.assessment.READY_FOR_ANALYST.message");
            case NO_ELIGIBLE_INPUT -> message(
                    "ops.researcher.assessment.NO_ELIGIBLE_INPUT.message");
        });
        model.addAttribute("curationBatches", extract.recentCurationBatches(20));
        model.addAttribute("extractLlmLabel", llmLabel(writerClient));
        return "researcher-curation";
    }

    private String message(String code, Object... args) {
        return messages.getMessage(code, args, LocaleContextHolder.getLocale());
    }

    private String curationSummary(com.marketradar.extract.ResearchCurationPlanner.Plan plan) {
        String key = plan.mode() == com.marketradar.extract.ResearchCurationPlanner.Mode.MAIN
                ? "ops.researcher.plan.main" : "ops.researcher.plan.audit";
        return message(key, plan.diagnostics().selectedDocuments(),
                plan.diagnostics().selectedClusters(), plan.diagnostics().candidateClusters(),
                plan.diagnostics().representedClusters(), plan.diagnostics().remainingClusters(),
                plan.diagnostics().auditPoolDocuments());
    }

    private Supplier<String> jobFor(String stage) {
        return switch (stage) {
            case "ingest" -> ingest::runOnce;
            case "classify" -> guarded(stage, classify::runOnce, classifierClient, writerClient);
            case "extract" -> guarded(stage, extract::runOnce, writerClient);
            case "interpret" -> guarded(stage, interpret::runOnce, writerClient);
            case "verify" -> guarded(stage, verify::runOnce, verifierClient);
            default -> null;
        };
    }

    private static Supplier<String> guarded(String stage, Supplier<String> job,
                                             SwitchableLlmClient... clients) {
        return () -> {
            PipelineExecutionRules.requireConfigured(stage, clients);
            return job.get();
        };
    }

    private static String llmLabel(SwitchableLlmClient client) {
        var c = client.config();
        return switch (c.kind()) {
            case STUB, STUB_VERIFIER -> "STUB (no API key configured)";
            case ANTHROPIC -> "Anthropic — " + c.model();
            case OPENAI_COMPAT -> client.providerName();
        };
    }
}
