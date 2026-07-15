package com.marketradar.extract;

/** Auditable funnel counters for one document extraction attempt. */
public record ExtractionRunMetrics(
        int inputChars,
        int chunksPlanned,
        int chunksCompleted,
        int factsProposed,
        int spansRejected,
        int duplicateSpansCollapsed,
        String rejectionSummary) {

    public ExtractionRunMetrics {
        rejectionSummary = rejectionSummary == null ? "" : rejectionSummary;
    }

    public static ExtractionRunMetrics planned(LongDocumentChunker.Plan plan) {
        return new ExtractionRunMetrics(plan.inputChars(), plan.chunkCount(), 0,
                0, 0, 0, "");
    }
}
