import vn.techcomlife.marketradar.review.ReviewRules;

/**
 * Test standalone cho logic thuần Batch 4 — chạy trên JRE trần, KHÔNG cần Maven/Spring.
 * Khác GateL1Test (bản port): file này compile TRỰC TIẾP ReviewRules.java của main code
 * → test đúng code sẽ chạy trong app.
 *
 * Chạy:
 *   javac -d out src/main/java/vn/techcomlife/marketradar/review/ReviewRules.java Batch4LogicTest.java
 *   java -cp out Batch4LogicTest
 */
public class Batch4LogicTest {

    static int passed = 0, failed = 0;

    public static void main(String[] args) {
        // ---- Tier placeholder ----
        check("demo-inject → T3", "T3", ReviewRules.assignTier(true, false, 3));
        check("exec-summary → T3", "T3", ReviewRules.assignTier(false, true, 2));
        check("nguồn regulator (tier 1) → T3 (hard override)", "T3", ReviewRules.assignTier(false, false, 1));
        check("nguồn media (tier 2) → T1", "T1", ReviewRules.assignTier(false, false, 2));
        check("nguồn blog (tier 4) → T1", "T1", ReviewRules.assignTier(false, false, 4));

        // ---- requiresHumanReview ----
        check("T0 diện sample", false, ReviewRules.requiresHumanReview("T0"));
        check("T1 diện sample", false, ReviewRules.requiresHumanReview("T1"));
        check("T2 bắt buộc người", true, ReviewRules.requiresHumanReview("T2"));
        check("T3 bắt buộc người", true, ReviewRules.requiresHumanReview("T3"));

        // ---- autoPublishable: CHỈ ENTAILED + tier sample ----
        check("ENTAILED + T1 → auto", true, ReviewRules.autoPublishable("ENTAILED", "T1"));
        check("ENTAILED + T3 → review", false, ReviewRules.autoPublishable("ENTAILED", "T3"));
        check("NEUTRAL + T1 → review", false, ReviewRules.autoPublishable("NEUTRAL", "T1"));
        check("CONTRADICTED + T1 → review", false, ReviewRules.autoPublishable("CONTRADICTED", "T1"));
        check("VERIFIER_ERROR + T1 → review (lỗi không bao giờ = pass)",
                false, ReviewRules.autoPublishable("VERIFIER_ERROR", "T1"));

        // ---- Precondition approve/edit (chống rubber-stamp) ----
        check("approve chưa mở evidence → chặn", true, ReviewRules.validateApprove(false) != null);
        check("approve đã mở evidence → cho", null, ReviewRules.validateApprove(true));
        check("edit chưa mở evidence → chặn", true, ReviewRules.validateEdit(false, "text") != null);
        check("edit text rỗng → chặn", true, ReviewRules.validateEdit(true, "   ") != null);
        check("edit hợp lệ → cho", null, ReviewRules.validateEdit(true, "text mới"));

        // ---- Force-approve: 3 điều kiện ----
        check("force chưa mở evidence → chặn", true,
                ReviewRules.validateForceApprove(false, "lý do đủ dài đây", "F-001") != null);
        check("force lý do < 10 ký tự → chặn", true,
                ReviewRules.validateForceApprove(true, "ngắn", "F-001") != null);
        check("force KHÔNG citation → chặn (Invariant #1)", true,
                ReviewRules.validateForceApprove(true, "lý do đủ dài đây", "  ") != null);
        check("force hợp lệ → cho", null,
                ReviewRules.validateForceApprove(true, "lý do đủ dài đây", "F-001"));

        // ---- Reject ----
        check("reject không lý do → chặn", true, ReviewRules.validateReject(null) != null);
        check("reject lý do 5+ ký tự → cho", null, ReviewRules.validateReject("trùng nguồn"));

        // ---- Verdict normalize + fence strip ----
        check("verdict entailed thường → ENTAILED", "ENTAILED", ReviewRules.normalizeVerdict("entailed"));
        check("verdict có space → NEUTRAL", "NEUTRAL", ReviewRules.normalizeVerdict("  Neutral "));
        check("verdict lạ → VERIFIER_ERROR", "VERIFIER_ERROR", ReviewRules.normalizeVerdict("MAYBE"));
        check("verdict null → VERIFIER_ERROR", "VERIFIER_ERROR", ReviewRules.normalizeVerdict(null));
        check("strip ```json fence", "{\"a\":1}",
                ReviewRules.stripCodeFences("```json\n{\"a\":1}\n```"));
        check("strip fence trần", "{\"a\":1}",
                ReviewRules.stripCodeFences("```\n{\"a\":1}\n```"));
        check("không fence giữ nguyên", "{\"a\":1}", ReviewRules.stripCodeFences("{\"a\":1}"));

        System.out.println("\n==== " + passed + " PASS / " + failed + " FAIL ====");
        if (failed > 0) System.exit(1);
    }

    static void check(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        System.out.println((ok ? "  ✓ " : "  ✗ ") + name
                + (ok ? "" : "  (expected=" + expected + ", actual=" + actual + ")"));
        if (ok) passed++; else failed++;
    }
}
