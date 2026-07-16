package com.marketradar.product;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Small, deliberately conservative language guard for reader-facing generated
 * copy. It is not a translation service: original titles and evidence remain
 * untouched. The goal is to stop a whole sentence in the wrong language from
 * being published under a Vietnamese or English label.
 */
public final class BilingualTextPolicy {
    private static final Pattern QUOTED = Pattern.compile("[\"“][^\"”]{1,240}[\"”]");
    private static final Pattern WORD = Pattern.compile("[\\p{L}]+(?:-[\\p{L}]+)?");
    private static final Set<String> ENGLISH_PROSE = Set.of(
            "the", "and", "for", "with", "from", "that", "this", "should", "within", "owner",
            "criterion", "source", "signal", "market", "customer", "innovation", "digital",
            "distribution", "engagement", "regulatory", "screen", "unit", "economics", "launch",
            "launches", "insurer", "plan", "benefit", "pricing", "process", "portfolio", "validate",
            "evidence", "current", "development", "change", "requires", "review", "new", "health",
            "insurance", "assessment", "decision", "research", "prototype", "roadmap", "legal");
    private static final Set<String> VIETNAMESE_PROSE = Set.of(
            "và", "của", "là", "cần", "để", "trong", "với", "được", "từ", "nguồn", "tín", "hiệu",
            "sản", "phẩm", "khách", "hàng", "thị", "trường", "bằng", "chứng", "kiểm", "tra", "đánh",
            "giá", "điều", "chỉnh", "quy", "định", "công", "bố", "doanh", "nghiệp", "ngày", "tháng");

    private BilingualTextPolicy() {}

    /** Returns blank when a stored display summary visibly belongs to the wrong language. */
    public static String safeDisplaySummary(String value, boolean vietnamese) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.strip();
        return isLikelyTargetLanguage(normalized, vietnamese) ? normalized : "";
    }

    public static boolean isLikelyTargetLanguage(String value, boolean vietnamese) {
        if (value == null || value.isBlank()) return false;
        String unquoted = QUOTED.matcher(value).replaceAll(" ");
        int oppositeWords = vocabularyHits(unquoted, vietnamese ? ENGLISH_PROSE : VIETNAMESE_PROSE);
        // A single shared technical word is normal; a cluster is prose leakage.
        if (oppositeWords >= 3) return false;
        if (!vietnamese && containsVietnameseDiacritic(unquoted) && oppositeWords >= 1) return false;
        return true;
    }

    /** Throws a writer exception through the caller when multilingual fields are swapped. */
    public static void requireInsightLanguagePurity(ProductInsightWriter.WrittenInsight insight) {
        require(insight.headlineVi(), true, "headlineVi");
        require(insight.whatVi(), true, "whatVi");
        require(insight.patternVi(), true, "patternVi");
        require(insight.soWhatVi(), true, "soWhatVi");
        require(insight.nowWhatVi(), true, "nowWhatVi");
        require(insight.caveatVi(), true, "caveatVi");
        require(insight.headlineEn(), false, "headlineEn");
        require(insight.whatEn(), false, "whatEn");
        require(insight.patternEn(), false, "patternEn");
        require(insight.soWhatEn(), false, "soWhatEn");
        require(insight.nowWhatEn(), false, "nowWhatEn");
        require(insight.caveatEn(), false, "caveatEn");
    }

    private static void require(String value, boolean vietnamese, String field) {
        if (!isLikelyTargetLanguage(value, vietnamese)) {
            throw new ProductInsightWritingException(field + " contains substantial "
                    + (vietnamese ? "English" : "Vietnamese") + " prose");
        }
    }

    private static int vocabularyHits(String value, Set<String> vocabulary) {
        int hits = 0;
        var matcher = WORD.matcher(value.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            if (vocabulary.contains(matcher.group())) hits++;
        }
        return hits;
    }

    private static boolean containsVietnameseDiacritic(String value) {
        return value != null && value.matches("(?s).*?[ăâđêôơưĂÂĐÊÔƠƯà-ỹÀ-Ỹ].*");
    }
}
