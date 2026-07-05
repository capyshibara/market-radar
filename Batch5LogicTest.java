import vn.techcomlife.marketradar.alert.AlertRules;
import vn.techcomlife.marketradar.dedup.DedupRules;

import java.util.Set;

/**
 * Test standalone cho logic thuần Batch 5 — chạy trên JRE trần, KHÔNG cần Maven/Spring.
 * Cùng pattern Batch4LogicTest: compile TRỰC TIẾP AlertRules.java + DedupRules.java
 * của main code → test đúng code sẽ chạy trong app.
 *
 * Chạy:
 *   javac -d out src/main/java/vn/techcomlife/marketradar/alert/AlertRules.java \
 *                src/main/java/vn/techcomlife/marketradar/dedup/DedupRules.java Batch5LogicTest.java
 *   java -cp out Batch5LogicTest
 */
public class Batch5LogicTest {

    static int passed = 0, failed = 0;

    public static void main(String[] args) {

        // ================= AlertRules =================

        // ---- isHotTier: so sánh chuỗi T0..T4 ----
        check("T3 >= min T3 → hot", true, AlertRules.isHotTier("T3", "T3"));
        check("T4 >= min T3 → hot", true, AlertRules.isHotTier("T4", "T3"));
        check("T2 < min T3 → nguội", false, AlertRules.isHotTier("T2", "T3"));
        check("T1 < min T3 → nguội", false, AlertRules.isHotTier("T1", "T3"));
        check("tier null → không alert", false, AlertRules.isHotTier(null, "T3"));

        // ---- isPublishableStatus: chỉ 4 trạng thái *_APPROVED ----
        check("AUTO_APPROVED được alert", true, AlertRules.isPublishableStatus("AUTO_APPROVED"));
        check("APPROVED được alert", true, AlertRules.isPublishableStatus("APPROVED"));
        check("EDITED_APPROVED được alert", true, AlertRules.isPublishableStatus("EDITED_APPROVED"));
        check("FORCE_APPROVED được alert", true, AlertRules.isPublishableStatus("FORCE_APPROVED"));
        check("PENDING_REVIEW KHÔNG alert (không đi tắt qua gate)",
                false, AlertRules.isPublishableStatus("PENDING_REVIEW"));
        check("PENDING_VERIFICATION KHÔNG alert",
                false, AlertRules.isPublishableStatus("PENDING_VERIFICATION"));
        check("REJECTED KHÔNG alert", false, AlertRules.isPublishableStatus("REJECTED"));
        check("status null KHÔNG alert", false, AlertRules.isPublishableStatus(null));

        // ---- shouldAlert: cần CẢ HAI điều kiện ----
        check("T3 + APPROVED → bắn", true, AlertRules.shouldAlert("T3", "APPROVED", "T3"));
        check("T3 + PENDING_REVIEW → không (chưa duyệt)",
                false, AlertRules.shouldAlert("T3", "PENDING_REVIEW", "T3"));
        check("T1 + APPROVED → không (nguội)",
                false, AlertRules.shouldAlert("T1", "APPROVED", "T3"));

        // ---- buildAlertText: đủ 4 phần fact + nguồn + vì sao + hành động ----
        String alert = AlertRules.buildAlertText("C-007", "T3", "APPROVED",
                "Đối thủ vừa ra sản phẩm mới đáng chú ý.",
                "Công ty X ra mắt sản phẩm Y [F-001]",
                "Báo Z (tier 2) — https://example.com/bai-viet",
                "http://localhost:8080/report/weekly");
        check("alert chứa mã claim", true, alert.contains("C-007"));
        check("alert chứa tier", true, alert.contains("[HOT T3]"));
        check("alert chứa 'vì sao' (claim text)", true,
                alert.contains("Vì sao đáng chú ý: Đối thủ vừa ra sản phẩm mới"));
        check("alert chứa fact + mã fact", true, alert.contains("Fact gốc:") && alert.contains("[F-001]"));
        check("alert chứa nguồn", true, alert.contains("Nguồn: Báo Z (tier 2)"));
        check("alert chứa hành động", true, alert.contains("Hành động: http://localhost:8080/report/weekly"));

        // EXEC_SUMMARY: không doc → không dòng nguồn, alert vẫn dựng được
        String alertNoSrc = AlertRules.buildAlertText("C-008", "T3", "APPROVED",
                "Câu tổng hợp cấp report.", null, null, "http://localhost:8080/report/weekly");
        check("không fact/nguồn → bỏ dòng, không in null", true,
                !alertNoSrc.contains("null") && !alertNoSrc.contains("Nguồn:"));

        // ---- jsonEscape ----
        check("escape quote", "an toàn \\\"trích dẫn\\\"", AlertRules.jsonEscape("an toàn \"trích dẫn\""));
        check("escape newline", "dòng 1\\ndòng 2", AlertRules.jsonEscape("dòng 1\ndòng 2"));
        check("escape backslash", "a\\\\b", AlertRules.jsonEscape("a\\b"));
        check("giữ nguyên tiếng Việt có dấu", "ưu tiên số 1", AlertRules.jsonEscape("ưu tiên số 1"));
        check("escape control char", "a\\u0001b", AlertRules.jsonEscape("a\u0001b"));

        // ---- validateWebhookUrl ----
        check("rỗng = stub mode hợp lệ", null, AlertRules.validateWebhookUrl(""));
        check("null = stub mode hợp lệ", null, AlertRules.validateWebhookUrl(null));
        check("https hợp lệ", null,
                AlertRules.validateWebhookUrl("https://hooks.slack.com/services/x/y/z"));
        check("http thuần bị chặn", true,
                AlertRules.validateWebhookUrl("http://hooks.slack.com/x") != null);

        // ================= DedupRules =================

        // ---- normalizeTitle: giữ dấu tiếng Việt, bỏ ký tự lạ, gộp space ----
        check("giữ dấu, lowercase", "bảo việt ra mắt sản phẩm mới",
                DedupRules.normalizeTitle("Bảo Việt  RA MẮT sản phẩm mới!!!"));
        check("bỏ ký tự đặc biệt (%, —, ngoặc), giữ chữ", "phí tăng 15 chính thức",
                DedupRules.normalizeTitle("Phí tăng 15% — [chính thức]"));
        check("null → rỗng", "", DedupRules.normalizeTitle(null));

        // ---- jaccard ----
        check("hai tập bằng nhau → 1.0", true,
                DedupRules.jaccard(Set.of("a", "b"), Set.of("a", "b")) == 1.0);
        check("giao rỗng → 0.0", true,
                DedupRules.jaccard(Set.of("a"), Set.of("b")) == 0.0);
        check("tập rỗng → 0 (không tín hiệu ≠ giống)", true,
                DedupRules.jaccard(Set.of(), Set.of("a")) == 0.0);
        double j = DedupRules.jaccard(Set.of("a", "b", "c"), Set.of("b", "c", "d"));
        check("giao 2 / hợp 4 = 0.5", true, Math.abs(j - 0.5) < 1e-9);
        check("titleJaccard title y hệt = 1.0", true,
                DedupRules.titleJaccard("Bảo Việt ra mắt", "bảo việt RA MẮT!") == 1.0);

        // ---- within72h ----
        long h = 60L * 60 * 1000;
        check("71h → trong cửa sổ", true, DedupRules.within72h(0, 71 * h));
        check("72h đúng biên → trong cửa sổ", true, DedupRules.within72h(0, 72 * h));
        check("73h → ngoài cửa sổ", false, DedupRules.within72h(0, 73 * h));
        check("đối xứng (b trước a)", true, DedupRules.within72h(72 * h, 0));

        // ---- decidePair: thang exact → Jaccard → GRAY ----
        check("URL trùng exact → SAME", DedupRules.PairVerdict.SAME_EVENT,
                DedupRules.decidePair("https://a/x", "https://a/x", "h1", "h2",
                        "khác hẳn", "hoàn toàn không giống", 0.9, 0.5));
        check("hash trùng exact → SAME", DedupRules.PairVerdict.SAME_EVENT,
                DedupRules.decidePair("https://a/1", "https://a/2", "hh", "hh",
                        "khác hẳn", "hoàn toàn không giống", 0.9, 0.5));
        check("Jaccard 1.0 >= 0.9 → SAME", DedupRules.PairVerdict.SAME_EVENT,
                DedupRules.decidePair("https://a/1", "https://a/2", "h1", "h2",
                        "Bảo Việt ra mắt sản phẩm", "bảo việt ra mắt sản phẩm", 0.9, 0.5));
        check("Jaccard vùng xám (7/8 = 0.875) → GRAY (cần LLM)", DedupRules.PairVerdict.GRAY,
                DedupRules.decidePair("https://a/1", "https://a/2", "h1", "h2",
                        "bảo việt ra mắt sản phẩm liên kết mới",
                        "bảo việt ra mắt sản phẩm liên kết", 0.9, 0.5));
        check("Jaccard thấp → DIFFERENT", DedupRules.PairVerdict.DIFFERENT,
                DedupRules.decidePair("https://a/1", "https://a/2", "h1", "h2",
                        "tin về phí bảo hiểm", "quy định kênh phân phối mới", 0.9, 0.5));

        // ---- pickWinner: official > media · mới > cũ · cùng tier → flag ----
        check("tier 1 thắng tier 2 (official > media)", 'A',
                DedupRules.pickWinner(1, 100L, 2, 200L));
        check("tier 1 thắng kể cả khi cũ hơn", 'B',
                DedupRules.pickWinner(3, 999L, 1, 1L));
        check("cùng tier → mới thắng", 'A',
                DedupRules.pickWinner(2, 200L, 2, 100L));
        check("cùng tier, B mới hơn → B", 'B',
                DedupRules.pickWinner(2, 100L, 2, 200L));
        check("cùng tier, cùng thời gian → FLAG", 'F',
                DedupRules.pickWinner(2, 100L, 2, 100L));
        check("cùng tier, một bên thiếu publishedAt → FLAG (không đoán)", 'F',
                DedupRules.pickWinner(2, null, 2, 100L));
        check("cùng tier, cả hai thiếu publishedAt → FLAG", 'F',
                DedupRules.pickWinner(2, null, 2, null));

        // ---- parseSameEvent: parse tối giản, lỗi → null (không đoán) ----
        check("true chuẩn", Boolean.TRUE, DedupRules.parseSameEvent("{\"same_event\": true}"));
        check("false chuẩn", Boolean.FALSE, DedupRules.parseSameEvent("{\"same_event\": false}"));
        check("bọc code-fence vẫn parse", Boolean.TRUE,
                DedupRules.parseSameEvent("```json\n{\"same_event\": true}\n```"));
        check("output lạ → null (route chờ người)", null,
                DedupRules.parseSameEvent("Tôi nghĩ hai bài này giống nhau."));
        check("null → null", null, DedupRules.parseSameEvent(null));

        // ---- Tổng kết ----
        System.out.println();
        System.out.println("PASSED: " + passed + " · FAILED: " + failed);
        if (failed > 0) System.exit(1);
    }

    static void check(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) { passed++; System.out.println("  ✓ " + name); }
        else {
            failed++;
            System.out.println("  ✗ " + name + " — kỳ vọng [" + expected + "] nhận [" + actual + "]");
        }
    }
}
