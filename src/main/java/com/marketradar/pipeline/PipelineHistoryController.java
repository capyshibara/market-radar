package com.marketradar.pipeline;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import com.marketradar.domain.PipelineItemLog;
import com.marketradar.domain.PipelineRunLog;
import com.marketradar.domain.RawDoc;
import com.marketradar.repo.PipelineItemLogRepository;
import com.marketradar.repo.PipelineRunLogRepository;
import com.marketradar.repo.RawDocRepository;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Batch 10 (feedback Hanh — "hard to see which news is blocked in which stage,
 * no clue what runs are a real batch, 221 claims overwhelming"):
 *
 *  1. Batch view: mỗi lần bấm Ingest mở MỘT batch mới; mọi stage khác chạy sau đó
 *     (tới trước lần Ingest tiếp theo) được gộp vào cùng batch — vì Ingest là điểm
 *     khởi đầu tự nhiên của một vòng làm việc.
 *  2. Per-document trail: MỘT hàng / doc, MỘT cột / stage, lấy từ PipelineItemLog
 *     (durable — khác StringBuilder tạm trước đây biến mất sau lần chạy kế/restart).
 *     Cột Ingest lấy trực tiếp từ RawDoc.parseStatus (ingest log ở cấp SOURCE,
 *     không cấp doc — doc tồn tại tức là ingest OK, không cần bảng riêng).
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

    public record BatchView(int batchId, List<PipelineRunLog> runs) {}

    public record DocTrailRow(Long docId, String title, String sourceCode,
                              String ingestStatus, String classifyStatus,
                              String extractStatus, String interpretStatus, String verifyStatus) {}

    @GetMapping("/pipeline/history")
    public String history(Model model) {
        // ---- Batch view ----
        List<PipelineRunLog> allRuns = runLogs.findAllByOrderByBatchIdDescStartedAtDesc();
        Map<Integer, List<PipelineRunLog>> byBatch = new LinkedHashMap<>();
        for (PipelineRunLog r : allRuns) byBatch.computeIfAbsent(r.getBatchId(), b -> new ArrayList<>()).add(r);
        List<BatchView> batches = byBatch.entrySet().stream()
                .map(e -> new BatchView(e.getKey(), e.getValue()))
                .toList();
        model.addAttribute("batches", batches);

        // ---- Per-document trail ----
        Map<Long, PipelineRunLog> runById = allRuns.stream()
                .collect(Collectors.toMap(PipelineRunLog::getId, r -> r));

        List<RawDoc> docs = rawDocs.findAllWithSource();
        List<DocTrailRow> rows = new ArrayList<>();
        for (RawDoc doc : docs) {
            List<PipelineItemLog> items = itemLogs.findByRawDocIdOrderByCreatedAtAsc(doc.getId());
            Map<String, List<String>> byStage = new LinkedHashMap<>();
            for (PipelineItemLog it : items) {
                PipelineRunLog run = runById.get(it.getRunLogId());
                if (run == null) continue;
                byStage.computeIfAbsent(run.getStage(), s -> new ArrayList<>()).add(it.getStatus());
            }
            rows.add(new DocTrailRow(doc.getId(),
                    doc.getTitle() == null ? "(no title)" : doc.getTitle(),
                    doc.getSource().getCode(),
                    doc.getParseStatus().name(),
                    summarize(byStage.get("classify")),
                    summarize(byStage.get("extract")),
                    summarize(byStage.get("interpret")),
                    summarize(byStage.get("verify"))));
        }
        model.addAttribute("rows", rows);

        return "pipeline-history";
    }

    /** Nhiều item log cùng stage/doc (vd verify có nhiều claim/doc) → gộp thành danh sách
     * trạng thái duy nhất, dễ quét (vd "ENTAILED, CONTRADICTED") thay vì liệt kê hết. */
    private static String summarize(List<String> statuses) {
        if (statuses == null || statuses.isEmpty()) return "—";
        return statuses.stream().distinct().sorted().collect(Collectors.joining(", "));
    }
}
