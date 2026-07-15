package com.marketradar.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/** Deterministic cluster of fact-level MarketEvents with explicit provenance. */
@Entity
@Table(name = "market_event_clusters", indexes = {
        @Index(name = "idx_market_cluster_key", columnList = "cluster_key", unique = true),
        @Index(name = "idx_market_cluster_provenance", columnList = "provenance_state")
})
public class MarketEventCluster {

    public enum ProvenanceState { SINGLE_SOURCE, INDEPENDENT_SOURCES }
    public enum ConflictState { NONE, DATE_CONFLICT }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cluster_key", nullable = false, unique = true, length = 64)
    private String clusterKey;

    @Column(name = "cluster_version", nullable = false, length = 64)
    private String clusterVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 32)
    private MarketEvent.EventType eventType;

    @Column(length = 256) private String company;
    @Column(name = "product_name", length = 512) private String productName;
    @Column(length = 64) private String geography;
    @Column(name = "anchor_date") private LocalDate anchorDate;
    @Column(name = "fact_count", nullable = false) private int factCount;
    @Column(name = "document_count", nullable = false) private int documentCount;
    @Column(name = "independent_source_count", nullable = false) private int independentSourceCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "provenance_state", nullable = false, length = 32)
    private ProvenanceState provenanceState;

    @Enumerated(EnumType.STRING)
    @Column(name = "conflict_state", nullable = false, length = 32)
    private ConflictState conflictState;

    @Lob @Column(name = "evidence_fact_codes", columnDefinition = "CLOB")
    private String evidenceFactCodes;
    @Lob @Column(name = "source_codes", columnDefinition = "CLOB")
    private String sourceCodes;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    protected MarketEventCluster() {}

    public MarketEventCluster(String clusterKey, String clusterVersion) {
        this.clusterKey = clusterKey;
        this.clusterVersion = clusterVersion;
    }

    public void refresh(MarketEvent.EventType eventType, String company, String productName,
                        String geography, LocalDate anchorDate, int factCount, int documentCount,
                        int independentSourceCount, ProvenanceState provenanceState,
                        ConflictState conflictState, String evidenceFactCodes, String sourceCodes) {
        this.eventType = eventType;
        this.company = company;
        this.productName = productName;
        this.geography = geography;
        this.anchorDate = anchorDate;
        this.factCount = factCount;
        this.documentCount = documentCount;
        this.independentSourceCount = independentSourceCount;
        this.provenanceState = provenanceState;
        this.conflictState = conflictState;
        this.evidenceFactCodes = evidenceFactCodes;
        this.sourceCodes = sourceCodes;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getClusterKey() { return clusterKey; }
    public String getClusterVersion() { return clusterVersion; }
    public MarketEvent.EventType getEventType() { return eventType; }
    public String getCompany() { return company; }
    public String getProductName() { return productName; }
    public String getGeography() { return geography; }
    public LocalDate getAnchorDate() { return anchorDate; }
    public int getFactCount() { return factCount; }
    public int getDocumentCount() { return documentCount; }
    public int getIndependentSourceCount() { return independentSourceCount; }
    public ProvenanceState getProvenanceState() { return provenanceState; }
    public ConflictState getConflictState() { return conflictState; }
    public String getEvidenceFactCodes() { return evidenceFactCodes; }
    public String getSourceCodes() { return sourceCodes; }
    public Instant getUpdatedAt() { return updatedAt; }
}
