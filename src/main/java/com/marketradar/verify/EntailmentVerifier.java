package com.marketradar.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.marketradar.domain.ClaimVerification.Verdict;
import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.LlmCallLog;
import com.marketradar.llm.LlmClient;
import com.marketradar.llm.LlmException;
import com.marketradar.repo.LlmCallLogRepository;
import com.marketradar.review.ReviewRules;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Gate L2 — entailment bằng LLM KHÁC HỌ với writer (Option A theo D2 Mục 8,
 * chấp nhận cho MVP vì thiếu NLI tiếng Việt domain pháp lý-tài chính).
 *
 * Cách ly persona (bài học D2): verifier CHỈ thấy
 *   (a) MỘT claim cô lập, (b) evidence span nguyên văn của các fact được trích.
 * KHÔNG thấy toàn bài, không thấy prompt của interpreter, không biết ai viết.
 *
 * Output bắt buộc JSON {"verdict": ENTAILED|CONTRADICTED|NEUTRAL, "rationale": "..."}.
 * Parse lỗi / verdict lạ / API lỗi → VERIFIER_ERROR (không bao giờ quy về pass).
 */
@Service
public class EntailmentVerifier {

    private static final Logger log = LoggerFactory.getLogger(EntailmentVerifier.class);

    private static final String SYSTEM = """
        Bạn là bộ kiểm chứng entailment độc lập. Nhiệm vụ DUY NHẤT: xét xem
        CẢ CLAIM_VI VÀ CLAIM_EN có được các đoạn EVIDENCE nguyên văn hậu thuẫn hay
        không, và hai bản ngôn ngữ có cùng nội dung factual hay không. Không dùng kiến
        thức ngoài evidence.
        Trả về DUY NHẤT một JSON object, không markdown:
        {"verdict_vi":"ENTAILED|CONTRADICTED|NEUTRAL",
         "verdict_en":"ENTAILED|CONTRADICTED|NEUTRAL",
         "translation_consistent":true|false,
         "rationale":"1-2 câu tiếng Việt"}
        - ENTAILED: mọi nội dung factual của claim đều suy ra được từ evidence.
        - CONTRADICTED: claim mâu thuẫn với evidence.
        - NEUTRAL: evidence không đủ để khẳng định hay bác bỏ.
        Nếu translation_consistent=false, verdict tổng hợp sẽ không được auto-publish.
        """;

    private final LlmClient verifier;
    private final LlmCallLogRepository callLog;
    private final ObjectMapper mapper = new ObjectMapper();
    private final boolean replayCache;
    private final com.marketradar.prompt.PromptService promptService;

    public EntailmentVerifier(@Qualifier("verifierLlmClient") LlmClient verifier,
                              LlmCallLogRepository callLog,
                              @Value("${marketradar.llm.replay-cache:true}") boolean replayCache,
                              com.marketradar.prompt.PromptService promptService) {
        this.verifier = verifier;
        this.callLog = callLog;
        this.replayCache = replayCache;
        this.promptService = promptService;
        promptService.registerDefault(com.marketradar.prompt.PromptKey.VERIFY, SYSTEM);
    }

    public record VerifyResult(Verdict verdict, String rationale, String rawResponse) {}

    public VerifyResult verify(String claimText, List<EvidenceFact> citedFacts) {
        return verifyBilingual(claimText, claimText, citedFacts);
    }

    public VerifyResult verifyBilingual(String claimVi, String claimEn,
                                        List<EvidenceFact> citedFacts) {
        String user = buildUserPrompt(claimVi, claimEn, citedFacts);
        String raw = call(user);
        if (raw == null) {
            return new VerifyResult(Verdict.VERIFIER_ERROR,
                    "Verifier API lỗi — không có response.", null);
        }
        return parse(raw);
    }

