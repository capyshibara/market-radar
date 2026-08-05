package com.marketradar.extract;

import com.marketradar.domain.ResearchCurationBatch;
import com.marketradar.intelligence.MarketEventService;
import com.marketradar.repo.ResearchCurationBatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Isolated persistence boundary for Researcher batch value and deferred-audit evidence. */
@Service
public class ResearchCurationBatchService {

    public enum Recommendation {
        RUN_NEXT_BATCH,
        RUN_DEFERRED_AUDIT,
        AUDIT_FOUND_ADDITIONAL_VALUE,
        OPERATOR_REVIEW_REQUIRED,
        READY_FOR_ANALYST,
        NO_ELIGIBLE_INPUT
    }

    public record Assessment(Recommendation recommendation, String message,
                             ResearchCurationBatch latestAudit) {}

    private final ResearchCurationBatchRepository batches;

    public ResearchCurationBatchService(ResearchCurationBatchRepository batches) {
        this.batches = batches;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long begin(ResearchCurationPlanner.Plan plan,
                      MarketEventService.MaterializationResult before) {
        ResearchCurationBatch row = new ResearchCurationBatch(
                plan.mode() == ResearchCurationPlanner.Mode.MAIN
                        ? ResearchCurationBatch.Mode.MAIN : ResearchCurationBatch.Mode.AUDIT,
                ResearchCurationPlanner.VERSION, plan.candidateSnapshot(),
                plan.selectedDocumentIds().stream().map(String::valueOf)
                        .collect(Collectors.joining(",")),
                plan.selectedClusters().stream().map(ResearchCurationPlanner.StoryCluster::clusterKey)
                        .collect(Collectors.joining(",")),
                plan.diagnostics().candidateClusters(), plan.diagnostics().representedClusters(),
                plan.diagnostics().remainingClusters(), plan.diagnostics().redundantDocuments(),
                before.clusters(), before.corroboratedClusters(), before.conflictClusters());
        return batches.save(row).getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(Long id, int attemptedDocuments, int successfulDocuments, int factsSaved,
                         MarketEventService.MaterializationResult after) {
        ResearchCurationBatch row = batches.findById(id)
                .orElseThrow(() -> new IllegalStateException("Research curation batch not found: " + id));
        row.complete(attemptedDocuments, successfulDocuments, factsSaved,
                after.clusters(), after.corroboratedClusters(), after.conflictClusters());
        batches.save(row);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Long id, String message) {
        if (id == null) return;
        batches.findById(id).ifPresent(row -> {
            row.fail(message);
            batches.save(row);
        });
    }

    @Transactional(readOnly = true)
    public List<ResearchCurationBatch> recent(int limit) {
        return batches.findAllByOrderByStartedAtDescIdDesc().stream()
                .limit(Math.max(1, limit)).toList();
    }

    @Transactional(readOnly = true)
    public Assessment assess(ResearchCurationPlanner.Plan mainPlan,
                             ResearchCurationPlanner.Plan auditPlan) {
        if (mainPlan.diagnostics().candidateClusters() == 0) {
            return new Assessment(Recommendation.NO_ELIGIBLE_INPUT,
                    "No eligible confirmed document is available for Researcher.", null);
        }
        if (mainPlan.diagnostics().remainingClusters() > 0 && !mainPlan.selected().isEmpty()) {
            return new Assessment(Recommendation.RUN_NEXT_BATCH,
                    mainPlan.diagnostics().remainingClusters()
                            + " conservative story cluster(s) still need a representative.", null);
        }
        if (mainPlan.diagnostics().remainingClusters() > 0) {
            if (mainPlan.diagnostics().exhaustedUnrepresentedClusters() == 0) {
                return new Assessment(Recommendation.OPERATOR_REVIEW_REQUIRED,
                        "The emergency document ceiling blocks additional automatic selection. "
                                + "Raise it only after reviewing spend and cluster coverage; the "
                                + "remaining clusters are not classified as low-value.", null);
            }
            return new Assessment(Recommendation.OPERATOR_REVIEW_REQUIRED,
                    mainPlan.diagnostics().exhaustedUnrepresentedClusters()
                            + " unrepresented story cluster(s) have only terminal EMPTY_RESULT/"
                            + "SCHEMA_REJECTED attempts. Inspect their extraction trail or run a "
                            + "targeted retry; they are not treated as represented.", null);
        }
        List<ResearchCurationBatch> currentHistory = batches.findAllByOrderByStartedAtDescIdDesc().stream()
                .filter(row -> Objects.equals(row.getCandidateSnapshot(), mainPlan.candidateSnapshot()))
                .filter(row -> row.getStatus() == ResearchCurationBatch.Status.COMPLETED)
                .toList();
        ResearchCurationBatch latestAudit = currentHistory.stream()
                .filter(row -> row.getMode() == ResearchCurationBatch.Mode.AUDIT)
                .findFirst().orElse(null);
        if (!auditPlan.selected().isEmpty() && latestAudit == null) {
            return new Assessment(Recommendation.RUN_DEFERRED_AUDIT,
                    auditPlan.selected().size()
                            + " deferred article(s) form the next stratified audit sample.", null);
        }
        if (!auditPlan.selected().isEmpty() && latestAudit != null
                && (latestAudit.getNewEventClusters() > 0
                || latestAudit.getNewConflictClusters() > 0)) {
            return new Assessment(Recommendation.AUDIT_FOUND_ADDITIONAL_VALUE,
                    "The latest deferred audit found " + latestAudit.getNewEventClusters()
                            + " new event cluster(s) and " + latestAudit.getNewConflictClusters()
                            + " new conflict cluster(s). Run another audit before hand-off.", latestAudit);
        }
        if (!auditPlan.selected().isEmpty()) {
            return new Assessment(Recommendation.RUN_DEFERRED_AUDIT,
                    "Deferred candidates remain after the last sample; run the next audit sample.", latestAudit);
        }
        return new Assessment(Recommendation.READY_FOR_ANALYST,
                "Every conservative story cluster has a Researcher representative and the deferred "
                        + "audit pool is exhausted for this corpus snapshot.", latestAudit);
    }
}
