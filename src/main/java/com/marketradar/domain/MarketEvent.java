package com.marketradar.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A normalized, synthesis-ready view of one evidence fact.
 *
 * <p>The row deliberately snapshots the dimensions needed for clustering. The
 * {@link #evidenceFact} relationship remains the authoritative link back to the
 * verbatim evidence. A new normalizer version creates a new edition rather than
 * silently overwriting the old interpretation.</p>
 */
@Entity
@Table(name = "market_events",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_market_event_fact_pipeline",
                columnNames = {"evidence_fact_id", "pipeline_version"}),
        indexes = {
                @Index(name = "idx_market_event_pipeline", columnList = "pipeline_version"),
                @Index(name = "idx_market_event_published", columnList = "published_date"),
                @Index(name = "idx_market_event_company", columnList = "company")
        })
public class MarketEvent {

    public enum EventType { EVENT, PRODUCT_LAUNCH, FEE_CHANGE, REGULATION, METRIC }
    public enum MarketScope { VIETNAM, REGIONAL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_key", nullable = false, unique = true, length = 96)
    private String eventKey;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "evidence_fact_id", nullable = false)
    private EvidenceFact evidenceFact;

    @Column(name = "evidence_fact_code", nullable = false, length = 16)
    private String evidenceFactCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 32)
    private EventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "market_scope", nullable = false, length = 16)
    private MarketScope marketScope;

    /** Conservative country/region label inferred from source registry metadata. */
    @Column(length = 64)
    private String geography;

    /** Null is meaningful: the evidence did not name an entity verbatim. */
    @Column(length = 256)
    private String company;

    /** Null is meaningful: the evidence did not name a product verbatim. */
    @Column(name = "product_name", length = 512)
    private String productName;

    @Column(name = "published_date")
    private LocalDate publishedDate;

    /** Original ambiguous date from EvidenceFact, retained without reinterpretation. */
    @Column(name = "source_event_date")
    private LocalDate sourceEventDate;

    @Column(name = "occurred_date")
    private LocalDate occurredDate;

    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "forecast_horizon")
    private LocalDate forecastHorizon;

    @Column(name = "source_code", nullable = false, length = 64)
    private String sourceCode;

    @Column(name = "source_tier", nullable = false)
    private int sourceTier;

    @Column(name = "pipeline_version", nullable = false, length = 64)
    private String pipelineVersion;

    @Column(name = "model_version", nullable = false, length = 128)
    private String modelVersion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cluster_id")
    private MarketEventCluster cluster;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected MarketEvent() {}

    public MarketEvent(String eventKey, EvidenceFact evidenceFact, EventType eventType,
                       MarketScope marketScope, String geography, String company,
                       String productName, LocalDate publishedDate, LocalDate sourceEventDate,
                       LocalDate occurredDate, LocalDate effectiveDate, LocalDate expiryDate,
                       LocalDate forecastHorizon,
                       String sourceCode, int sourceTier, String pipelineVersion,
                       String modelVersion) {
        this.eventKey = eventKey;
        this.evidenceFact = evidenceFact;
        this.evidenceFactCode = evidenceFact.getFactCode();
        this.eventType = eventType;
        this.marketScope = marketScope;
        this.geography = geography;
        this.company = company;
        this.productName = productName;
        this.publishedDate = publishedDate;
        this.sourceEventDate = sourceEventDate;
        this.occurredDate = occurredDate;
        this.effectiveDate = effectiveDate;
        this.expiryDate = expiryDate;
        this.forecastHorizon = forecastHorizon;
        this.sourceCode = sourceCode;
        this.sourceTier = sourceTier;
        this.pipelineVersion = pipelineVersion;
        this.modelVersion = modelVersion;
    }

    public Long getId() { return id; }
    public String getEventKey() { return eventKey; }
    public EvidenceFact getEvidenceFact() { return evidenceFact; }
    public String getEvidenceFactCode() { return evidenceFactCode; }
    public EventType getEventType() { return eventType; }
    public MarketScope getMarketScope() { return marketScope; }
    public String getGeography() { return geography; }
    public String getCompany() { return company; }
    public String getProductName() { return productName; }
    public LocalDate getPublishedDate() { return publishedDate; }
    public LocalDate getSourceEventDate() { return sourceEventDate; }
    public LocalDate getOccurredDate() { return occurredDate; }
    public LocalDate getEffectiveDate() { return effectiveDate; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public LocalDate getForecastHorizon() { return forecastHorizon; }
    public String getSourceCode() { return sourceCode; }
    public int getSourceTier() { return sourceTier; }
    public String getPipelineVersion() { return pipelineVersion; }
    public String getModelVersion() { return modelVersion; }
    public MarketEventCluster getCluster() { return cluster; }
    public Instant getCreatedAt() { return createdAt; }
    public void assignCluster(MarketEventCluster cluster) { this.cluster = cluster; }
}
