package com.marketradar.pipeline;

import com.marketradar.llm.SwitchableLlmClient;
import com.marketradar.extract.ExtractionContentDiagnostics;

import java.util.ArrayList;
import java.util.List;

/** Pure rules for deciding whether a costly corpus reprocessing run may start. */
public final class ReprocessPreflightRules {

    private ReprocessPreflightRules() {}

    public record Input(
            SwitchableLlmClient.Kind classifierKind,
            SwitchableLlmClient.Kind writerKind,
            SwitchableLlmClient.Kind verifierKind,
            boolean pipelineRunning,
            boolean backupConfirmed,
            long documentCount,
            long incompleteDocumentCount,
            long titleOnlyCount,
            long shortFullTextCount,
            long parseFailureCount,
            long evidenceFactCount,
            long pendingReviewCount,
            ExtractionContentDiagnostics.LengthStats contentLengths) {}

    public enum Severity { BLOCKER, WARNING, INFO }

    public record Check(String code, Severity severity, boolean passed, String message) {}

    public record Report(boolean ready, List<Check> checks, List<String> orderedStages) {
        public Report {
            checks = List.copyOf(checks);
            orderedStages = List.copyOf(orderedStages);
        }
    }

    public static Report evaluate(Input in) {
        List<Check> checks = new ArrayList<>();
        checks.add(blocker("PIPELINE_IDLE", !in.pipelineRunning(),
                in.pipelineRunning() ? "Another pipeline stage is running." : "No pipeline stage is running."));
        checks.add(blocker("BACKUP_CONFIRMED", in.backupConfirmed(),
                in.backupConfirmed() ? "Operator confirmed a database backup." : "Create and verify a database backup first."));
        checks.add(blocker("CLASSIFIER_CONFIGURED", configured(in.classifierKind()),
                configured(in.classifierKind()) ? "Classifier is configured." : "Classifier is in STUB mode."));
        checks.add(blocker("WRITER_CONFIGURED", configured(in.writerKind()),
                configured(in.writerKind()) ? "Writer is configured." : "Writer/extractor is in STUB mode."));
        checks.add(blocker("VERIFIER_CONFIGURED", configured(in.verifierKind()),
                configured(in.verifierKind()) ? "Verifier is configured." : "Verifier is in STUB mode."));

        long incomplete = in.incompleteDocumentCount();
        checks.add(warning("CONTENT_BACKFILL", incomplete == 0,
                incomplete == 0 ? "No incomplete documents detected."
                        : incomplete + " unique documents need targeted refetch or exclusion (title-only="
                        + in.titleOnlyCount() + ", short=" + in.shortFullTextCount()
                        + ", parse-failed=" + in.parseFailureCount() + ")."));
        checks.add(warning("PENDING_REVIEW", in.pendingReviewCount() == 0,
                in.pendingReviewCount() == 0 ? "No claims are awaiting review."
                        : in.pendingReviewCount() + " legacy claims await review; they must not enter new editions."));
        checks.add(new Check("CORPUS_COUNTS", Severity.INFO, true,
                "documents=" + in.documentCount() + ", evidenceFacts=" + in.evidenceFactCount()));
        ExtractionContentDiagnostics.LengthStats lengths = in.contentLengths();
        if (lengths != null) {
            checks.add(new Check("CONTENT_LENGTH_DEPTH", Severity.INFO, true,
                    "fullText=" + lengths.fullTextDocuments()
                            + ", medianChars=" + lengths.medianChars()
                            + ", p90Chars=" + lengths.p90Chars()
                            + ", averageChars=" + Math.round(lengths.averageChars())
                            + ", maxChars=" + lengths.maxChars()
                            + ", multiChunkDocuments=" + lengths.inputTruncatedDocuments()));
        }

        boolean ready = checks.stream()
                .filter(c -> c.severity() == Severity.BLOCKER)
                .allMatch(Check::passed);
        List<String> stages = incomplete > 0
                ? List.of("targeted-refetch", "classify-stale", "extract-stale", "normalize-events",
                "evaluate-golden-set", "regenerate-product-7-30-90", "publication-gate",
                "product-sme-review")
                : List.of("classify-stale", "extract-stale", "normalize-events", "evaluate-golden-set",
                "regenerate-product-7-30-90", "publication-gate", "product-sme-review");
        return new Report(ready, checks, stages);
    }

    private static boolean configured(SwitchableLlmClient.Kind kind) {
        return kind != null && kind != SwitchableLlmClient.Kind.STUB
                && kind != SwitchableLlmClient.Kind.STUB_VERIFIER;
    }

    private static Check blocker(String code, boolean passed, String message) {
        return new Check(code, Severity.BLOCKER, passed, message);
    }

    private static Check warning(String code, boolean passed, String message) {
        return new Check(code, Severity.WARNING, passed, message);
    }
}
