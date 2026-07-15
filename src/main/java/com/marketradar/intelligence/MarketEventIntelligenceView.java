package com.marketradar.intelligence;

import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.MarketEvent;
import com.marketradar.domain.MarketEventCluster;

import java.time.LocalDate;

/** Flat synthesis read model: fact evidence plus cluster, provenance and temporal safety. */
public record MarketEventIntelligenceView(
        String eventKey,
        String evidenceFactCode,
        EvidenceFact evidenceFact,
        String sourceCode,
        int sourceTier,
        String pipelineVersion,
        String modelVersion,
        String clusterKey,
        int clusterFactCount,
        int clusterDocumentCount,
        int independentSourceCount,
        MarketEventCluster.ProvenanceState provenanceState,
        MarketEventCluster.ConflictState conflictState,
        MarketEvent.EventType eventType,
        String company,
        String productName,
        String geography,
        LocalDate publishedDate,
        LocalDate occurredDate,
        LocalDate effectiveDate,
        LocalDate expiryDate,
        LocalDate forecastHorizon,
        MarketEventTemporalRules.Status temporalStatus,
        boolean futureActionEligible) {

    public static MarketEventIntelligenceView from(MarketEvent event, LocalDate asOf) {
        MarketEventCluster cluster = event.getCluster();
        return new MarketEventIntelligenceView(
                event.getEventKey(), event.getEvidenceFactCode(), event.getEvidenceFact(),
                event.getSourceCode(), event.getSourceTier(), event.getPipelineVersion(),
                event.getModelVersion(),
                cluster == null ? event.getEventKey() : cluster.getClusterKey(),
                cluster == null ? 1 : cluster.getFactCount(),
                cluster == null ? 1 : cluster.getDocumentCount(),
                cluster == null ? 1 : cluster.getIndependentSourceCount(),
                cluster == null ? MarketEventCluster.ProvenanceState.SINGLE_SOURCE
                        : cluster.getProvenanceState(),
                cluster == null ? MarketEventCluster.ConflictState.NONE : cluster.getConflictState(),
                event.getEventType(), event.getCompany(), event.getProductName(), event.getGeography(),
                event.getPublishedDate(), event.getOccurredDate(), event.getEffectiveDate(),
                event.getExpiryDate(), event.getForecastHorizon(),
                MarketEventTemporalRules.status(event, asOf),
                MarketEventTemporalRules.futureActionEligible(event, asOf));
    }
}
