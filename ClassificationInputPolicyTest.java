import com.marketradar.classify.ClassificationInputPolicy;

/** Dependency-free regression checks for pre-classification content eligibility. */
public class ClassificationInputPolicyTest {
    public static void main(String[] args) {
        check(ClassificationInputPolicy.assess(true, true, "x".repeat(700)).decision()
                        == ClassificationInputPolicy.Decision.SAMPLE_DATA,
                "sample content must never consume classifier calls");
        check(ClassificationInputPolicy.assess(false, false, "headline only").decision()
                        == ClassificationInputPolicy.Decision.NEEDS_FULL_TEXT,
                "unverified/title-only content must skip");
        check(ClassificationInputPolicy.assess(false, false, "x".repeat(700)).decision()
                        == ClassificationInputPolicy.Decision.NEEDS_FULL_TEXT,
                "long title/listing text without full-text provenance must still skip");
        check(ClassificationInputPolicy.assess(false, true, null).decision()
                        == ClassificationInputPolicy.Decision.EMPTY_TEXT,
                "null full-text payload must skip");
        check(ClassificationInputPolicy.assess(false, true, "   ").decision()
                        == ClassificationInputPolicy.Decision.EMPTY_TEXT,
                "blank full-text payload must skip");
        check(ClassificationInputPolicy.assess(false, true, "x".repeat(599)).decision()
                        == ClassificationInputPolicy.Decision.SHORT_TEXT,
                "content below the shared 600-character floor must skip");
        check(ClassificationInputPolicy.assess(false, true, "  " + "x".repeat(599) + "  ").decision()
                        == ClassificationInputPolicy.Decision.SHORT_TEXT,
                "surrounding whitespace must not inflate the content floor");
        check(ClassificationInputPolicy.assess(false, true, "x".repeat(600)).eligible(),
                "the shared 600-character floor is inclusive");
        check(ClassificationInputPolicy.assess(false, true, "x".repeat(24001)).eligible(),
                "long content remains eligible because downstream extraction chunks it");
        System.out.println("ClassificationInputPolicyTest: ALL PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
