package com.marketradar.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.marketradar.classify.Router;
import com.marketradar.classify.TopicClassifier;
import com.marketradar.classify.ClassificationPersistenceService;
import com.marketradar.classify.ClassificationInputPolicy;
import com.marketradar.classify.ClassificationVersioning;
import com.marketradar.dedup.DedupJob;
import com.marketradar.domain.Classification;
import com.marketradar.domain.ClassificationAttempt;
import com.marketradar.domain.PipelineItemLog;
import com.marketradar.domain.RawDoc;
import com.marketradar.repo.ClassificationRepository;
import com.marketradar.repo.ClassificationAttemptRepository;
import com.marketradar.repo.PipelineItemLogRepository;
import com.marketradar.repo.RawDocRepository;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Bước 4 pipeline: phân loại (AI#1) + routing (bảng tra) cho các RawDoc
 * đã ingest OK. Kết quả hiện tại được bỏ qua; kết quả legacy hoặc sai version
 * được chạy lại theo provider/model + prompt hash + content hash.
 *
 * Dedup LUÔN chạy trước classify trong cùng lần gọi (không phụ thuộc thứ tự
 * curl tay) — RawDoc đã bị đánh dấu duplicateOfId (bản thua) thì KHÔNG tốn
 * LLM call phân loại, vì báo cáo cuối cùng lọc bản trùng ra rồi (ReportController).
 */
@Service
public class ClassificationJob {

    private static final Logger log = LoggerFactory.getLogger(ClassificationJob.class);

    private final RawDocRepository rawDocs;
    private final ClassificationRepository classifications;
    private final ClassificationAttemptRepository attempts;
    private final TopicClassifier classifier;
    private final ClassificationPersistenceService persistence;
    private final Router router;
    private final DedupJob dedupJob;
    private final PipelineRunStatusService progress;
    private final PipelineItemLogRepository itemLogs;

    public ClassificationJob(RawDocRepository rawDocs, ClassificationRepository classifications,
                             ClassificationAttemptRepository attempts,
                             TopicClassifier classifier,
                             ClassificationPersistenceService persistence,
                             Router router, DedupJob dedupJob,
                             PipelineRunStatusService progress, PipelineItemLogRepository itemLogs) {
        this.rawDocs = rawDocs;
        this.classifications = classifications;
        this.attempts = attempts;
        this.classifier = classifier;
        this.persistence = persistence;
        this.router = router;
        this.dedupJob = dedupJob;
        this.progress = progress;
        this.itemLogs = itemLogs;
    }

    /**
     * KHÔNG @Transactional (bỏ 2026-07-13): trước đây cả vòng chạy là MỘT transaction —
     * classify DeepSeek giờ cao điểm mất hàng giờ, suốt thời gian đó không thấy tiến độ,
     * và Ctrl+C/crash mất TOÀN BỘ kết quả + LlmCallLog (mất luôn replay-cache đã trả tiền).
     * Giờ mỗi doc tự commit (save từng entity = transaction riêng): tiến độ nhìn được
     * ngay ở /classifications; mỗi doc được commit cùng attempt ledger riêng.
     * DedupJob giữ @Transactional riêng của nó (gọi qua proxy — nhanh, ~10s).
     */
    public String runOnce() {
        return execute(false);
    }

    /** Explicit operator retry hook; normal runs hold a version that already failed. */
    public String runOnceRetryFailedVersion() {
        return execute(true);
    }

    /** Safe single-document retry: no deletes, no dedup mutation, and no replay-cache read. */
    public String retryOne(Long rawDocId) {
        classifier.requireConfiguredProvider();
        RawDoc doc = rawDocs.findById(rawDocId)
                .orElseThrow(() -> new IllegalArgumentException("raw doc not found: " + rawDocId));
        if (doc.getParseStatus() != RawDoc.ParseStatus.OK) {
            return "Retry refused for doc#" + rawDocId + ": parse status is " + doc.getParseStatus();
        }
        if (doc.getDuplicateOfId() != null) {
            return "Retry refused for doc#" + rawDocId + ": duplicate of doc#" + doc.getDuplicateOfId();
        }
        ClassificationInputPolicy.Assessment input = ClassificationInputPolicy.assess(doc);
        if (!input.eligible()) {
            return "Retry refused for doc#" + rawDocId + ": classification input "
                    + input.decision() + " (" + input.inputCharacters() + " characters)";
        }
        Classification active = classifications.findByRawDoc(doc).orElse(null);
        ClassificationVersioning.CurrentVersion attemptedVersion = classifier.currentVersion(doc);
        Classification candidate = null;
        try {
            TopicClassifier.VersionedClassification result = classifier.retryVersioned(doc);
            candidate = result.classification();
            attemptedVersion = result.version();
            router.route(candidate);
            ClassificationAttempt.Outcome outcome = persistence.apply(doc,
                    active == null ? null : active.getId(),
                    active == null ? null : active.getClassifierVersionSignature(),
                    candidate, attemptedVersion);
            return "doc#" + rawDocId + ": " + outcome + " — " + candidate.getStatus()
                    + " " + candidate.getLabels() + " → " + candidate.getRoutingStatus();
        } catch (RuntimeException e) {
            persistence.recordError(doc, attemptedVersion, candidate, e.getMessage());
            throw e;
        }
    }

    private String execute(boolean retryFailed) {
        // Must happen before dedup/progress/ledger writes. /classify/run invokes this
        // service directly and does not pass through PipelineRunnerController's guard.
        classifier.requireConfiguredProvider();
        String dedupSummary = dedupJob.runOnce();

        List<RawDoc> all = rawDocs.findAll();
        Map<Long, Classification> activeByDoc = activeByDoc();
        Map<Long, PlannedDoc> plans = new LinkedHashMap<>();
        for (RawDoc doc : all) {
            if (doc.getParseStatus() == RawDoc.ParseStatus.OK
                    && doc.getDuplicateOfId() == null
                    && ClassificationInputPolicy.assess(doc).eligible()) {
                plans.put(doc.getId(), plan(doc, activeByDoc.get(doc.getId()), retryFailed));
            }
        }
        long actionable = plans.values().stream().filter(PlannedDoc::actionable).count();
        progress.startProgress("classify", (int) actionable);
        Long runLogId = progress.currentRunLogId("classify");

        int done = 0, errors = 0, preserved = 0;
        int skippedParse = 0, skippedDuplicate = 0, skippedCurrent = 0, heldFailed = 0;
        Map<ClassificationInputPolicy.Decision, Integer> contentSkipped =
                new EnumMap<>(ClassificationInputPolicy.Decision.class);
        StringBuilder summary = new StringBuilder();
        for (RawDoc doc : all) {
            if (doc.getParseStatus() != RawDoc.ParseStatus.OK) { skippedParse++; continue; }
            if (doc.getDuplicateOfId() != null) { skippedDuplicate++; continue; }
            ClassificationInputPolicy.Assessment input = ClassificationInputPolicy.assess(doc);
            if (!input.eligible()) {
                contentSkipped.merge(input.decision(), 1, Integer::sum);
                continue;
            }
            PlannedDoc planned = plans.get(doc.getId());
            if (planned.action() == ClassificationVersioning.PlanAction.SKIP_CURRENT) {
                skippedCurrent++;
                continue;
            }
            if (planned.action() == ClassificationVersioning.PlanAction.HOLD_FAILED_VERSION) {
                heldFailed++;
                continue;
            }
            Classification candidate = null;
            ClassificationVersioning.CurrentVersion attemptedVersion = planned.version();
            try {
                TopicClassifier.VersionedClassification result = classifier.classifyVersioned(doc);
                candidate = result.classification();
                attemptedVersion = result.version();
                router.route(candidate);
                ClassificationAttempt.Outcome outcome = persistence.apply(doc,
                        planned.active() == null ? null : planned.active().getId(),
                        planned.active() == null ? null
                                : planned.active().getClassifierVersionSignature(),
                        candidate, attemptedVersion);
                if (outcome == ClassificationAttempt.Outcome.APPLIED_NEW
                        || outcome == ClassificationAttempt.Outcome.APPLIED_REPLACEMENT) {
                    done++;
                } else {
                    preserved++;
                }
                summary.append("doc#").append(doc.getId()).append(": ")
                       .append(outcome).append(" — ")
                       .append(candidate.getStatus()).append(' ').append(candidate.getLabels())
                       .append(" → ").append(candidate.getRoutingStatus())
                       .append(' ').append(candidate.getDepartments()).append('\n');
                logItem(runLogId, doc, outcome.name(),
                        candidate.getStatus() + " " + candidate.getLabels()
                                + " → " + candidate.getRoutingStatus());
            } catch (Exception e) {
                errors++;
                log.error("Classify lỗi doc#{}", doc.getId(), e);
                try {
                    persistence.recordError(doc, attemptedVersion, candidate, e.getMessage());
                } catch (Exception ledgerError) {
                    log.error("Không ghi được classification attempt ledger cho doc#{}",
                            doc.getId(), ledgerError);
                }
                summary.append("doc#").append(doc.getId()).append(": ERROR — ")
                       .append(e.getMessage()).append('\n');
                logItem(runLogId, doc, "ERROR", e.getMessage());
            }
            progress.stepProgress("classify");
        }
        summary.insert(0, "Classification versions: applied " + done
                + ", preserved prior " + preserved + ", errors " + errors
                + ", current " + skippedCurrent + ", held failed version " + heldFailed
                + ", parse-skip " + skippedParse + ", duplicate-skip " + skippedDuplicate
                + ", content-skip " + contentSkipTotal(contentSkipped)
                + " " + contentSkipSummary(contentSkipped)
                + " (duplicate — filtered by dedup before costing an LLM call)\n"
                + "--- Dedup (runs before classify) ---\n" + dedupSummary + "---\n");
        return summary.toString();
    }

    /**
     * Read-only diagnostics: no dedup mutation and no LLM call. The returned plan
     * makes legacy/stale/current/held decisions inspectable before an expensive run.
     */
    public String dryRunPlan() {
        List<RawDoc> all = rawDocs.findAll();
        Map<Long, Classification> activeByDoc = activeByDoc();
        Map<ClassificationVersioning.PlanAction, Integer> counts =
                new EnumMap<>(ClassificationVersioning.PlanAction.class);
        StringBuilder details = new StringBuilder();
        int parseSkipped = 0, duplicateSkipped = 0;
        Map<ClassificationInputPolicy.Decision, Integer> contentSkipped =
                new EnumMap<>(ClassificationInputPolicy.Decision.class);

        for (RawDoc doc : all) {
            if (doc.getParseStatus() != RawDoc.ParseStatus.OK) { parseSkipped++; continue; }
            if (doc.getDuplicateOfId() != null) { duplicateSkipped++; continue; }
            ClassificationInputPolicy.Assessment input = ClassificationInputPolicy.assess(doc);
            if (!input.eligible()) {
                contentSkipped.merge(input.decision(), 1, Integer::sum);
                continue;
            }
            Classification active = activeByDoc.get(doc.getId());
            PlannedDoc planned = plan(doc, active, false);
            counts.merge(planned.action(), 1, Integer::sum);
            if (planned.action() != ClassificationVersioning.PlanAction.SKIP_CURRENT) {
                details.append("doc#").append(doc.getId()).append(": ")
                        .append(planned.action()).append(" — ")
                        .append(reason(active, planned)).append('\n');
            }
        }

        return "Classification dry-run (read-only; no LLM calls)\n"
                + "CLASSIFY_NEW=" + counts.getOrDefault(
                        ClassificationVersioning.PlanAction.CLASSIFY_NEW, 0)
                + ", RECLASSIFY_STALE=" + counts.getOrDefault(
                        ClassificationVersioning.PlanAction.RECLASSIFY_STALE, 0)
                + ", SKIP_CURRENT=" + counts.getOrDefault(
                        ClassificationVersioning.PlanAction.SKIP_CURRENT, 0)
                + ", HOLD_FAILED_VERSION=" + counts.getOrDefault(
                        ClassificationVersioning.PlanAction.HOLD_FAILED_VERSION, 0)
                + ", PARSE_SKIP=" + parseSkipped
                + ", DUPLICATE_SKIP=" + duplicateSkipped
                + ", CONTENT_SKIP=" + contentSkipTotal(contentSkipped)
                + " " + contentSkipSummary(contentSkipped) + "\n"
                + details;
    }

    private static int contentSkipTotal(
            Map<ClassificationInputPolicy.Decision, Integer> counts) {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static String contentSkipSummary(
            Map<ClassificationInputPolicy.Decision, Integer> counts) {
        return "(sample=" + counts.getOrDefault(ClassificationInputPolicy.Decision.SAMPLE_DATA, 0)
                + ", empty=" + counts.getOrDefault(ClassificationInputPolicy.Decision.EMPTY_TEXT, 0)
                + ", needs-full-text=" + counts.getOrDefault(
                        ClassificationInputPolicy.Decision.NEEDS_FULL_TEXT, 0)
                + ", short=" + counts.getOrDefault(
                        ClassificationInputPolicy.Decision.SHORT_TEXT, 0) + ")";
    }

    private PlannedDoc plan(RawDoc doc, Classification active, boolean retryFailed) {
        ClassificationVersioning.CurrentVersion desired = classifier.currentVersion(doc);
        boolean activeMatches = active != null && ClassificationVersioning.matches(
                active.getLlmProvider(), active.getClassifierPromptSha256(),
                active.getClassifierContentSha256(), active.getClassifierVersionSignature(), desired);
        boolean failedDesiredVersion = attempts
                .findFirstByRawDocAndVersionSignatureOrderByCreatedAtDescIdDesc(
                        doc, desired.signature())
                .map(a -> ClassificationVersioning.isFailureOutcome(a.getOutcome().name()))
                .orElse(false);
        ClassificationVersioning.PlanAction action = ClassificationVersioning.plan(
                active != null, activeMatches, failedDesiredVersion, retryFailed);
        return new PlannedDoc(doc, active, desired, action, failedDesiredVersion);
    }

    private Map<Long, Classification> activeByDoc() {
        return classifications.findAll().stream().collect(Collectors.toMap(
                c -> c.getRawDoc().getId(), Function.identity(), (left, right) -> left));
    }

    private String reason(Classification active, PlannedDoc planned) {
        if (planned.action() == ClassificationVersioning.PlanAction.CLASSIFY_NEW) {
            return "no active classification";
        }
        if (planned.action() == ClassificationVersioning.PlanAction.HOLD_FAILED_VERSION) {
            return "this provider/prompt/content version already failed; prior result retained";
        }
        if (active != null && active.getClassifierVersionSignature() == null) {
            return "legacy classification has no version metadata";
        }
        return "provider/model, effective prompt hash, or content hash changed";
    }

    private record PlannedDoc(RawDoc doc, Classification active,
                              ClassificationVersioning.CurrentVersion version,
                              ClassificationVersioning.PlanAction action,
                              boolean failedDesiredVersion) {
        boolean actionable() {
            return action == ClassificationVersioning.PlanAction.CLASSIFY_NEW
                    || action == ClassificationVersioning.PlanAction.RECLASSIFY_STALE;
        }
    }

    private void logItem(Long runLogId, RawDoc doc, String status, String message) {
        if (runLogId == null) return;
        itemLogs.save(new PipelineItemLog(runLogId, PipelineItemLog.ItemType.RAW_DOC,
                String.valueOf(doc.getId()), doc.getTitle(), doc.getId(), status, message));
    }
}
