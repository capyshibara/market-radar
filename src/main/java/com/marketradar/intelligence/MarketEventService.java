package com.marketradar.intelligence;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.marketradar.domain.EvidenceFact;
import com.marketradar.repo.EvidenceFactRepository;
import com.marketradar.repo.LlmCallLogRepository;
import com.marketradar.repo.MarketEventRepository;

/** Materializes immutable normalized event editions from the evidence store. */
@Service
public class MarketEventService {

    private final EvidenceFactRepository facts;
    private final MarketEventRepository events;
    private final LlmCallLogRepository llmCalls;
    private final MarketEventNormalizer normalizer;
    private final MarketEventClusterService clusterService;

    public MarketEventService(EvidenceFactRepository facts, MarketEventRepository events,
                              LlmCallLogRepository llmCalls,
                              MarketEventNormalizer normalizer,
                              MarketEventClusterService clusterService) {
        this.facts = facts;
        this.events = events;
        this.llmCalls = llmCalls;
        this.normalizer = normalizer;
        this.clusterService = clusterService;
    }

    /** Idempotent for the current pipeline version; existing editions are never overwritten. */
    @Transactional
    public MaterializationResult materializeMissing() {
        int created = 0;
        int existing = 0;
        for (EvidenceFact fact : facts.findAllActiveOrderById()) {
            if (events.existsByEvidenceFactAndPipelineVersion(
                    fact, MarketEventNormalizer.PIPELINE_VERSION)) {
                existing++;
                continue;
            }
            String modelVersion = extractionModel(fact);
            events.save(normalizer.normalize(fact, modelVersion));
            created++;
        }
        var clusters = clusterService.refresh(MarketEventNormalizer.PIPELINE_VERSION);
        return new MaterializationResult(created, existing,
                MarketEventNormalizer.PIPELINE_VERSION, clusters.clusters(),
                clusters.independentlyCorroborated(), clusters.conflicts());
    }

    private String extractionModel(EvidenceFact fact) {
        if (fact.getRawDoc().isSampleData()) return "MANUAL_SEED";
        if (fact.getExtractionRun() != null) {
            return fact.getExtractionRun().getModelVersion();
        }
        return llmCalls.findFirstByPurposeAndRawDocIdOrderByCreatedAtDesc(
                        "EXTRACT", fact.getRawDoc().getId())
                .map(call -> call.getModel())
                .orElse("UNKNOWN_LEGACY");
    }

    public record MaterializationResult(int created, int alreadyExisting,
                                        String pipelineVersion, int clusters,
                                        int corroboratedClusters, int conflictClusters) {}
}
