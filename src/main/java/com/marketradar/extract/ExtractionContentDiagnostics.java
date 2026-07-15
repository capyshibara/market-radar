package com.marketradar.extract;

import com.marketradar.domain.RawDoc;

import java.util.List;

/** Pure content-completeness gate: incomplete documents never consume extraction tokens. */
public final class ExtractionContentDiagnostics {

    public static final int MIN_ARTICLE_CHARS = 600;
    public static final int MAX_EXTRACT_INPUT_CHARS = 24000;

    public enum State {
        READY_STALE,
        CURRENT,
        DUPLICATE,
        PARSE_FAILED,
        EMPTY_TEXT,
        NEEDS_FULL_TEXT,
        SHORT_TEXT,
        NOT_CONFIRMED
    }

    private ExtractionContentDiagnostics() {}

    public static State assess(RawDoc doc, boolean confirmed, boolean currentVersion) {
        return assessDetailed(doc, confirmed, currentVersion).state();
    }

    public static Assessment assessDetailed(RawDoc doc, boolean confirmed, boolean currentVersion) {
        int chars = doc.getRawText() == null ? 0 : doc.getRawText().strip().length();
        if (doc.getDuplicateOfId() != null) {
            return rejected(State.DUPLICATE, "DUPLICATE_DOCUMENT",
                    "Document is a deduplicated loser; evidence must come from the canonical document.", chars);
        }
        if (doc.getParseStatus() != RawDoc.ParseStatus.OK) {
            return rejected(State.PARSE_FAILED, "PARSE_STATUS_" + doc.getParseStatus(),
                    "Parser did not produce an OK document; extraction is blocked.", chars);
        }
        if (doc.getRawText() == null || doc.getRawText().isBlank()) {
            return rejected(State.EMPTY_TEXT, "EMPTY_ARTICLE_TEXT",
                    "No article text is available; title metadata is not evidence.", 0);
        }
        if (!doc.isFullTextFetched()) {
            return rejected(State.NEEDS_FULL_TEXT, "TITLE_ONLY_OR_UNVERIFIED_FULL_TEXT",
                    "Full-text provenance is absent; title-only content fails closed.", chars);
        }
        if (chars < MIN_ARTICLE_CHARS) {
            return rejected(State.SHORT_TEXT, "ARTICLE_BELOW_MINIMUM_" + MIN_ARTICLE_CHARS,
                    "Article text is too short for evidence extraction and must be backfilled or reviewed.", chars);
        }
        if (!confirmed) {
            return rejected(State.NOT_CONFIRMED, "CLASSIFICATION_NOT_CONFIRMED",
                    "Only confirmed in-scope documents may enter evidence extraction.", chars);
        }
        LongDocumentChunker.Plan chunks = LongDocumentChunker.plan(doc.getRawText());
        if (!chunks.completeCoverage() || chunks.silentlyDroppedCharacters() != 0) {
            throw new IllegalStateException("chunk planner failed complete-coverage invariant");
        }
        return new Assessment(currentVersion ? State.CURRENT : State.READY_STALE,
                currentVersion ? "CURRENT_EXTRACTION_SIGNATURE" : "READY_FOR_VERSIONED_EXTRACTION",
                currentVersion ? "Current extraction edition already exists."
                        : "Complete article is eligible; all chunks will be processed.",
                chars, chunks.chunkCount(), chunks.coveredCharacters(), true);
    }

    private static Assessment rejected(State state, String code, String reason, int chars) {
        return new Assessment(state, code, reason, chars, 0, 0, false);
    }

    public static LengthStats summarizeLengths(List<Integer> inputLengths) {
        if (inputLengths == null || inputLengths.isEmpty()) {
            return new LengthStats(0, 0, 0, 0, 0, 0, 0.0);
        }
        List<Integer> lengths = inputLengths.stream().sorted().toList();
        long total = lengths.stream().mapToLong(Integer::longValue).sum();
        int n = lengths.size();
        int p90Index = Math.max(0, (int) Math.ceil(n * 0.90) - 1);
        long truncated = lengths.stream().filter(v -> v > MAX_EXTRACT_INPUT_CHARS).count();
        return new LengthStats(n, lengths.get(0), lengths.get(n / 2), lengths.get(p90Index),
                lengths.get(n - 1), truncated, (double) total / n);
    }

    public record LengthStats(int fullTextDocuments, int minChars, int medianChars,
                              int p90Chars, int maxChars, long inputTruncatedDocuments,
                              double averageChars) {}

    public record Assessment(State state, String reasonCode, String reason, int inputChars,
                             int chunksPlanned, int coveredCharacters,
                             boolean completeChunkCoverage) {
        public boolean eligible() { return state == State.READY_STALE; }
    }
}
