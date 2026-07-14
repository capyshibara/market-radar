package com.marketradar.pipeline;

import org.springframework.beans.factory.annotation.Qualifier;
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

    public PipelineRunnerController(IngestionJob ingest, ClassificationJob classify,
                                    FactExtractionJob extract, InterpretationJob interpret,
                                    VerificationJob verify, PipelineRunStatusService status,
                                    @Qualifier("classifierLlmClient") LlmClient classifierClient,
                                    LlmClient writerClient, // @Primary
                                    @Qualifier("verifierLlmClient") LlmClient verifierClient) {
        this.ingest = ingest;
        this.classify = classify;
        this.extract = extract;
        this.interpret = interpret;
        this.verify = verify;
        this.status = status;
        this.classifierClient = (SwitchableLlmClient) classifierClient;
        this.writerClient = (SwitchableLlmClient) writerClient;
        this.verifierClient = (SwitchableLlmClient) verifierClient;
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

    private Supplier<String> jobFor(String stage) {
        return switch (stage) {
            case "ingest" -> ingest::runOnce;
            case "classify" -> classify::runOnce;
            case "extract" -> extract::runOnce;
            case "interpret" -> interpret::runOnce;
            case "verify" -> verify::runOnce;
            default -> null;
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