    /** Public để test standalone không cần Spring/API. */
    public VerifyResult parse(String raw) {
        try {
            String clean = ReviewRules.stripCodeFences(raw);
            JsonNode root = mapper.readTree(clean);
            String rationale = root.path("rationale").asText("").strip();
            // Old single-language cached/overridden responses remain readable.
            String single = root.path("verdict").asText(null);
            Verdict verdict;
            if (single != null) {
                verdict = Verdict.valueOf(ReviewRules.normalizeVerdict(single));
            } else {
                Verdict vi = Verdict.valueOf(ReviewRules.normalizeVerdict(root.path("verdict_vi").asText("")));
                Verdict en = Verdict.valueOf(ReviewRules.normalizeVerdict(root.path("verdict_en").asText("")));
                boolean consistent = root.path("translation_consistent").asBoolean(false);
                verdict = aggregate(vi, en, consistent);
                if (!consistent) rationale = append(rationale, "Hai bản VI/EN không nhất quán.");
            }
            String reason = verdict == Verdict.VERIFIER_ERROR ? "Verifier returned an invalid verdict." : rationale;
            return new VerifyResult(verdict, reason, raw);
        } catch (Exception e) {
            return new VerifyResult(Verdict.VERIFIER_ERROR,
                    "Output verifier không parse được JSON: " + e.getMessage(), raw);
        }
    }

    private static String buildUserPrompt(String claimVi, String claimEn, List<EvidenceFact> citedFacts) {
        StringBuilder sb = new StringBuilder("CLAIM_VI:\n").append(claimVi)
                .append("\n\nCLAIM_EN:\n").append(claimEn).append("\n\nEVIDENCE:\n");
        for (EvidenceFact f : citedFacts) {
            sb.append("[").append(f.getFactCode()).append("] ")
              .append(f.getSpanText() == null ? "" : f.getSpanText().strip());
            if (f.getEventDate() != null) sb.append(" (eventDate: ").append(f.getEventDate()).append(')');
            sb.append('\n');
        }
        if (citedFacts.isEmpty()) sb.append("(không có evidence — claim không trích dẫn fact hợp lệ)\n");
        return sb.toString();
    }

    private static Verdict aggregate(Verdict vi, Verdict en, boolean consistent) {
        if (vi == Verdict.CONTRADICTED || en == Verdict.CONTRADICTED) return Verdict.CONTRADICTED;
        if (vi == Verdict.VERIFIER_ERROR || en == Verdict.VERIFIER_ERROR) return Verdict.VERIFIER_ERROR;
        if (!consistent || vi == Verdict.NEUTRAL || en == Verdict.NEUTRAL) return Verdict.NEUTRAL;
        return Verdict.ENTAILED;
    }

    private static String append(String first, String second) {
        return first == null || first.isBlank() ? second : first + " " + second;
    }

    public String providerName() { return verifier.providerName(); }

    /** Cùng cơ chế replay-cache với Interpreter/TopicClassifier. Hash gồm
     * verifier.providerName() — fix bug 2026-07-15: đổi verifier STUB → provider thật
     * vẫn cache-hit trúng response STUB cũ (prompt text giống hệt, hash cũ không phân
     * biệt provider) → ClaimVerification ghi nhầm verdict/rationale của STUB dưới tên
     * provider thật. */
    private String call(String user) {
        String system = promptService.body(com.marketradar.prompt.PromptKey.VERIFY);
        String hash = sha256(verifier.providerName() + "\n===\n" + system + "\n---\n" + user);
        if (replayCache) {
            var cached = callLog.findFirstByPromptSha256AndSampleIndexOrderByCreatedAtDesc(hash, 0);
            if (cached.isPresent()) {
                log.debug("Replay cache hit (VERIFY)");
                return cached.get().getResponseText();
            }
        }
        long t0 = System.currentTimeMillis();
        try {
            // temperature=null: entailment cần deterministic nhất có thể, không cần đa dạng
            String response = verifier.complete(system, user, null);
            callLog.save(new LlmCallLog("VERIFY", verifier.providerName(), hash, 0,
                    response, null, System.currentTimeMillis() - t0));
            return response;
        } catch (LlmException e) {
            log.error("VERIFY lỗi LLM: {}", e.getMessage());
            return null;
        }
    }

    private static String sha256(String s) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
