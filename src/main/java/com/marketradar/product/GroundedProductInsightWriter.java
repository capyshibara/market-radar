package com.marketradar.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketradar.domain.LlmCallLog;
import com.marketradar.llm.LlmClient;
import com.marketradar.prompt.PromptKey;
import com.marketradar.prompt.PromptService;
import com.marketradar.repo.LlmCallLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Configured-writer implementation with closed JSON schema and deterministic gates. */
@Service
public class GroundedProductInsightWriter implements ProductInsightWriter {

    public static final String DEFAULT_PROMPT = """
            You are the Product Intelligence writer for a Vietnam life insurer.
            Use ONLY the supplied evidence objects. Never add a company, product, number,
            date, market claim, comparison, or causal claim absent from that evidence.
            The audience and action owner are OUR Product team. Do not recommend actions
            to a competitor or make another department the accountable owner.

            Return exactly one JSON object with exactly these fields:
            headlineVi, headlineEn, whatVi, whatEn, patternVi, patternEn,
            soWhatVi, soWhatEn, nowWhatVi, nowWhatEn, caveatVi, caveatEn, factCodes.
            factCodes must be a JSON array and may contain only supplied fact codes.

            what: verified actor/action/object only.
            `evidenceSpan` is the only source for a factual assertion. Treat article metadata,
            editorial context and routing fields as non-evidence: do not derive a launch,
            withdrawal, announcement, availability, partnership, expansion, date, number or
            named actor from them. If a span describes a feature but does not say it launched
            or was withdrawn, describe only the feature; do not supply the missing event.
            Put every company and product proper name in quotation marks, copied verbatim
            from evidence, so the deterministic grounding gate can verify it.
            pattern: comparison with the supplied peer/history evidence. With one independent
            source, explicitly say "tín hiệu đơn nguồn" / "single-source signal" and never trend.
            soWhat: decision mechanism for our Product team, not generic monitoring.
            nowWhat: start exactly "Chủ trì: Bộ phận Sản phẩm" and "Owner: Product" respectively;
            include a 30/45/60/90-day horizon and an explicit "tiêu chí" / "criterion" gate.
            caveat: strongest uncertainty or contrary/transfer constraint.
            Keep Vietnamese and English semantically aligned and concise. Each Vietnamese
            field must be Vietnamese prose; each English field must be English prose. Proper
            names and accepted acronyms are the only exception. Do not leave explanatory
            English phrases in Vietnamese fields or Vietnamese phrases in English fields.
            """;

    private static final Set<String> FIELDS = Set.of(
            "headlineVi", "headlineEn", "whatVi", "whatEn", "patternVi", "patternEn",
            "soWhatVi", "soWhatEn", "nowWhatVi", "nowWhatEn", "caveatVi", "caveatEn",
            "factCodes");
    private static final Pattern NUMBER = Pattern.compile("(?<![\\p{L}\\p{N}])\\d+(?:[.,]\\d+)?%?(?![\\p{L}\\p{N}])");

    private final LlmClient llm;
    private final Supplier<String> prompt;
    private final LlmCallLogRepository callLogs;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public GroundedProductInsightWriter(LlmClient llm, PromptService prompts,
                                        LlmCallLogRepository callLogs) {
        this.llm = llm;
        this.prompt = () -> prompts.body(PromptKey.PRODUCT_INSIGHT);
        this.callLogs = callLogs;
        prompts.registerDefault(PromptKey.PRODUCT_INSIGHT, DEFAULT_PROMPT);
    }

    /** Test seam: fake client + fixed prompt; never needs Spring or a repository. */
    public GroundedProductInsightWriter(LlmClient llm, String effectivePrompt) {
        this.llm = llm;
        this.prompt = () -> effectivePrompt;
        this.callLogs = null;
    }

    @Override
    public WrittenInsight write(ProductBriefSynthesisRules.Draft draft) {
        return write(draft, null, "PRODUCT_INSIGHT", 0);
    }

    @Override
    public WrittenInsight writeCorrected(ProductBriefSynthesisRules.Draft draft, String rejection) {
        String correction = "The previous candidate was rejected by a deterministic quality gate: "
                + compact(rejection) + "\nReturn a fresh replacement. Omit any unsupported proper name, "
                + "number, date, actor, action or causal claim; use only evidenceSpan text in the evidence pack. "
                + "Do not rely on a title, summary, source metadata or routing field. In particular, never say "
                + "a feature was launched, announced, withdrawn, available, expanded or partnered unless that "
                + "event is stated in the cited evidenceSpan. If the span only describes a feature, state only "
                + "that description. Every Vietnamese and English factual sentence must be independently entailed.";
        return write(draft, correction, "PRODUCT_INSIGHT_REPAIR", 1);
    }

