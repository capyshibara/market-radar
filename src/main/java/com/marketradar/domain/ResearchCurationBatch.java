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

/** Append-only operational ledger for each paid Researcher batch or deferred audit. */
@Entity
@Table(name = "research_curation_batches", indexes = {
        @Index(name = "idx_research_batch_started", columnList = "started_at"),
        @Index(name = "idx_research_batch_snapshot", columnList = "candidate_snapshot")
})
public class ResearchCurationBatch {

    public enum Mode { MAIN, AUDIT }
    public enum Status { RUNNING, COMPLETED, FAILED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Mode mode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.RUNNING;

    @Column(name = "planner_version", nullable = false, length = 80)
    private String plannerVersion;

    @Column(name = "candidate_snapshot", nullable = false, length = 64)
    private String candidateSnapshot;

    @Lob @Column(name = "selected_document_ids", columnDefinition = "CLOB")
    private String selectedDocumentIds;

    @Lob @Column(name = "selected_cluster_keys", columnDefinition = "CLOB")
    private String selectedClusterKeys;

    @Column(name = "candidate_clusters", nullable = false)
    private int candidateClusters;

    @Column(name = "represented_clusters_before", nullable = false)
    private int representedClustersBefore;

    @Column(name = "remaining_clusters_before", nullable = false)
    private int remainingClustersBefore;

    @Column(name = "redundant_documents", nullable = false)
    private int redundantDocuments;

    @Column(name = "events_before", nullable = false)
    private int eventsBefore;

    @Column(name = "corroborated_before", nullable = false)
    private int corroboratedBefore;

    @Column(name = "conflicts_before", nullable = false)
    private int conflictsBefore;

    @Column(name = "attempted_documents", nullable = false)
    private int attemptedDocuments;

    @Column(name = "successful_documents", nullable = false)
    private int successfulDocuments;

    @Column(name = "facts_saved", nullable = false)
    private int factsSaved;

    @Column(name = "new_event_clusters", nullable = false)
    private int newEventClusters;

    @Column(name = "new_corroborated_clusters", nullable = false)
    private int newCorroboratedClusters;

    @Column(name = "new_conflict_clusters", nullable = false)
    private int newConflictClusters;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    protected ResearchCurationBatch() {}

    public ResearchCurationBatch(Mode mode, String plannerVersion, String candidateSnapshot,
                                 String selectedDocumentIds, String selectedClusterKeys,
                                 int candidateClusters, int representedClustersBefore,
                                 int remainingClustersBefore, int redundantDocuments,
                                 int eventsBefore, int corroboratedBefore, int conflictsBefore) {
        this.mode = mode;
        this.plannerVersion = plannerVersion;
        this.candidateSnapshot = candidateSnapshot;
        this.selectedDocumentIds = selectedDocumentIds;
        this.selectedClusterKeys = selectedClusterKeys;
        this.candidateClusters = candidateClusters;
        this.representedClustersBefore = representedClustersBefore;
        this.remainingClustersBefore = remainingClustersBefore;
        this.redundantDocuments = redundantDocuments;
        this.eventsBefore = eventsBefore;
        this.corroboratedBefore = corroboratedBefore;
        this.conflictsBefore = conflictsBefore;
    }

    public void complete(int attemptedDocuments, int successfulDocuments, int factsSaved,
                         int eventsAfter, int corroboratedAfter, int conflictsAfter) {
        this.status = Status.COMPLETED;
        this.attemptedDocuments = Math.max(0, attemptedDocuments);
        this.successfulDocuments = Math.max(0, successfulDocuments);
        this.factsSaved = Math.max(0, factsSaved);
        this.newEventClusters = Math.max(0, eventsAfter - eventsBefore);
        this.newCorroboratedClusters = Math.max(0, corroboratedAfter - corroboratedBefore);
        this.newConflictClusters = Math.max(0, conflictsAfter - conflictsBefore);
        this.finishedAt = Instant.now();
        this.errorMessage = null;
    }

    public void fail(String message) {
        this.status = Status.FAILED;
        this.finishedAt = Instant.now();
        this.errorMessage = truncate(message, 2000);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max) + "…";
    }

    public Long getId() { return id; }
    public Mode getMode() { return mode; }
    public Status getStatus() { return status; }
    public String getPlannerVersion() { return plannerVersion; }
    public String getCandidateSnapshot() { return candidateSnapshot; }
    public String getSelectedDocumentIds() { return selectedDocumentIds; }
    public String getSelectedClusterKeys() { return selectedClusterKeys; }
    public int getCandidateClusters() { return candidateClusters; }
    public int getRepresentedClustersBefore() { return representedClustersBefore; }
    public int getRemainingClustersBefore() { return remainingClustersBefore; }
    public int getRedundantDocuments() { return redundantDocuments; }
    public int getAttemptedDocuments() { return attemptedDocuments; }
    public int getSuccessfulDocuments() { return successfulDocuments; }
    public int getFactsSaved() { return factsSaved; }
    public int getNewEventClusters() { return newEventClusters; }
    public int getNewCorroboratedClusters() { return newCorroboratedClusters; }
    public int getNewConflictClusters() { return newConflictClusters; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public String getErrorMessage() { return errorMessage; }
}
