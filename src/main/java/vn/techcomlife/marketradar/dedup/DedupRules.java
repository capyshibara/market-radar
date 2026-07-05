package vn.techcomlife.marketradar.dedup;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Logic THUẦN của Dedup/Conflict (Batch 5, bước 9 sequence) — ZERO import ngoài JDK.
 * Batch5LogicTest.java compile TRỰC TIẾP file này.
 *
 * Thang quyết định một CẶP tài liệu trong cửa sổ 72h (theo handoff):
 *   1. URL trùng exact / contentHash trùng exact  → SAME_EVENT (deterministic)
 *   2. Jaccard(title chuẩn hoá) >= JACCARD_SAME   → SAME_EVENT (deterministic)
 *   3. JACCARD_GRAY <= J < JACCARD_SAME           → hỏi LLM pairwise same-event
 *   4. J < JACCARD_GRAY                           → DIFFERENT
 *
 * Khi SAME_EVENT, chọn bản GIỮ theo rule xung đột (thứ tự ưu tiên):
 *   official > media  (tier NGUỒN nhỏ hơn thắng)
 *   mới > cũ          (cùng tier: publishedAt mới hơn thắng)
 *   cùng tier + không phân định được thời gian → FLAG reviewer (không đoán)
 */
public final class DedupRules {

    private DedupRules() {}

    public static final double JACCARD_SAME_DEFAULT = 0.90;
    public static final double JACCARD_GRAY_DEFAULT = 0.50;
    public static final long WINDOW_72H_MILLIS = 72L * 60 * 60 * 1000;

    // ---------- Chuẩn hoá & Jaccard ----------

    /**
     * Chuẩn hoá title để so sánh: lowercase (giữ nguyên dấu tiếng Việt — dấu là
     * thông tin phân biệt, không được gỡ), bỏ ký tự không phải chữ/số, gộp khoảng trắng.
     */
    public static String normalizeTitle(String title) {
        if (title == null) return "";
        String t = title.toLowerCase(Locale.ROOT);
        // Giữ chữ (mọi bảng chữ cái Unicode — tiếng Việt, Hán) + số; còn lại thành space
        t = t.replaceAll("[^\\p{L}\\p{Nd}]+", " ");
        return t.strip().replaceAll("\\s+", " ");
    }

    public static Set<String> titleTokens(String title) {
        String n = normalizeTitle(title);
        if (n.isEmpty()) return Set.of();
        return new HashSet<>(Arrays.asList(n.split(" ")));
    }

    /** Jaccard = |giao| / |hợp|. Hai tập rỗng → 0 (không có tín hiệu ≠ giống nhau). */
    public static double jaccard(Set<String> a, Set<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0.0;
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) inter.size() / union.size();
    }

    public static double titleJaccard(String titleA, String titleB) {
        return jaccard(titleTokens(titleA), titleTokens(titleB));
    }

    // ---------- Cửa sổ thời gian ----------

    /** millis epoch; dùng publishedAt, nguồn không có thì fallback fetchedAt (caller chọn). */
    public static boolean within72h(long epochMillisA, long epochMillisB) {
        return Math.abs(epochMillisA - epochMillisB) <= WINDOW_72H_MILLIS;
    }

    // ---------- Thang quyết định cặp ----------

    /** Bước deterministic — trả verdict hoặc GRAY (cần LLM) — KHÔNG bao giờ đoán. */
    public enum PairVerdict { SAME_EVENT, DIFFERENT, GRAY }

    public static PairVerdict decidePair(String urlA, String urlB,
                                         String hashA, String hashB,
                                         String titleA, String titleB,
                                         double jaccardSame, double jaccardGray) {
        if (urlA != null && urlA.equals(urlB)) return PairVerdict.SAME_EVENT;
        if (hashA != null && hashA.equals(hashB)) return PairVerdict.SAME_EVENT;
        double j = titleJaccard(titleA, titleB);
        if (j >= jaccardSame) return PairVerdict.SAME_EVENT;
        if (j >= jaccardGray) return PairVerdict.GRAY;
        return PairVerdict.DIFFERENT;
    }

    // ---------- Rule xung đột: chọn bản giữ ----------

    /** 'A' = giữ A · 'B' = giữ B · 'F' = flag reviewer (không tự quyết). */
    public static char pickWinner(int sourceTierA, Long publishedMillisA,
                                  int sourceTierB, Long publishedMillisB) {
        // official > media: tier nguồn NHỎ hơn = chính thống hơn → thắng
        if (sourceTierA != sourceTierB) return sourceTierA < sourceTierB ? 'A' : 'B';
        // cùng tier: mới > cũ — chỉ khi CẢ HAI có mốc thời gian và khác nhau
        if (publishedMillisA != null && publishedMillisB != null
                && !publishedMillisA.equals(publishedMillisB)) {
            return publishedMillisA > publishedMillisB ? 'A' : 'B';
        }
        // cùng tier + không phân định được → flag reviewer (fail loud, không đoán)
        return 'F';
    }

    // ---------- Parse output LLM pairwise ----------

    /**
     * Output kỳ vọng: {"same_event": true|false}. Parse tối giản không lib.
     * Bất kỳ thứ gì không match rõ ràng → NULL (caller route NEEDS_REVIEW —
     * không bao giờ quy lỗi parse về một verdict).
     */
    public static Boolean parseSameEvent(String rawLlmOutput) {
        if (rawLlmOutput == null) return null;
        String s = rawLlmOutput.strip()
                .replaceAll("(?s)^```(?:json)?", "").replaceAll("(?s)```$", "")
                .strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        if (s.contains("\"same_event\":true")) return Boolean.TRUE;
        if (s.contains("\"same_event\":false")) return Boolean.FALSE;
        return null;
    }
}
