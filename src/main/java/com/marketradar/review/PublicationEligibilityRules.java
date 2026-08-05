package com.marketradar.review;

import java.util.Set;

/**
 * Fail-closed publication rules shared conceptually by repository queries,
 * narrative synthesis and offline tests. Strings keep this core dependency-free.
 */
public final class PublicationEligibilityRules {
    private PublicationEligibilityRules() {}

    private static final Set<String> APPROVED = Set.of(
            "AUTO_APPROVED", "APPROVED", "EDITED_APPROVED", "FORCE_APPROVED");
    private static final Set<String> NARRATIVE_INPUT_SLOTS = Set.of("WHY_MATTERS", "IMPLICATION");
    private static final Set<String> MANUAL_APPROVED = Set.of(
            "APPROVED", "EDITED_APPROVED", "FORCE_APPROVED");

    public enum Disposition { DECISION_GRADE, REVIEWED_ANALYSIS, EDITORIAL_WATCH, EXCLUDE }

    /**
     * Two-lane publication: independently entailed content is decision-grade;
     * a human-approved neutral claim may appear only in a clearly labelled watch
     * lane. Contradiction, verifier failure, missing verification and unsafe
     * entity attribution are excluded.
     */
    public static Disposition disposition(String gateStatus, String reviewStatus,
                                          String latestVerdict, boolean superseded,
                                          boolean entityAttributionSafe) {
        return disposition(gateStatus, reviewStatus, latestVerdict, superseded,
                entityAttributionSafe, false);
    }

    /**
     * Analysis is not mislabeled as an independently verified fact. A cautious
     * implication will often be NEUTRAL under strict entailment because it is a
     * reasoned inference rather than a quotation. Once a human has reviewed it,
     * it may be published in a separate REVIEWED_ANALYSIS lane. It is never mixed
     * into fact charts, corroboration counts or automatic executive highlights.
     */
    public static Disposition disposition(String gateStatus, String reviewStatus,
                                          String latestVerdict, boolean superseded,
                                          boolean entityAttributionSafe,
                                          boolean analyticalContent) {
        if (!"PASS".equals(gateStatus) || superseded || !APPROVED.contains(reviewStatus)
                || !entityAttributionSafe) return Disposition.EXCLUDE;
        if ("ENTAILED".equals(latestVerdict)) return Disposition.DECISION_GRADE;
        if ("NEUTRAL".equals(latestVerdict) && MANUAL_APPROVED.contains(reviewStatus)) {
            return analyticalContent ? Disposition.REVIEWED_ANALYSIS : Disposition.EDITORIAL_WATCH;
        }
        return Disposition.EXCLUDE;
    }

    /**
     * A claim is publishable only when L1 passed, review approval is current,
     * and the latest independent verification entails the claim. Missing state
     * and verifier errors fail closed. FORCE_APPROVED does not waive evidence.
     */
    public static boolean isPublishable(String gateStatus, String reviewStatus, String latestVerdict) {
        return isPublishable(gateStatus, reviewStatus, latestVerdict, false);
    }

    public static boolean isPublishable(String gateStatus, String reviewStatus,
                                        String latestVerdict, boolean superseded) {
        return disposition(gateStatus, reviewStatus, latestVerdict, superseded, true)
                == Disposition.DECISION_GRADE;
    }

    public static boolean isNarrativeInputEligible(String gateStatus, String reviewStatus,
                                                    String latestVerdict, String slot,
                                                    String origin, boolean hasRawDoc,
                                                    boolean duplicate) {
        return isPublishable(gateStatus, reviewStatus, latestVerdict)
                && NARRATIVE_INPUT_SLOTS.contains(slot)
                && "PIPELINE".equals(origin)
                && hasRawDoc
                && !duplicate;
    }
}
