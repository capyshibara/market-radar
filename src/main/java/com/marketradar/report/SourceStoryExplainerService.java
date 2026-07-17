package com.marketradar.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.LlmCallLog;
import com.marketradar.domain.RawDoc;
import com.marketradar.domain.StoryExplainer;
import com.marketradar.llm.LlmClient;
import com.marketradar.product.BilingualTextPolicy;
import com.marketradar.prompt.PromptKey;
import com.marketradar.prompt.PromptService;
import com.marketradar.repo.EvidenceFactRepository;
import com.marketradar.repo.LlmCallLogRepository;
import com.marketradar.repo.StoryExplainerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Sinh và lưu bản viết lại có giải thích (EN + VI) cho một source story.
 *
 * <p>Một lần gọi writer cho mỗi fact code, kết quả lưu vĩnh viễn và tái sử dụng.
 * Cả hai bản viết lại phải qua {@link BilingualTextPolicy}; nếu sau một lần sửa
 * vẫn sai ngôn ngữ thì KHÔNG lưu gì — hệ thống không bịa bản dịch.</p>
 */
@Service
public class SourceStoryExplainerService {

    public static final String DEFAULT_PROMPT = """
            You retell one stored news article for a reader with no insurance background.
            Use ONLY the supplied article text. Never add a company, product, number, date,
            event or causal claim that is not in that text. This is a reading aid, not
            evidence: draw no conclusion beyond what the article itself says.

            Return exactly one JSON object with exactly these fields:
            rewriteEn, rewriteVi, termsEn, termsVi.
            rewriteEn / rewriteVi: 4-7 short sentences retelling the article in plain
            language — what happened, who did it, and why the article says it matters.
            When a technical term is unavoidable, explain it inline in plain words.
            termsEn / termsVi: JSON array of up to 6 strings, each formatted
            "term" — plain-language explanation, for jargon that appears in the article;
            keep the term itself verbatim in double quotes; empty array if none.
            rewriteEn and termsEn explanations must be entirely English prose;
            rewriteVi and termsVi explanations entirely Vietnamese prose. Proper names
            and accepted acronyms are the only exception.
            """;

    static final String PURPOSE = "STORY_EXPLAIN";
    static final int MAX_SOURCE_CHARS = 12_000;

    private final LlmClient llm;
    private final Supplier<String> prompt;
    private final EvidenceFactRepository facts;
    private final StoryExplainerRepository explainers;
    private final LlmCallLogRepository callLogs;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public SourceStoryExplainerService(LlmClient llm, PromptService prompts,
                                       EvidenceFactRepository facts,
                                       StoryExplainerRepository explainers,
                                       LlmCallLogRepository callLogs) {
        this.llm = llm;
        this.prompt = () -> prompts.body(PromptKey.STORY_EXPLAIN);
        this.facts = facts;
        this.explainers = explainers;
        this.callLogs = callLogs;
        prompts.registerDefault(PromptKey.STORY_EXPLAIN, DEFAULT_PROMPT);
    }

    @Transactional(readOnly = true)
    public Optional<StoryExplainer> find(String factCode) {
        return explainers.findByFactCode(factCode);
    }

