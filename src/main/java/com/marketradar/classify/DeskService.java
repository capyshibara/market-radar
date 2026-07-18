package com.marketradar.classify;

import com.marketradar.domain.Classification;
import com.marketradar.domain.Department;
import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.PipelineRunLog;
import com.marketradar.product.ProductBriefEditionRepository;
import com.marketradar.product.ProductReportCadence;
import com.marketradar.repo.ClassificationRepository;
import com.marketradar.repo.EvidenceFactRepository;
import com.marketradar.repo.PipelineRunLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Read-only desk view over persisted routing decisions. The desk shows exactly what
 * {@link Router} wrote (departments + audit note) — it never re-routes or re-labels.
 *
 * <p>Stories are grouped into the pipeline batch that produced their routing decision.
 * {@code Router.route()} runs synchronously inside the "classify" stage job, so a
 * classification's {@code createdAt} always falls after that job's own {@code startedAt}
 * and before the next classify job starts — the same fact the Run History page relies on
 * to group runs into cycles. There is no batchId column on Classification/RawDoc; this
 * resolves the causal batch from that ordering instead of adding one.</p>
 */
@Service
public class DeskService {
    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(REPORT_ZONE);
    private static final DateTimeFormatter DATETIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(REPORT_ZONE);
    /** No classify run has ever started before this classification — seeded/legacy data. */
    private static final int UNASSIGNED_BATCH = 0;

    private final ClassificationRepository classifications;
    private final EvidenceFactRepository facts;
    private final PipelineRunLogRepository runLogs;
    private final ProductBriefEditionRepository editions;

    public DeskService(ClassificationRepository classifications, EvidenceFactRepository facts,
                       PipelineRunLogRepository runLogs, ProductBriefEditionRepository editions) {
        this.classifications = classifications;
        this.facts = facts;
        this.runLogs = runLogs;
        this.editions = editions;
    }

    @Transactional(readOnly = true)
    public List<DeskSummary> overview() {
        Instant weekAgo = Instant.now().minus(Duration.ofDays(7));
        return java.util.Arrays.stream(Department.values()).map(dept -> {
            List<Classification> routed = classifications.findRoutedByDepartment(dept);
            long fresh = routed.stream().filter(c -> c.getCreatedAt().isAfter(weekAgo)).count();
            long reportCount = editions.findByDepartmentOrderByCreatedAtDesc(dept).size();
            return new DeskSummary(dept, routed.size(), fresh, reportCount,
                    toItems(routed.stream().limit(3).toList()));
        }).toList();
    }

    @Transactional(readOnly = true)
    public DeskFeed deskFeed(Department dept) {
        List<Classification> routed = classifications.findRoutedByDepartment(dept);
        List<PipelineRunLog> classifyRunsAsc = runLogs.findAllByOrderByBatchIdDescStartedAtDesc()
                .stream().filter(r -> "classify".equals(r.getStage()))
                .sorted(Comparator.comparing(PipelineRunLog::getStartedAt))
                .toList();
        Map<Integer, PipelineRunLog> runByBatch = new HashMap<>();
        classifyRunsAsc.forEach(r -> runByBatch.putIfAbsent(r.getBatchId(), r));

        Map<Integer, List<Classification>> byBatch = new TreeMap<>(Comparator.reverseOrder());
        for (Classification c : routed) {
            byBatch.computeIfAbsent(resolveBatch(classifyRunsAsc, c.getCreatedAt()),
                    k -> new ArrayList<>()).add(c);
        }

        List<DeskBatch> batches = byBatch.entrySet().stream().map(e -> {
            int batchId = e.getKey();
            PipelineRunLog run = runByBatch.get(batchId);
            return new DeskBatch(batchId, batchId != UNASSIGNED_BATCH,
                    run == null ? null : DATETIME.format(run.getStartedAt()),
                    run == null ? "UNTRACKED" : run.getState(),
                    toItems(e.getValue()));
        }).toList();

        return new DeskFeed(reportBatches(dept, classifyRunsAsc, runByBatch), batches);
    }

