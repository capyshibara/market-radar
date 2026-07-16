package com.marketradar.pipeline;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import com.marketradar.domain.PipelineItemLog;
import com.marketradar.domain.PipelineRunLog;
import com.marketradar.domain.RawDoc;
import com.marketradar.repo.PipelineItemLogRepository;
import com.marketradar.repo.PipelineRunLogRepository;
import com.marketradar.repo.RawDocRepository;

import java.util.*;

/**
 * Batch 10 (feedback Hanh — "hard to see which news is blocked in which stage,
 * no clue what runs are a real batch, 221 claims overwhelming"):
 *
 *  1. Cycle view: mỗi lần bấm Ingest mở MỘT cycle mới; mọi stage khác chạy sau đó
 *     (tới trước lần Ingest tiếp theo) được gộp vào cùng batch — vì Ingest là điểm
 *     khởi đầu tự nhiên của một vòng làm việc.
 *  2. Per-document trail: MỘT hàng / doc / cycle, MỘT cột / stage, lấy từ
 *     PipelineItemLog.  It intentionally does not infer historical ingest state
 *     from RawDoc.parseStatus: that field is current state, not a historical event.
 */
@Controller
public class PipelineHistoryController {

    private final PipelineRunLogRepository runLogs;
    private final PipelineItemLogRepository itemLogs;
    private final RawDocRepository rawDocs;

    public PipelineHistoryController(PipelineRunLogRepository runLogs, PipelineItemLogRepository itemLogs,
                                     RawDocRepository rawDocs) {
        this.runLogs = runLogs;
        this.itemLogs = itemLogs;
        this.rawDocs = rawDocs;
    }

    /** Kept as an alias for templates being migrated from "batch" to "cycle". */
    public record BatchView(int batchId, List<PipelineRunLog> runs) {}

    public record DocTrailRow(int cycleId, Long docId, String title, String sourceCode,
                              String ingestStatus, String classifyStatus,
                              String extractStatus, String interpretStatus, String verifyStatus) {}

    @GetMapping("/pipeline/history")
    public String history(@RequestParam(value = "cycle", required = false) String requestedCycle, Model model) {
        // ---- Cycle view (backed by the durable batchId column) ----
        List<PipelineRunLog> allRuns = runLogs.findAllByOrderByBatchIdDescStartedAtDesc();
        Map<Integer, List<PipelineRunLog>> byBatch = new LinkedHashMap<>();
        for (PipelineRunLog r : allRuns) byBatch.computeIfAbsent(r.getBatchId(), b -> new ArrayList<>()).add(r);
        List<PipelineCycle> cycles = byBatch.entrySet().stream()
                .map(e -> new PipelineCycle(e.getKey(), e.getValue()))
                .toList();
        model.addAttribute("cycles", cycles);
        // Compatibility during the UI migration.  The new screen should use cycles.
        model.addAttribute("batches", cycles.stream().map(c -> new BatchView(c.cycleId(), c.runs())).toList());

        boolean allCycles = "all".equalsIgnoreCase(requestedCycle);
        Integer selectedCycleId = allCycles ? null : parseCycle(requestedCycle);
        if (selectedCycleId == null && !allCycles && !cycles.isEmpty()) selectedCycleId = cycles.get(0).cycleId();
        if (selectedCycleId != null && !byBatch.containsKey(selectedCycleId)) {
            // Invalid/expired URL values must not silently show a different historical cycle.
            selectedCycleId = cycles.isEmpty() ? null : cycles.get(0).cycleId();
        }
        Set<Integer> includedCycles = allCycles ? new LinkedHashSet<>(byBatch.keySet())
                : selectedCycleId == null ? Set.of() : Set.of(selectedCycleId);
        model.addAttribute("selectedCycleId", selectedCycleId);
        model.addAttribute("allCycles", allCycles);
        model.addAttribute("showCycleColumn", allCycles);

        // ---- Per-document trail ----
        Map<Long, Integer> cycleByRunId = new HashMap<>();
        Map<Long, String> stageByRunId = new HashMap<>();
        for (PipelineRunLog run : allRuns) {
            cycleByRunId.put(run.getId(), run.getBatchId());
            stageByRunId.put(run.getId(), run.getStage());
        }
        List<Long> selectedRunIds = allRuns.stream()
                .filter(run -> includedCycles.contains(run.getBatchId()))
                .map(PipelineRunLog::getId).toList();
        List<PipelineItemLog> itemEvents = selectedRunIds.isEmpty() ? List.of()
                : itemLogs.findByRunLogIdInOrderByCreatedAtAsc(selectedRunIds);
        List<PipelineTrailAssembler.Event> events = itemEvents.stream()
                .filter(item -> item.getRawDocId() != null && cycleByRunId.containsKey(item.getRunLogId()))
                .map(item -> new PipelineTrailAssembler.Event(
                        cycleByRunId.get(item.getRunLogId()), item.getRawDocId(),
                        stageByRunId.get(item.getRunLogId()), item.getStatus()))
                .toList();
        Map<PipelineTrailAssembler.DocCycleKey, Map<String, String>> statusByDocCycle =
                PipelineTrailAssembler.statusesByDocumentAndCycle(events, includedCycles);
        Map<Long, RawDoc> docsById = rawDocs.findAllWithSource().stream()
                .collect(java.util.stream.Collectors.toMap(RawDoc::getId, doc -> doc));
        List<DocTrailRow> rows = new ArrayList<>();
        statusByDocCycle.forEach((key, byStage) -> {
            RawDoc doc = docsById.get(key.docId());
            if (doc == null) return; // deleted doc; retain run log but do not render an orphaned row
            rows.add(new DocTrailRow(key.cycleId(), doc.getId(),
                    doc.getTitle() == null ? "(no title)" : doc.getTitle(),
                    doc.getSource().getCode(),
                    PipelineTrailAssembler.statusFor(byStage, "ingest"),
                    PipelineTrailAssembler.statusFor(byStage, "classify"),
                    PipelineTrailAssembler.statusFor(byStage, "extract"),
                    PipelineTrailAssembler.statusFor(byStage, "interpret"),
                    PipelineTrailAssembler.statusFor(byStage, "verify")));
        });
        rows.sort(Comparator.comparing(DocTrailRow::cycleId).reversed()
                .thenComparing(DocTrailRow::docId));
        model.addAttribute("rows", rows);

        return "pipeline-history";
    }

    private static Integer parseCycle(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            int cycle = Integer.parseInt(raw);
            return cycle > 0 ? cycle : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

}
