import com.marketradar.classify.ClassificationInputPolicy;

import java.time.Instant;

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

        Instant now = Instant.parse("2026-08-05T00:00:00Z");
        check(ClassificationInputPolicy.assessCurrentMetadata(900, "Current product launch",
                        "https://example.com/news/product", Instant.parse("2026-07-01T00:00:00Z"),
                        now, 400, true).eligible(),
                "dated current intelligence remains eligible");
        check(ClassificationInputPolicy.assessCurrentMetadata(900, "Undated article",
                        "https://example.com/news/article", null, now, 400, true).decision()
                        == ClassificationInputPolicy.Decision.DATE_UNRESOLVED,
                "undated content must not enter a time-bounded paid run");
        check(ClassificationInputPolicy.assessCurrentMetadata(900, "Old article",
                        "https://example.com/news/article", Instant.parse("2024-01-01T00:00:00Z"),
                        now, 400, true).decision()
                        == ClassificationInputPolicy.Decision.OUTSIDE_ANALYSIS_HORIZON,
                "old archives remain stored but do not consume current-analysis calls");
        check(ClassificationInputPolicy.assessCurrentMetadata(900, "Future article",
                        "https://example.com/news/article", Instant.parse("2026-09-01T00:00:00Z"),
                        now, 400, true).decision()
                        == ClassificationInputPolicy.Decision.FUTURE_DATED,
                "materially future-dated metadata is held");
        check(ClassificationInputPolicy.assessCurrentMetadata(900, "Healthy summer recipes",
                        "https://insurer.example/vi/blog/health-lifestyle/recipes/",
                        Instant.parse("2026-07-01T00:00:00Z"), now, 400, true).decision()
                        == ClassificationInputPolicy.Decision.NON_INTELLIGENCE_CONTENT,
                "consumer lifestyle navigation is removed before paid curation");
        check(ClassificationInputPolicy.assessCurrentMetadata(900, "Thông báo nghỉ Tết Nguyên Đán",
                        "https://insurer.example/notices/holiday", Instant.parse("2026-01-01T00:00:00Z"),
                        now, 400, true).decision()
                        == ClassificationInputPolicy.Decision.NON_INTELLIGENCE_CONTENT,
                "operational holiday notices are not management intelligence");
        System.out.println("ClassificationInputPolicyTest: ALL PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