    /**
     * All persisted report editions for this desk, newest first, grouped by the pipeline batch
     * whose data they were built from (resolved causally from createdAt, same as stories —
     * editions carry no batchId column either). The newest edition per cadence is flagged
     * {@code current}; older ones render as superseded history. Only Product has a running
     * writer today, so other desks return an empty list and the template shows an honest
     * empty state instead of a fake report.
     */
    private List<ReportBatch> reportBatches(Department dept, List<PipelineRunLog> classifyRunsAsc,
                                            Map<Integer, PipelineRunLog> runByBatch) {
        List<com.marketradar.product.ProductBriefEdition> all =
                editions.findByDepartmentOrderByCreatedAtDesc(dept);
        if (all.isEmpty()) return List.of();

        Map<Integer, Boolean> currentSeen = new HashMap<>();
        Map<Integer, List<ReportRow>> byBatch = new TreeMap<>(Comparator.reverseOrder());
        for (var ed : all) {
            int days = (int) java.time.temporal.ChronoUnit.DAYS.between(
                    ed.getWindowStart(), ed.getWindowEnd()) + 1;
            ProductReportCadence cadence = cadenceForDays(days);
            boolean current = cadence != null && currentSeen.putIfAbsent(days, Boolean.TRUE) == null;
            String readerUrl = cadence == null ? "/report/product"
                    : "/report/product?cadence=" + cadence.name().toLowerCase(java.util.Locale.ROOT);
            int batch = resolveBatch(classifyRunsAsc, ed.getCreatedAt());
            byBatch.computeIfAbsent(batch, k -> new ArrayList<>()).add(new ReportRow(
                    ed.getEditionCode(), cadence, days, current,
                    DATE.format(ed.getWindowStart()) + " – " + DATE.format(ed.getWindowEnd()),
                    ed.getStatus().name(), ed.getInsightCount(),
                    DATETIME.format(ed.getCreatedAt()), readerUrl));
        }
        return byBatch.entrySet().stream().map(e -> {
            PipelineRunLog run = runByBatch.get(e.getKey());
            return new ReportBatch(e.getKey(), e.getKey() != UNASSIGNED_BATCH,
                    run == null ? null : DATETIME.format(run.getStartedAt()),
                    run == null ? "UNTRACKED" : run.getState(), e.getValue());
        }).toList();
    }

    private static ProductReportCadence cadenceForDays(int days) {
        for (ProductReportCadence c : ProductReportCadence.values()) {
            if (c.days() == days) return c;
        }
        return null;
    }

    /** Last classify run whose startedAt is at or before createdAt; 0 if none (pre-tracking data). */
    private static int resolveBatch(List<PipelineRunLog> classifyRunsAsc, Instant createdAt) {
        int resolved = UNASSIGNED_BATCH;
        for (PipelineRunLog run : classifyRunsAsc) {
            if (!run.getStartedAt().isAfter(createdAt)) {
                resolved = run.getBatchId();
            } else {
                break;
            }
        }
        return resolved;
    }

    private List<DeskItem> toItems(List<Classification> routed) {
        List<Long> docIds = routed.stream().map(c -> c.getRawDoc().getId()).toList();
        Map<Long, String> firstFactByDoc = new HashMap<>();
        if (!docIds.isEmpty()) {
            for (EvidenceFact fact : facts.findActiveByRawDocIdIn(docIds)) {
                firstFactByDoc.putIfAbsent(fact.getRawDoc().getId(), fact.getFactCode());
            }
        }
        return routed.stream().map(c -> {
            var doc = c.getRawDoc();
            String url = doc.getUrl() == null ? "" : doc.getUrl();
            return new DeskItem(doc.getId(),
                    doc.getTitle() == null || doc.getTitle().isBlank() ? "Document #" + doc.getId() : doc.getTitle(),
                    url, url.startsWith("https://") || url.startsWith("http://"),
                    doc.getPublisherName() == null || doc.getPublisherName().isBlank()
                            ? doc.getSource().getName() : doc.getPublisherName(),
                    doc.getPublishedAt() == null ? "—" : DATE.format(doc.getPublishedAt()),
                    c.getLabels().stream().map(Enum::name).sorted().toList(),
                    c.getDepartments().stream().map(Enum::name).sorted().toList(),
                    c.getNote() == null ? "" : c.getNote(),
                    firstFactByDoc.get(doc.getId()));
        }).toList();
    }

    public record DeskSummary(Department dept, long total, long newThisWeek, long reportCount,
                              List<DeskItem> recent) {}

    public record DeskFeed(List<ReportBatch> reportBatches, List<DeskBatch> batches) {}

    public record ReportBatch(int batchId, boolean tracked, String startedLabel, String runState,
                              List<ReportRow> reports) {}

    public record ReportRow(String editionCode, ProductReportCadence cadence, int windowDays,
                            boolean current, String windowLabel, String status, int insightCount,
                            String createdLabel, String readerUrl) {}

    public record DeskBatch(int batchId, boolean tracked, String startedLabel, String runState,
                            List<DeskItem> items) {}

    public record DeskItem(long docId, String title, String url, boolean externalLink,
                           String sourceName, String publishedLabel, List<String> labels,
                           List<String> departments, String routingNote, String factCode) {}
}
