import com.marketradar.extract.LongDocumentChunker;

public class LongDocumentChunkerTest {
    public static void main(String[] args) {
        var shortPlan = LongDocumentChunker.plan("x".repeat(600));
        check(shortPlan.chunkCount() == 1, "short document uses one chunk");
        check(shortPlan.completeCoverage(), "short document fully covered");

        String text = "A".repeat(23_900) + "LATE_SECTION_RELEVANT" + "B".repeat(31_000);
        var longPlan = LongDocumentChunker.plan(text);
        check(longPlan.chunkCount() == 3, "long document uses all required chunks");
        check(longPlan.completeCoverage(), "long document has no gaps");
        check(longPlan.silentlyDroppedCharacters() == 0, "no characters silently dropped");
        check(longPlan.chunks().stream().anyMatch(c -> c.text().contains("LATE_SECTION_RELEVANT")),
                "later relevant section reaches a model input");
        for (int i = 1; i < longPlan.chunks().size(); i++) {
            var prior = longPlan.chunks().get(i - 1);
            var next = longPlan.chunks().get(i);
            check(next.startInclusive() < prior.endExclusive(), "adjacent chunks overlap");
        }

        boolean invalidRejected = false;
        try { LongDocumentChunker.plan("x", 100, 100); }
        catch (IllegalArgumentException expected) { invalidRejected = true; }
        check(invalidRejected, "invalid overlap rejected");
        System.out.println("LongDocumentChunkerTest: ALL PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError("Failed: " + message);
    }
}
