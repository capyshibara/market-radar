package com.marketradar.classify;

import com.marketradar.domain.RawDoc;
import com.marketradar.extract.ExtractionContentDiagnostics;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Fail-closed, model-free eligibility gate for classification input.
 * Documents that can never become evidence must not consume classifier calls.
 */
public final class ClassificationInputPolicy {

    public enum Decision {
        ELIGIBLE,
        SAMPLE_DATA,
        EMPTY_TEXT,
        NEEDS_FULL_TEXT,
        SHORT_TEXT,
        DATE_UNRESOLVED,
        FUTURE_DATED,
        OUTSIDE_ANALYSIS_HORIZON,
        NON_INTELLIGENCE_CONTENT
    }

    public record Assessment(Decision decision, int inputCharacters) {
        public boolean eligible() { return decision == Decision.ELIGIBLE; }
    }

    private ClassificationInputPolicy() {}

    public static Assessment assess(RawDoc doc) {
        if (doc == null) throw new IllegalArgumentException("raw document is required");
        return assess(doc.isSampleData(), doc.isFullTextFetched(), doc.getRawText());
    }

    /**
     * Current-intelligence gate used by paid pipeline stages. Content quality is checked first,
     * then time scope and deterministic publisher-navigation noise. The raw document remains in
     * the corpus for audit; this decision only prevents an irrelevant model call.
     */
    public static Assessment assessForCurrentAnalysis(RawDoc doc, Instant now,
                                                       int maxAgeDays,
                                                       boolean requirePublicationDate) {
        if (doc == null) throw new IllegalArgumentException("raw document is required");
        Assessment content = assess(doc);
        if (!content.eligible()) return content;
        return assessCurrentMetadata(content.inputCharacters(), doc.getTitle(), doc.getUrl(),
                doc.getPublishedAt(), now, maxAgeDays, requirePublicationDate);
    }

    /** Public pure seam for regression tests and dry-run tooling. */
    public static Assessment assessCurrentMetadata(int inputCharacters, String title, String url,
                                                    Instant publishedAt, Instant now,
                                                    int maxAgeDays,
                                                    boolean requirePublicationDate) {
        if (now == null) throw new IllegalArgumentException("current time is required");
        if (maxAgeDays < 1) throw new IllegalArgumentException("maxAgeDays must be positive");
        if (publishedAt == null && requirePublicationDate) {
            return new Assessment(Decision.DATE_UNRESOLVED, inputCharacters);
        }
        if (publishedAt != null) {
            // A small clock-skew allowance is harmless; a material future date is not.
            if (publishedAt.isAfter(now.plus(Duration.ofDays(2)))) {
                return new Assessment(Decision.FUTURE_DATED, inputCharacters);
            }
            if (publishedAt.isBefore(now.minus(Duration.ofDays(maxAgeDays)))) {
                return new Assessment(Decision.OUTSIDE_ANALYSIS_HORIZON, inputCharacters);
            }
        }
        if (isDeterministicNoise(title, url)) {
            return new Assessment(Decision.NON_INTELLIGENCE_CONTENT, inputCharacters);
        }
        return new Assessment(Decision.ELIGIBLE, inputCharacters);
    }

    private static boolean isDeterministicNoise(String title, String url) {
        String normalizedUrl = value(url);
        if (normalizedUrl.contains("/health-lifestyle/")
                || normalizedUrl.contains("/lifestyle/")
                || normalizedUrl.contains("/song-khoe/")) {
            return true;
        }
        String normalizedTitle = value(title);
        return normalizedTitle.contains("thong bao nghi tet")
                || normalizedTitle.contains("thong bao nghi le")
                || normalizedTitle.contains("holiday closure notice")
                || normalizedTitle.contains("office closure notice");
    }

    private static String value(String value) {
        if (value == null) return "";
        String normalized = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return normalized.toLowerCase(Locale.ROOT).strip();
    }

    /** Public pure seam for regression tests and non-JPA planning tools. */
    public static Assessment assess(boolean sampleData, boolean fullTextFetched, String rawText) {
        int characters = rawText == null ? 0 : rawText.strip().length();
        if (sampleData) return new Assessment(Decision.SAMPLE_DATA, characters);
        if (rawText == null || rawText.isBlank()) {
            return new Assessment(Decision.EMPTY_TEXT, 0);
        }
        if (!fullTextFetched) {
            return new Assessment(Decision.NEEDS_FULL_TEXT, characters);
        }
        if (characters < ExtractionContentDiagnostics.MIN_ARTICLE_CHARS) {
            return new Assessment(Decision.SHORT_TEXT, characters);
        }
        return new Assessment(Decision.ELIGIBLE, characters);
    }
}