    @Transactional
    public StoryExplainer generateIfAbsent(String factCode) {
        Optional<StoryExplainer> existing = explainers.findByFactCode(factCode);
        if (existing.isPresent()) return existing.get();
        EvidenceFact fact = facts.findAllByFactCodeInForAudit(List.of(factCode)).stream()
                .filter(candidate -> factCode.equals(candidate.getFactCode()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Source story not found: " + factCode));
        RawDoc doc = fact.getRawDoc();
        String user = buildUserPrompt(doc.getTitle(), doc.getPublisherName(),
                fact.getSpanText(), doc.getRawText());

        Parsed parsed;
        try {
            parsed = parseAndValidate(call(prompt.get(), user, doc.getId(), 0));
        } catch (ExplainerRejectedException first) {
            String repair = prompt.get() + "\n\nThe previous answer was rejected: " + first.getMessage()
                    + "\nReturn a fresh JSON object. rewriteEn must be entirely English prose and "
                    + "rewriteVi entirely Vietnamese prose; keep every statement inside the article text.";
            parsed = parseAndValidate(call(repair, user, doc.getId(), 1));
        }
        return explainers.save(new StoryExplainer(factCode, doc.getId(),
                parsed.rewriteEn(), parsed.rewriteVi(), parsed.termsEn(), parsed.termsVi(),
                llm.providerName()));
    }

    private String call(String system, String user, Long rawDocId, int sampleIndex) {
        String raw;
        long started = System.currentTimeMillis();
        try {
            raw = llm.complete(system, user, null);
        } catch (Exception e) {
            throw new ExplainerRejectedException("story explainer writer call failed: " + e.getMessage());
        }
        if (callLogs != null) {
            callLogs.save(new LlmCallLog(PURPOSE, llm.providerName(),
                    sha256(system + "\n" + user), sampleIndex, raw, rawDocId,
                    System.currentTimeMillis() - started));
        }
        return raw;
    }

    /** Pure seam: bounded article context so one story never exceeds one affordable call. */
    static String buildUserPrompt(String title, String publisher, String span, String fullText) {
        String body = fullText == null ? "" : fullText;
        if (body.length() > MAX_SOURCE_CHARS) body = body.substring(0, MAX_SOURCE_CHARS) + " …";
        return "ARTICLE TITLE: " + safe(title) + "\nPUBLISHER: " + safe(publisher)
                + "\nEVIDENCE SPAN ALREADY CITED IN THE REPORT (must stay consistent with it):\n"
                + safe(span) + "\n\nFULL STORED ARTICLE TEXT:\n" + body;
    }

    /** Pure seam: JSON schema + language purity; throws instead of storing a bad rewrite. */
    static Parsed parseAndValidate(String raw) {
        JsonNode node;
        try {
            String cleaned = raw == null ? "" : raw.strip()
                    .replaceFirst("^```(?:json)?\\s*", "").replaceFirst("```\\s*$", "").strip();
            node = MAPPER.readTree(cleaned);
        } catch (Exception e) {
            throw new ExplainerRejectedException("response was not a JSON object");
        }
        String rewriteEn = text(node, "rewriteEn");
        String rewriteVi = text(node, "rewriteVi");
        if (rewriteEn.isBlank() || rewriteVi.isBlank()) {
            throw new ExplainerRejectedException("rewriteEn and rewriteVi are both required");
        }
        if (!BilingualTextPolicy.isLikelyTargetLanguage(rewriteEn, false)) {
            throw new ExplainerRejectedException("rewriteEn contains substantial Vietnamese prose");
        }
        if (!BilingualTextPolicy.isLikelyTargetLanguage(rewriteVi, true)) {
            throw new ExplainerRejectedException("rewriteVi contains substantial English prose");
        }
        return new Parsed(rewriteEn, rewriteVi, terms(node, "termsEn"), terms(node, "termsVi"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || !value.isTextual() ? "" : value.asText().strip();
    }

    private static String terms(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isArray()) return "";
        StringBuilder out = new StringBuilder();
        for (JsonNode term : value) {
            if (!term.isTextual() || term.asText().isBlank()) continue;
            if (!out.isEmpty()) out.append('\n');
            out.append(term.asText().strip());
        }
        return out.toString();
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "—" : value.strip();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest(value.getBytes(StandardCharsets.UTF_8))) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, 64);
        } catch (Exception e) {
            return "sha-unavailable";
        }
    }

    record Parsed(String rewriteEn, String rewriteVi, String termsEn, String termsVi) {}

    /** Deterministic rejection: shown to the operator; nothing is stored. */
    public static class ExplainerRejectedException extends RuntimeException {
        public ExplainerRejectedException(String message) { super(message); }
    }
}
