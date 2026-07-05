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
        Bạn là bộ kiểm chứng entailment độc lập. Nhiệm vụ DUY NHẤT: xét xem CLAIM
        có được các đoạn EVIDENCE (nguyên văn, có thể là tiếng Việt/Trung/Anh)
        hậu thuẫn hay không. Không suy diễn từ kiến thức ngoài evidence.
        Trả về DUY NHẤT một JSON object, không markdown, không giải thích ngoài JSON:
        {"verdict":"ENTAILED|CONTRADICTED|NEUTRAL","rationale":"1-2 câu tiếng Việt"}
        - ENTAILED: mọi nội dung factual của claim đều suy ra được từ evidence.
        - CONTRADICTED: claim mâu thuẫn với evidence.
        - NEUTRAL: evidence không đủ để khẳng định hay bác bỏ.
        """;

    private final LlmClient verifier;
    private final LlmCallLogRepository callLog;
    private final ObjectMapper mapper = new ObjectMapper();
    private final boolean replayCache;

    public EntailmentVerifier(@Qualifier("verifierLlmClient") LlmClient verifier,
                              LlmCallLogRepository callLog,
                              @Value("${marketradar.llm.replay-cache:true}") boolean replayCache) {
        this.verifier = verifier;
        this.callLog = callLog;
        this.replayCache = replayCache;
    }

    public record VerifyResult(Verdict verdict, String rationale, String rawResponse) {}

    public VerifyResult verify(String claimText, List<EvidenceFact> citedFacts) {
        String user = buildUserPrompt(claimText, citedFacts);
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
            String v = ReviewRules.normalizeVerdict(root.path("verdict").asText(""));
            Verdict verdict = Verdict.valueOf(v);
            String reason = verdict == Verdict.VERIFIER_ERROR
                    ? "Verdict ngoài enum: '" + root.path("verdict").asText("") + "'"
                    : rationale;
            return new VerifyResult(verdict, reason, raw);
        } catch (Exception e) {
            return new VerifyResult(Verdict.VERIFIER_ERROR,
                    "Output verifier không parse được JSON: " + e.getMessage(), raw);
        }
    }

    private static String buildUserPrompt(String claimText, List<EvidenceFact> citedFacts) {
        StringBuilder sb = new StringBuilder("CLAIM:\n").append(claimText).append("\n\nEVIDENCE:\n");
        for (EvidenceFact f : citedFacts) {
            sb.append("[").append(f.getFactCode()).append("] ")
              .append(f.getSpanText() == null ? "" : f.getSpanText().strip());
            if (f.getEventDate() != null) sb.append(" (eventDate: ").append(f.getEventDate()).append(')');
            sb.append('\n');
        }
        if (citedFacts.isEmpty()) sb.append("(không có evidence — claim không trích dẫn fact hợp lệ)\n");
        return sb.toString();
    }

    public String providerName() { return verifier.providerName(); }

    /** Cùng cơ chế replay-cache với Interpreter/TopicClassifier. */
    private String call(String user) {
        String hash = sha256(SYSTEM + "\n---\n" + user);
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
            String response = verifier.complete(SYSTEM, user, null);
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