    private WrittenInsight write(ProductBriefSynthesisRules.Draft draft, String correction,
                                 String purpose, int sampleIndex) {
        requireRealWriter();
        String system = prompt.get() + (correction == null ? "" : "\n\n" + correction);
        String user = renderEvidencePack(draft);
        String callHash = sha256(purpose + "\n" + llm.providerName() + "\n" + system + "\n" + user
                + "\n" + ProductInsightContract.SCHEMA_VERSION);
        String raw;
        long started = System.currentTimeMillis();
        try {
            // gpt-5 family rejects arbitrary temperature; null follows the same
            // deterministic/model-compatible convention as the verifier path.
            raw = llm.complete(system, user, null);
        } catch (Exception e) {
            throw new ProductInsightWritingException("Product insight writer failed", e);
        }
        if (callLogs != null) {
            callLogs.save(new LlmCallLog(purpose, llm.providerName(), callHash,
                    sampleIndex, raw, null, System.currentTimeMillis() - started));
        }
        return parseAndValidate(raw, draft);
    }

    @Override
    public Version version() {
        String provider = llm.providerName() == null ? "UNKNOWN" : llm.providerName();
        return new Version(provider, sha256(prompt.get()), ProductInsightContract.SCHEMA_VERSION);
    }

    private WrittenInsight parseAndValidate(String raw, ProductBriefSynthesisRules.Draft draft) {
        try {
            String cleaned = raw == null ? "" : raw.strip()
                    .replaceAll("(?s)^```(?:json)?", "")
                    .replaceAll("(?s)```$", "").strip();
            JsonNode root = mapper.readTree(cleaned);
            if (root == null || !root.isObject()) reject("response is not an object");
            Set<String> actual = new HashSet<>();
            root.fieldNames().forEachRemaining(actual::add);
            if (!actual.equals(FIELDS)) reject("response fields do not match closed schema");

            List<String> codes = new ArrayList<>();
            JsonNode codeNode = root.get("factCodes");
            if (!codeNode.isArray()) reject("factCodes must be an array");
            for (JsonNode n : codeNode) {
                if (!n.isTextual() || n.textValue().isBlank()) reject("invalid fact code");
                codes.add(n.textValue().strip());
            }
            Set<String> available = new LinkedHashSet<>(draft.factCodes());
            if (codes.isEmpty() || !available.containsAll(codes)) {
                reject("response cites unavailable evidence");
            }

            WrittenInsight out = new WrittenInsight(text(root, "headlineVi"), text(root, "headlineEn"),
                    text(root, "whatVi"), text(root, "whatEn"),
                    text(root, "patternVi"), text(root, "patternEn"),
                    text(root, "soWhatVi"), text(root, "soWhatEn"),
                    // Product-owned actions are deterministic contract copy. Keeping them out of
                    // creative model variation prevents a cosmetic wording slip from discarding
                    // otherwise sound, grounded evidence.
                    requiredText(root, "nowWhatVi", draft.nowWhatVi()),
                    requiredText(root, "nowWhatEn", draft.nowWhatEn()),
                    text(root, "caveatVi"), text(root, "caveatEn"), List.copyOf(codes));
            ProductInsightContract.Shape shape = new ProductInsightContract.Shape(
                    draft.kiqCode(), out.headlineVi(), out.headlineEn(), out.whatVi(), out.whatEn(),
                    out.patternVi(), out.patternEn(), out.soWhatVi(), out.soWhatEn(),
                    out.nowWhatVi(), out.nowWhatEn(), out.caveatVi(), out.caveatEn());
            if (!ProductInsightContract.complete(shape)) reject("required Product insight contract failed");
            BilingualTextPolicy.requireInsightLanguagePurity(out);
            validateSingleSourceLanguage(draft, out);
            validateWhatNumbers(draft, out);
            return out;
        } catch (ProductInsightWritingException e) {
            throw e;
        } catch (Exception e) {
            throw new ProductInsightWritingException("Product insight schema rejected", e);
        }
    }

