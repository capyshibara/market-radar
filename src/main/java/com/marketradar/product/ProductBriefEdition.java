package com.marketradar.product;

import com.marketradar.domain.Department;
import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Immutable snapshot metadata for one department brief.
 *
 * A brief is never silently overwritten when prompts, models, ranking rules, or
 * source facts change.  The source fingerprint and algorithm version make the
 * exact input/rules behind a delivered report auditable.
 */
@Entity
@Table(name = "product_brief_editions",
        uniqueConstraints = @UniqueConstraint(name = "uk_product_brief_code", columnNames = "editionCode"),
        indexes = @Index(name = "idx_product_brief_department_created", columnList = "department,createdAt"))
public class ProductBriefEdition {

    public enum Status {
        READY,
        /** Current, evidence-backed signals that have not met the full 3-insight decision threshold. */
        WATCH_BRIEF,
        INSUFFICIENT_EVIDENCE,
        GENERATION_FAILED,
        /** Kept only so legacy database rows remain readable. */
        @Deprecated INSUFFICIENT_SIGNAL
    }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 48)
    private String editionCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Department department;

    @Column(nullable = false)
    private LocalDate windowStart;

    @Column(nullable = false)
    private LocalDate windowEnd;

    @Column(nullable = false, length = 48)
    private String algorithmVersion;

    @Column(nullable = false, length = 64)
    private String sourceFingerprint;

    /** Nullable for safe migration of legacy editions; null editions fail current-contract checks. */
    @Column(length = 128)
    private String writerProvider;

    @Column(length = 64)
    private String writerPromptSha256;

    @Column(length = 48)
    private String insightSchemaVersion;

    @Column(length = 64)
    private String qualitySignature;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Status status;

    @Column(nullable = false)
    private int eligibleFactCount;

    @Column(nullable = false)
    private int insightCount;

    @Column(length = 2000)
    private String failureMessage;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected ProductBriefEdition() {}

    public ProductBriefEdition(String editionCode, Department department,
                               LocalDate windowStart, LocalDate windowEnd,
                               String algorithmVersion, String sourceFingerprint,
                               String writerProvider, String writerPromptSha256,
                               String insightSchemaVersion, String qualitySignature,
                               Status status, int eligibleFactCount, int insightCount,
                               String failureMessage) {
        this.editionCode = editionCode;
        this.department = department;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.algorithmVersion = algorithmVersion;
        this.sourceFingerprint = sourceFingerprint;
        this.writerProvider = writerProvider;
        this.writerPromptSha256 = writerPromptSha256;
        this.insightSchemaVersion = insightSchemaVersion;
        this.qualitySignature = qualitySignature;
        this.status = status;
        this.eligibleFactCount = eligibleFactCount;
        this.insightCount = insightCount;
        this.failureMessage = failureMessage == null || failureMessage.length() <= 2000
                ? failureMessage : failureMessage.substring(0, 2000) + "…";
    }

    public Long getId() { return id; }
    public String getEditionCode() { return editionCode; }
    public Department getDepartment() { return department; }
    public LocalDate getWindowStart() { return windowStart; }
    public LocalDate getWindowEnd() { return windowEnd; }
    public String getAlgorithmVersion() { return algorithmVersion; }
    public String getSourceFingerprint() { return sourceFingerprint; }
    public String getWriterProvider() { return writerProvider; }
    public String getWriterPromptSha256() { return writerPromptSha256; }
    public String getInsightSchemaVersion() { return insightSchemaVersion; }
    public String getQualitySignature() { return qualitySignature; }
    public Status getStatus() { return status; }
    public int getEligibleFactCount() { return eligibleFactCount; }
    public int getInsightCount() { return insightCount; }
    public String getFailureMessage() { return failureMessage; }
    public Instant getCreatedAt() { return createdAt; }
}
