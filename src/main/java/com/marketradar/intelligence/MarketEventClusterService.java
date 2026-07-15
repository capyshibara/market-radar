package com.marketradar.intelligence;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.marketradar.domain.MarketEventCluster;
import com.marketradar.repo.MarketEventClusterRepository;
import com.marketradar.repo.MarketEventRepository;

/** Materializes/refreshes deterministic clusters after fact-level normalization. */
@Service
public class MarketEventClusterService {

    private final MarketEventRepository events;
    private final MarketEventClusterRepository clusters;

    public MarketEventClusterService(MarketEventRepository events,
                                     MarketEventClusterRepository clusters) {
        this.events = events;
        this.clusters = clusters;
    }

    @Transactional
    public ClusterResult refresh(String eventPipelineVersion) {
        var drafts = MarketEventClustering.cluster(
                events.findAllForPipelineVersion(eventPipelineVersion));
        int corroborated = 0, conflicts = 0;
        for (var draft : drafts) {
            MarketEventCluster cluster = clusters.findByClusterKey(draft.clusterKey())
                    .orElseGet(() -> new MarketEventCluster(
                            draft.clusterKey(), MarketEventClustering.VERSION));
            cluster.refresh(draft.eventType(), draft.company(), draft.productName(),
                    draft.geography(), draft.anchorDate(), draft.factCount(), draft.documentCount(),
                    draft.independentSourceCount(), draft.provenanceState(), draft.conflictState(),
                    draft.evidenceFactCodes(), draft.sourceCodes());
            cluster = clusters.save(cluster);
            for (var event : draft.members()) event.assignCluster(cluster);
            events.saveAll(draft.members());
            if (draft.provenanceState() == MarketEventCluster.ProvenanceState.INDEPENDENT_SOURCES) {
                corroborated++;
            }
            if (draft.conflictState() != MarketEventCluster.ConflictState.NONE) conflicts++;
        }
        return new ClusterResult(drafts.size(), corroborated, conflicts, MarketEventClustering.VERSION);
    }

    public record ClusterResult(int clusters, int independentlyCorroborated,
                                int conflicts, String clusterVersion) {}
}
