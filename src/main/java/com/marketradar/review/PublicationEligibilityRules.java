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
        return "PASS".equals(gateStatus)
                && APPROVED.contains(reviewStatus)
                && "ENTAILED".equals(latestVerdict)
                && !superseded;
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
