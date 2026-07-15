import com.marketradar.review.PublicationEligibilityRules;

/** Standalone regression suite for verification/review publication gates. */
public class PublicationEligibilityRulesTest {
    private static int checks;

    public static void main(String[] args) {
        check(PublicationEligibilityRules.isPublishable("PASS", "AUTO_APPROVED", "ENTAILED"), "auto + entailed");
        check(PublicationEligibilityRules.isPublishable("PASS", "APPROVED", "ENTAILED"), "human + entailed");
        check(PublicationEligibilityRules.isPublishable("PASS", "EDITED_APPROVED", "ENTAILED"), "edit + entailed");
        check(PublicationEligibilityRules.isPublishable("PASS", "FORCE_APPROVED", "ENTAILED"), "force + entailed");
        reject("FAIL_NO_CITATION", "APPROVED", "ENTAILED", "L1 failure");
        reject("PASS", "PENDING_VERIFICATION", "ENTAILED", "unapproved");
        reject("PASS", "PENDING_REVIEW", "ENTAILED", "pending review");
        reject("PASS", "APPROVED", null, "unverified");
        reject("PASS", "APPROVED", "NEUTRAL", "latest neutral");
        reject("PASS", "FORCE_APPROVED", "CONTRADICTED", "latest contradicted despite override");
        reject("PASS", "APPROVED", "VERIFIER_ERROR", "verifier error");
        check(!PublicationEligibilityRules.isPublishable("PASS", "APPROVED", "ENTAILED", true),
                "superseded edition cannot publish");
        check(PublicationEligibilityRules.isNarrativeInputEligible(
                "PASS", "APPROVED", "ENTAILED", "WHY_MATTERS", "PIPELINE", true, false), "why enters narrative");
        check(PublicationEligibilityRules.isNarrativeInputEligible(
                "PASS", "AUTO_APPROVED", "ENTAILED", "IMPLICATION", "PIPELINE", true, false), "implication enters narrative");
        check(!PublicationEligibilityRules.isNarrativeInputEligible(
                "PASS", "APPROVED", "NEUTRAL", "IMPLICATION", "PIPELINE", true, false), "neutral excluded");
        check(!PublicationEligibilityRules.isNarrativeInputEligible(
                "PASS", "APPROVED", "ENTAILED", "NARRATIVE", "PIPELINE", true, false), "no recursive narrative");
        check(!PublicationEligibilityRules.isNarrativeInputEligible(
                "PASS", "APPROVED", "ENTAILED", "WHY_MATTERS", "PIPELINE", true, true), "duplicate excluded");
        check(!PublicationEligibilityRules.isNarrativeInputEligible(
                "PASS", "APPROVED", "ENTAILED", "WHY_MATTERS", "DEMO_INJECT", true, false), "demo excluded");
        System.out.println("PublicationEligibilityRulesTest: " + checks + " checks passed");
    }

    private static void reject(String gate, String review, String verdict, String label) {
        check(!PublicationEligibilityRules.isPublishable(gate, review, verdict), "reject " + label);
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }
}
