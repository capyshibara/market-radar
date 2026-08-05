package com.marketradar.domain;

/**
 * Editorial use of a registered source, independent from acquisition health.
 *
 * <p>{@link Source#isActive()} answers only whether Scout may crawl the channel.
 * This policy answers whether already stored documents may consume AI and which
 * publication lane they may reach. Keeping the axes separate means a temporarily
 * broken official URL does not invalidate its historical evidence, while an
 * active low-authority feed cannot silently become management-grade evidence.</p>
 */
public enum SourceUsePolicy {
    /** May be analysed and may support decision-grade content after all other gates. */
    DECISION_ELIGIBLE(true, true),
    /** May be analysed, but content is capped at the visibly labelled watch lane. */
    WATCH_ONLY(true, false),
    /** Retained/crawled for audit or future curation; no paid downstream AI calls. */
    ARCHIVE_ONLY(false, false);

    private final boolean analysisAllowed;
    private final boolean decisionPublicationAllowed;

    SourceUsePolicy(boolean analysisAllowed, boolean decisionPublicationAllowed) {
        this.analysisAllowed = analysisAllowed;
        this.decisionPublicationAllowed = decisionPublicationAllowed;
    }

    public boolean allowsAnalysis() { return analysisAllowed; }
    public boolean allowsDecisionPublication() { return decisionPublicationAllowed; }
}
