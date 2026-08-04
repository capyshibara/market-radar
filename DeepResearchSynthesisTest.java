package com.marketradar.research;

import com.marketradar.llm.LlmClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Offline regression for the high-volume Deep Research preview synthesis path. */
public class DeepResearchSynthesisTest {
    public static void main(String[] args) {
        List<DeepResearchService.GatheredSource> sources = List.of(
                new DeepResearchService.GatheredSource("Source A", "https://example.com/a",
                        "OPEN_SEARCH", "Evidence excerpt A", LocalDate.of(2026, 8, 1)),
                new DeepResearchService.GatheredSource("Source B", "https://example.org/b",
                        "OPEN_SEARCH", "Evidence excerpt B", LocalDate.of(2026, 8, 2)));

        TrackingLlm valid = new TrackingLlm(validJson(15));
        DeepResearchService service = service(valid);
        List<String> progress = new ArrayList<>();
        var content = service.synthesize("Compare competitors", sources, progress::add);

        check(valid.seenBudget == DeepResearchService.SYNTHESIS_MAX_OUTPUT_TOKENS,
                "synthesis uses the dedicated output budget");
        check(content.findings().size() == DeepResearchService.MAX_SYNTHESIS_FINDINGS,
                "oversized model output is bounded to the report contract");
        check(content.findings().get(0).citations().size() == 2,
                "source_refs resolve only to gathered source citations");
        check(progress.stream().anyMatch(s -> s.contains("không phụ thuộc Max tokens chung")),
                "operator log explains the independent synthesis budget");

        TrackingLlm truncated = new TrackingLlm("{\"title\":\"cut off\",\"findings\":[");
        List<String> failedProgress = new ArrayList<>();
        var fallback = service(truncated).synthesize("Compare competitors", sources, failedProgress::add);
        check(fallback.findings().size() == sources.size(),
                "truncated JSON preserves gathered material through the raw-source fallback");
        check(failedProgress.stream().anyMatch(s -> s.contains("CẮT GIỮA CHỪNG")
                        && s.contains(String.valueOf(DeepResearchService.SYNTHESIS_MAX_OUTPUT_TOKENS))),
                "truncation is reported loudly with the dedicated budget");

        System.out.println("DeepResearchSynthesisTest: ALL PASS");
    }

    private static DeepResearchService service(LlmClient llm) {
        return new DeepResearchService(llm, null, null, null, null,
                null, null, null, null, null);
    }

    private static String validJson(int count) {
        StringBuilder out = new StringBuilder("{\"title\":\"Structured preview\",\"findings\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) out.append(',');
            out.append("{\"bucket\":\"COMPETITIVE_THEME\",\"subject_key\":\"Theme ")
                    .append(i).append("\",\"text_vi\":\"Nhận định ").append(i)
                    .append("\",\"text_en\":\"Finding ").append(i)
                    .append("\",\"highlight\":").append(i < 3)
                    .append(",\"severity\":null,\"source_refs\":[1,2]}");
        }
        return out.append("]}").toString();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError("Failed: " + message);
    }

    private static final class TrackingLlm implements LlmClient {
        private final String response;
        private int seenBudget = -1;

        private TrackingLlm(String response) { this.response = response; }

        @Override
        public String complete(String systemPrompt, String userPrompt, Double temperature) {
            throw new AssertionError("Deep Research synthesis must not use the Writer's normal budget");
        }

        @Override
        public String completeWithMaxTokens(String systemPrompt, String userPrompt,
                                            Double temperature, int maxOutputTokens) {
            seenBudget = maxOutputTokens;
            return response;
        }

        @Override
        public String providerName() { return "TEST"; }
    }
}
