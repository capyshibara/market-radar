import com.marketradar.pipeline.TargetedRefetchPolicy;

import java.util.ArrayList;
import java.util.List;

public class TargetedRefetchPolicyTest {
    public static void main(String[] args) {
        check(TargetedRefetchPolicy.eligibility(false, 4_000)
                        == TargetedRefetchPolicy.EligibilityReason.UNVERIFIED_FULL_TEXT,
                "unverified full-text flag remains eligible even when stored text is long");
        check(TargetedRefetchPolicy.eligibility(true, 599)
                        == TargetedRefetchPolicy.EligibilityReason.SHORT_FULL_TEXT,
                "verified but shallow document remains eligible");
        check(TargetedRefetchPolicy.eligibility(true, 600) == null,
                "600-character verified document meets the floor");
        check(TargetedRefetchPolicy.contentCharacters("  " + "x".repeat(599) + "  ") == 599,
                "surrounding whitespace does not hide a shallow document from refetch planning");
        check(TargetedRefetchPolicy.contentCharacters(null) == 0,
                "missing content has zero usable characters");
        check(TargetedRefetchPolicy.normalizeIds(List.of(4L, 4L, 7L), true)
                        .equals(List.of(4L, 7L)),
                "explicit IDs are stable and deduplicated");
        rejects(() -> TargetedRefetchPolicy.normalizeIds(List.of(), true),
                "mutation requires explicit IDs");
        List<Long> tooMany = new ArrayList<>();
        for (long id = 1; id <= 26; id++) tooMany.add(id);
        rejects(() -> TargetedRefetchPolicy.normalizeIds(tooMany, true),
                "execution is bounded to 25 documents");
        check("news.example.com".equals(TargetedRefetchPolicy.articleFetchHost(
                        "SOURCE", "news.example.com", "news.example.com")),
                "same-host article remains allowed");
        check("chubb.mediaroom.com".equals(TargetedRefetchPolicy.articleFetchHost(
                        "CHUBB_VN", "www.chubb.com", "chubb.mediaroom.com")),
                "declared source-specific article host remains allowed");
        check(TargetedRefetchPolicy.articleFetchHost(
                        "SOURCE", "news.example.com", "attacker.example") == null,
                "undeclared external host fails closed");
        System.out.println("TargetedRefetchPolicyTest: ALL PASS");
    }

    private static void rejects(Runnable operation, String message) {
        boolean rejected = false;
        try { operation.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError("Failed: " + message);
    }
}
