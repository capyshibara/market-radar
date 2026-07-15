package com.marketradar.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/** Append-only audit outcome for a confirmed, explicit-ID refetch attempt. */
@Entity
@Table(name = "targeted_refetch_attempts", indexes = {
        @Index(name = "idx_refetch_attempt_doc", columnList = "raw_doc_id"),
        @Index(name = "idx_refetch_attempt_at", columnList = "attempted_at")
})
public class TargetedRefetchAttempt {

    public enum Status {
        SUCCESS,
        DOCUMENT_NOT_FOUND,
        NOT_ELIGIBLE,
        HOST_REJECTED,
        FETCH_OR_PARSE_FAILED,
        INSUFFICIENT_TEXT,
        DUPLICATE_CONTENT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "raw_doc_id")
    private Long rawDocId;

    @Column(name = "source_code", length = 64)
    private String sourceCode;

    @Column(name = "document_url", length = 2048)
    private String documentUrl;

    @Column(name = "eligibility_reason", nullable = false, length = 64)
    private String eligibilityReason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private Status status;

    @Column(name = "previous_content_hash", length = 64)
    private String previousContentHash;

    @Column(name = "new_content_hash", length = 64)
    private String newContentHash;

    @Column(length = 1024)
    private String message;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt = Instant.now();

    protected TargetedRefetchAttempt() {}

    public TargetedRefetchAttempt(Long rawDocId, String sourceCode, String documentUrl,
                                  String eligibilityReason, Status status,
                                  String previousContentHash, String newContentHash,
                                  String message) {
        this.rawDocId = rawDocId;
        this.sourceCode = sourceCode;
        this.documentUrl = documentUrl;
        this.eligibilityReason = eligibilityReason;
        this.status = status;
        this.previousContentHash = previousContentHash;
        this.newContentHash = newContentHash;
        this.message = message;
    }

    public Long getId() { return id; }
    public Long getRawDocId() { return rawDocId; }
    public String getSourceCode() { return sourceCode; }
    public String getDocumentUrl() { return documentUrl; }
    public String getEligibilityReason() { return eligibilityReason; }
    public Status getStatus() { return status; }
    public String getPreviousContentHash() { return previousContentHash; }
    public String getNewContentHash() { return newContentHash; }
    public String getMessage() { return message; }
    public Instant getAttemptedAt() { return attemptedAt; }
}