    private String renderEvidencePack(ProductBriefSynthesisRules.Draft draft) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", ProductInsightContract.SCHEMA_VERSION);
        root.put("kiqCodes", ProductKiqContract.split(draft.kiqCode()));
        root.put("theme", draft.theme().name());
        root.put("deterministicConfidence", draft.confidence().name());
        root.put("editorialGuidance", Map.of(
                "nowWhatVi", draft.nowWhatVi(), "nowWhatEn", draft.nowWhatEn()));
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (ProductBriefSynthesisRules.Signal s : draft.signals()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("factCode", s.factCode());
            item.put("sourceCode", s.sourceCode());
            item.put("sourceTier", s.sourceTier());
            item.put("rawDocId", s.rawDocId());
            item.put("publishedDate", s.publishedDate() == null ? null : s.publishedDate().toString());
            item.put("company", s.company());
            item.put("productName", s.productName());
            item.put("eventType", s.eventType());
            item.put("marketScope", s.marketScope());
            item.put("modelVersion", s.modelVersion());
            item.put("pipelineVersion", s.pipelineVersion());
            item.put("clusterKey", s.clusterKey());
            item.put("clusterDocumentCount", s.clusterDocumentCount());
            item.put("clusterIndependentSourceCount", s.clusterIndependentSourceCount());
            item.put("conflictState", s.conflictState());
            item.put("effectiveDate", s.effectiveDate() == null ? null : s.effectiveDate().toString());
            item.put("expiryDate", s.expiryDate() == null ? null : s.expiryDate().toString());
            item.put("temporalStatus", s.temporalStatus());
            item.put("futureActionEligible", s.futureActionEligible());
            item.put("evidenceSpan", s.evidenceSpan());
            evidence.add(item);
        }
        root.put("evidence", evidence);
        try {
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new ProductInsightWritingException("Cannot render Product evidence pack", e);
        }
    }

    private void requireRealWriter() {
        String provider = llm.providerName();
        if (provider == null || provider.isBlank() || provider.startsWith("STUB")) {
            throw new ProductInsightWritingException(
                    "Product insight writing requires a configured non-STUB writer");
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || !n.isTextual() || n.textValue().isBlank()) reject(field + " is required");
        return n.textValue().strip();
    }

    private static String requiredText(JsonNode root, String field, String deterministicValue) {
        text(root, field); // closed schema still requires the model to acknowledge the field
        if (deterministicValue == null || deterministicValue.isBlank()) {
            reject("missing deterministic " + field + " contract");
        }
        return deterministicValue.strip();
    }

    private static void validateSingleSourceLanguage(ProductBriefSynthesisRules.Draft draft,
                                                     WrittenInsight out) {
        long sources = draft.signals().stream().map(ProductBriefSynthesisRules.Signal::sourceCode)
                .filter(Objects::nonNull).distinct().count();
        if (sources < 2 && (!out.patternVi().toLowerCase(Locale.ROOT).contains("tín hiệu đơn nguồn")
                || !out.patternEn().toLowerCase(Locale.ROOT).contains("single-source signal"))) {
            reject("single-source pattern must be labelled explicitly");
        }
    }

    private static void validateWhatNumbers(ProductBriefSynthesisRules.Draft draft,
                                            WrittenInsight out) {
        String evidence = draft.signals().stream()
                .map(s -> String.join(" ", nullToEmpty(s.title()), nullToEmpty(s.evidenceSpan()),
                        nullToEmpty(s.summaryVi()), nullToEmpty(s.summaryEn())))
                .reduce("", (a, b) -> a + " " + b);
        Set<String> supported = numbers(evidence);
        Set<String> claimed = numbers(out.whatVi() + " " + out.whatEn());
        if (!supported.containsAll(claimed)) reject("what contains an unsupported number");
    }

    private static Set<String> numbers(String value) {
        Set<String> out = new LinkedHashSet<>();
        var m = NUMBER.matcher(value == null ? "" : value);
        while (m.find()) out.add(m.group().replace(',', '.'));
        return out;
    }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }
    private static String compact(String value) {
        if (value == null || value.isBlank()) return "unspecified rejection";
        String normalized = value.replaceAll("\\s+", " ").strip();
        return normalized.length() <= 900 ? normalized : normalized.substring(0, 900) + "…";
    }
    private static void reject(String message) { throw new ProductInsightWritingException(message); }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
