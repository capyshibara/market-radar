package com.marketradar.classify;

import com.marketradar.domain.RawDoc;
import com.marketradar.extract.ExtractionContentDiagnostics;

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
        SHORT_TEXT
    }

    public record Assessment(Decision decision, int inputCharacters) {
        public boolean eligible() { return decision == Decision.ELIGIBLE; }
    }

    private ClassificationInputPolicy() {}

    public static Assessment assess(RawDoc doc) {
        if (doc == null) throw new IllegalArgumentException("raw document is required");
        return assess(doc.isSampleData(), doc.isFullTextFetched(), doc.getRawText());
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
