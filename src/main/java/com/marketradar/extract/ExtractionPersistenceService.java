package com.marketradar.extract;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.FactExtractionRun;
import com.marketradar.domain.RawDoc;
import com.marketradar.repo.EvidenceFactRepository;
import com.marketradar.repo.FactExtractionRunRepository;

import java.time.Instant;
import java.util.List;

/** Keeps each document's edition switch atomic without a transaction around LLM calls. */
@Service
public class ExtractionPersistenceService {

    private final FactExtractionRunRepository runs;
    private final EvidenceFactRepository facts;

    public ExtractionPersistenceService(FactExtractionRunRepository runs,
                                        EvidenceFactRepository facts) {
        this.runs = runs;
        this.facts = facts;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FactExtractionRun begin(RawDoc doc, ExtractionVersioning.CurrentVersion version,
                                   String signature, LongDocumentChunker.Plan plan) {
        FactExtractionRun run = new FactExtractionRun(doc, version.pipelineVersion(),
                version.modelVersion(), version.promptSha256(), doc.getContentHash(), signature);
        applyMetrics(run, ExtractionRunMetrics.planned(plan));
        return runs.save(run);
    }

    /** Save the new edition and supersede its predecessors as one commit. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int succeed(Long runId, List<EvidenceFact> accepted, int rejected) {
        return succeed(runId, accepted, new ExtractionRunMetrics(0, 1, 1,
                accepted == null ? 0 : accepted.size(), rejected, 0, ""));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int succeed(Long runId, List<EvidenceFact> accepted, ExtractionRunMetrics metrics) {
        if (accepted == null || accepted.isEmpty()) {
            throw new IllegalArgumentException(
                    "an empty extraction cannot activate or supersede an evidence edition");
        }
        FactExtractionRun run = runs.findById(runId).orElseThrow();
        applyMetrics(run, metrics);
        for (EvidenceFact fact : accepted) fact.extractionRun(run);
        facts.saveAll(accepted);
        facts.flush();
        Instant switchedAt = Instant.now();
        runs.retirePriorCurrentEdition(run.getRawDoc().getId(), run.getId(), switchedAt);
        int superseded = facts.supersedeOtherActiveFacts(
                run.getRawDoc().getId(), run.getId(), switchedAt);
        run.succeed(accepted.size(), metrics.spansRejected());
        runs.save(run);
        return superseded;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Long runId, FactExtractionRun.Status status, String message) {
        fail(runId, status, message, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Long runId, FactExtractionRun.Status status, String message,
                     ExtractionRunMetrics metrics) {
        FactExtractionRun run = runs.findById(runId).orElseThrow();
        if (metrics != null) applyMetrics(run, metrics);
        run.fail(status, message);
        runs.save(run);
    }

    private static void applyMetrics(FactExtractionRun run, ExtractionRunMetrics metrics) {
        run.applyMetrics(metrics.inputChars(), metrics.chunksPlanned(), metrics.chunksCompleted(),
                metrics.factsProposed(), metrics.spansRejected(),
                metrics.duplicateSpansCollapsed(), metrics.rejectionSummary());
    }
}
