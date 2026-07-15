package com.marketradar.extract;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic overlapping windows over the complete source text. Every source
 * character is covered by at least one chunk; no tail section is silently dropped.
 */
public final class LongDocumentChunker {

    public static final int DEFAULT_CHUNK_CHARS = 24_000;
    public static final int DEFAULT_OVERLAP_CHARS = 1_200;

    private LongDocumentChunker() {}

    public static Plan plan(String text) {
        return plan(text, DEFAULT_CHUNK_CHARS, DEFAULT_OVERLAP_CHARS);
    }

    public static Plan plan(String text, int chunkChars, int overlapChars) {
        if (text == null) throw new IllegalArgumentException("text is required");
        if (chunkChars <= 0) throw new IllegalArgumentException("chunkChars must be positive");
        if (overlapChars < 0 || overlapChars >= chunkChars) {
            throw new IllegalArgumentException("overlapChars must be in [0, chunkChars)");
        }
        if (text.isEmpty()) return new Plan(0, List.of(), true, 0);

        List<Chunk> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + chunkChars);
            chunks.add(new Chunk(chunks.size(), start, end, text.substring(start, end)));
            if (end == text.length()) break;
            start = end - overlapChars;
        }
        boolean complete = chunks.get(0).startInclusive() == 0
                && chunks.get(chunks.size() - 1).endExclusive() == text.length();
        for (int i = 1; i < chunks.size(); i++) {
            if (chunks.get(i).startInclusive() > chunks.get(i - 1).endExclusive()) complete = false;
        }
        int covered = complete ? text.length() : coveredCharacters(chunks);
        return new Plan(text.length(), List.copyOf(chunks), complete, covered);
    }

    private static int coveredCharacters(List<Chunk> chunks) {
        int covered = 0;
        int end = 0;
        for (Chunk chunk : chunks) {
            if (chunk.startInclusive() > end) covered += chunk.length();
            else covered += Math.max(0, chunk.endExclusive() - end);
            end = Math.max(end, chunk.endExclusive());
        }
        return covered;
    }

    public record Chunk(int index, int startInclusive, int endExclusive, String text) {
        public int length() { return endExclusive - startInclusive; }
    }

    public record Plan(int inputChars, List<Chunk> chunks, boolean completeCoverage,
                       int coveredCharacters) {
        public int chunkCount() { return chunks.size(); }
        public int silentlyDroppedCharacters() { return inputChars - coveredCharacters; }
    }
}
