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

import java.time.Instant;

/** Immutable-provenance ledger for every attempted fact extraction edition. */
@Entity
@Table(name = "fact_extraction_runs", indexes = {
        @Index(name = "idx_fact_extract_doc_signature",
                columnList = "raw_doc_id, extraction_signature"),
        @Index(name = "idx_fact_extract_status", columnList = "status")
})
public class FactExtractionRun {

    public enum Status { RUNNING, SUCCESS, EMPTY_RESULT, LLM_ERROR, SCHEMA_REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "raw_doc_id", nullable = false)
    private RawDoc rawDoc;

    @Column(name = "pipeline_version", nullable = false, length = 64)
    private String pipelineVersion;

    @Column(name = "model_version", nullable = false, length = 128)
    private String modelVersion;

    @Column(name = "prompt_sha256", nullable = false, length = 64)
    private String promptSha256;

    @Column(name = "input_content_hash", nullable = false, length = 64)
    private String inputContentHash;

    @Column(name = "extraction_signature", nullable = false, length = 64)
    private String extractionSignature;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Status status = Status.RUNNING;

    @Column(name = "facts_saved", nullable = false)
    private int factsSaved;

    @Column(name = "spans_rejected", nullable = false)
    private int spansRejected;

    @Column(name = "input_chars", nullable = false)
    private int inputChars;

    @Column(name = "chunks_planned", nullable = false)
    private int chunksPlanned;

    @Column(name = "chunks_completed", nullable = false)
    private int chunksCompleted;

    @Column(name = "facts_proposed", nullable = false)
    private int factsProposed;

    @Column(name = "duplicate_spans_collapsed", nullable = false)
    private int duplicateSpansCollapsed;

    @Column(name = "rejection_summary", length = 2000)
    private String rejectionSummary;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "current_edition", nullable = false,
            columnDefinition = "boolean default false")
    private boolean currentEdition;

    @Column(name = "superseded_at")
    private Instant supersededAt;

    protected FactExtractionRun() {}

    public FactExtractionRun(RawDoc rawDoc, String pipelineVersion, String modelVersion,
                             String promptSha256, String inputContentHash,
                             String extractionSignature) {
        this.rawDoc = rawDoc;
        this.pipelineVersion = pipelineVersion;
        this.modelVersion = modelVersion;
        this.promptSha256 = promptSha256;
        this.inputContentHash = inputContentHash;
        this.extractionSignature = extractionSignature;
    }

    public void succeed(int factsSaved, int spansRejected) {
        this.status = Status.SUCCESS;
        this.factsSaved = factsSaved;
        this.spansRejected = spansRejected;
        this.finishedAt = Instant.now();
        this.errorMessage = null;
        this.currentEdition = true;
    }

    public void applyMetrics(int inputChars, int chunksPlanned, int chunksCompleted,
                             int factsProposed, int spansRejected,
                             int duplicateSpansCollapsed, String rejectionSummary) {
        this.inputChars = inputChars;
        this.chunksPlanned = chunksPlanned;
        this.chunksCompleted = chunksCompleted;
        this.factsProposed = factsProposed;
        this.spansRejected = spansRejected;
        this.duplicateSpansCollapsed = duplicateSpansCollapsed;
        this.rejectionSummary = truncate(rejectionSummary, 2000);
    }

    public void fail(Status status, String message) {
        if (status != Status.EMPTY_RESULT && status != Status.LLM_ERROR
                && status != Status.SCHEMA_REJECTED) {
            throw new IllegalArgumentException("failure status required");
        }
        this.status = status;
        this.finishedAt = Instant.now();
        this.errorMessage = truncate(message, 2000);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max) + "…";
    }

    public Long getId() { return id; }
    public RawDoc getRawDoc() { return rawDoc; }
    public String getPipelineVersion() { return pipelineVersion; }
    public String getModelVersion() { return modelVersion; }
    public String getPromptSha256() { return promptSha256; }
    public String getInputContentHash() { return inputContentHash; }
    public String getExtractionSignature() { return extractionSignature; }
    public Status getStatus() { return status; }
    public int getFactsSaved() { return factsSaved; }
    public int getSpansRejected() { return spansRejected; }
    public int getInputChars() { return inputChars; }
    public int getChunksPlanned() { return chunksPlanned; }
    public int getChunksCompleted() { return chunksCompleted; }
    public int getFactsProposed() { return factsProposed; }
    public int getDuplicateSpansCollapsed() { return duplicateSpansCollapsed; }
    public String getRejectionSummary() { return rejectionSummary; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public String getErrorMessage() { return errorMessage; }
    public boolean isCurrentEdition() { return currentEdition; }
    public Instant getSupersededAt() { return supersededAt; }
}
