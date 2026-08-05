package com.marketradar.dedup;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Logic THUẦN của Dedup/Conflict (Batch 5, bước 9 sequence) — ZERO import ngoài JDK.
 * Batch5LogicTest.java compile TRỰC TIẾP file này.
 *
 * Thang quyết định một CẶP tài liệu trong cửa sổ 72h:
 *   1. URL/hash trùng hoặc nội dung gần như giống hệt → DUPLICATE_CONTENT
 *   2. Tiêu đề đủ giống → hỏi model đây là bản sao hay bài độc lập cùng sự kiện
 *   3. Tiêu đề khác rõ → DIFFERENT (event clustering later may still corroborate facts)
 *
 * Chỉ khi DUPLICATE_CONTENT mới chọn bản GIỮ theo rule:
 *   higher source authority score wins (independent from geography)
 *   mới > cũ          (cùng authority: publishedAt mới hơn thắng)
 *   cùng authority + không phân định được thời gian → FLAG reviewer (không đoán)
 */
public final class DedupRules {

    private DedupRules() {}

    public static final double JACCARD_SAME_DEFAULT = 0.90;
    public static final double JACCARD_GRAY_DEFAULT = 0.50;
    public static final double CONTENT_DUPLICATE_DEFAULT = 0.92;
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

    /** Strict near-copy signal. Set semantics are intentional and threshold is high;
     * lower similarity is not enough to discard potentially independent reporting. */
    public static double contentJaccard(String textA, String textB) {
        return jaccard(contentTokens(textA), contentTokens(textB));
    }

    private static Set<String> contentTokens(String text) {
        String normalized = normalizeTitle(text);
        if (normalized.isEmpty()) return Set.of();
        String[] words = normalized.split(" ");
        if (words.length < 3) return new HashSet<>(Arrays.asList(words));
        Set<String> trigrams = new HashSet<>();
        for (int i = 0; i <= words.length - 3; i++) {
            trigrams.add(words[i] + " " + words[i + 1] + " " + words[i + 2]);
        }
        return trigrams;
    }

    // ---------- Cửa sổ thời gian ----------

    /** millis epoch; dùng publishedAt, nguồn không có thì fallback fetchedAt (caller chọn). */
    public static boolean within72h(long epochMillisA, long epochMillisB) {
        return Math.abs(epochMillisA - epochMillisB) <= WINDOW_72H_MILLIS;
    }

    // ---------- Thang quyết định cặp ----------

    /** Bước deterministic — trả verdict hoặc GRAY (cần LLM) — KHÔNG bao giờ đoán. */
    public enum PairVerdict { DUPLICATE_CONTENT, DIFFERENT, GRAY }

    public static PairVerdict decidePair(String urlA, String urlB,
                                         String hashA, String hashB,
                                         String titleA, String titleB,
                                         double jaccardSame, double jaccardGray) {
        if (urlA != null && urlA.equals(urlB)) return PairVerdict.DUPLICATE_CONTENT;
        if (hashA != null && hashA.equals(hashB)) return PairVerdict.DUPLICATE_CONTENT;
        double j = titleJaccard(titleA, titleB);
        // A shared headline can be used by two independent articles. It is a
        // candidate, never sufficient evidence to discard either document.
        if (j >= jaccardSame) return PairVerdict.GRAY;
        if (j >= jaccardGray) return PairVerdict.GRAY;
        return PairVerdict.DIFFERENT;
    }

    // ---------- Rule xung đột: chọn bản giữ ----------

    /** 'A' = giữ A · 'B' = giữ B · 'F' = flag reviewer (không tự quyết). */
    public static char pickWinner(int authorityScoreA, Long publishedMillisA,
                                  int authorityScoreB, Long publishedMillisB) {
        if (authorityScoreA != authorityScoreB) return authorityScoreA > authorityScoreB ? 'A' : 'B';
        // cùng authority: mới > cũ — chỉ khi CẢ HAI có mốc thời gian và khác nhau
        if (publishedMillisA != null && publishedMillisB != null
                && !publishedMillisA.equals(publishedMillisB)) {
            return publishedMillisA > publishedMillisB ? 'A' : 'B';
        }
        // cùng authority + không phân định được → flag reviewer (fail loud, không đoán)
        return 'F';
    }

    // ---------- Parse output LLM pairwise ----------

    /**
     * Output kỳ vọng: {"same_event": true|false}. Parse tối giản không lib.
     * Bất kỳ thứ gì không match rõ ràng → NULL (caller route NEEDS_REVIEW —
     * không bao giờ quy lỗi parse về một verdict).
     */
    public enum ContentRelationship { DUPLICATE_CONTENT, SAME_EVENT_INDEPENDENT, DIFFERENT }

    public static ContentRelationship parseRelationship(String rawLlmOutput) {
        if (rawLlmOutput == null) return null;
        String s = rawLlmOutput.strip()
                .replaceAll("(?s)^```(?:json)?", "").replaceAll("(?s)```$", "")
                .strip().toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        if (s.contains("\"RELATIONSHIP\":\"DUPLICATE_CONTENT\"")) {
            return ContentRelationship.DUPLICATE_CONTENT;
        }
        if (s.contains("\"RELATIONSHIP\":\"SAME_EVENT_INDEPENDENT\"")) {
            return ContentRelationship.SAME_EVENT_INDEPENDENT;
        }
        if (s.contains("\"RELATIONSHIP\":\"DIFFERENT\"")) {
            return ContentRelationship.DIFFERENT;
        }
        return null;
    }

    /** Legacy parser retained for old standalone callers only. */
    @Deprecated(forRemoval = false)
    public static Boolean parseSameEvent(String rawLlmOutput) {
        ContentRelationship relationship = parseRelationship(rawLlmOutput);
        if (relationship == null) return null;
        return relationship != ContentRelationship.DIFFERENT;
    }
}
