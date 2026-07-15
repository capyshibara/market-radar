package com.marketradar.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Append-only audit ledger for classification attempts. The one-to-one
 * classifications table remains the materialized active result; this table
 * preserves both the prior snapshot and the candidate that tried to replace it.
 */
@Entity
@Table(name = "classification_attempts", indexes = {
        @Index(name = "idx_class_attempt_doc_version",
                columnList = "raw_doc_id, version_signature"),
        @Index(name = "idx_class_attempt_outcome", columnList = "outcome")
})
public class ClassificationAttempt {

    public enum Outcome {
        APPLIED_NEW,
        APPLIED_REPLACEMENT,
        PRESERVED_PRIOR_REVIEW,
        CONCURRENT_CHANGE_PRESERVED,
        ERROR
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "raw_doc_id", nullable = false)
    private RawDoc rawDoc;

    @Column(name = "provider_model", nullable = false, length = 128)
    private String providerModel;

    @Column(name = "prompt_sha256", nullable = false, length = 64)
    private String promptSha256;

    @Column(name = "content_sha256", nullable = false, length = 64)
    private String contentSha256;

    @Column(name = "version_signature", nullable = false, length = 64)
    private String versionSignature;

    @Column(name = "prior_classification_id")
    private Long priorClassificationId;

    @Lob
    @Column(name = "prior_snapshot_json", columnDefinition = "CLOB")
    private String priorSnapshotJson;

    @Lob
    @Column(name = "candidate_snapshot_json", columnDefinition = "CLOB")
    private String candidateSnapshotJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private Outcome outcome;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ClassificationAttempt() {}

    public ClassificationAttempt(RawDoc rawDoc, String providerModel, String promptSha256,
                                 String contentSha256, String versionSignature,
                                 Long priorClassificationId, String priorSnapshotJson,
                                 String candidateSnapshotJson, Outcome outcome,
                                 String errorMessage) {
        this.rawDoc = rawDoc;
        this.providerModel = providerModel;
        this.promptSha256 = promptSha256;
        this.contentSha256 = contentSha256;
        this.versionSignature = versionSignature;
        this.priorClassificationId = priorClassificationId;
        this.priorSnapshotJson = priorSnapshotJson;
        this.candidateSnapshotJson = candidateSnapshotJson;
        this.outcome = outcome;
        this.errorMessage = truncate(errorMessage, 2000);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max) + "…";
    }

    public Long getId() { return id; }
    public RawDoc getRawDoc() { return rawDoc; }
    public String getProviderModel() { return providerModel; }
    public String getPromptSha256() { return promptSha256; }
    public String getContentSha256() { return contentSha256; }
    public String getVersionSignature() { return versionSignature; }
    public Long getPriorClassificationId() { return priorClassificationId; }
    public String getPriorSnapshotJson() { return priorSnapshotJson; }
    public String getCandidateSnapshotJson() { return candidateSnapshotJson; }
    public Outcome getOutcome() { return outcome; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
}
