package com.marketradar.classify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.marketradar.domain.Category;
import com.marketradar.domain.Classification;
import com.marketradar.domain.LlmCallLog;
import com.marketradar.domain.RawDoc;
import com.marketradar.llm.LlmClient;
import com.marketradar.llm.LlmException;
import com.marketradar.repo.LlmCallLogRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * AI#1 — Topic Classifier (multi-label, enum đóng).
 *
 * Invariant #3 (không tin verbalized confidence): confidence = self-consistency
 * vote qua N lần gọi độc lập. Nhãn được NHẬN chỉ khi đạt tối thiểu minVotes.
 * Nhãn ngoài enum / JSON hỏng → run đó bị schema-reject (không tính vote).
 * Không đủ run hợp lệ hoặc bất đồng → trạng thái *_REVIEW (fail loud).
 */
@Service
public class TopicClassifier {

    private static final Logger log = LoggerFactory.getLogger(TopicClassifier.class);
    private static final int MAX_INPUT_CHARS = 4000; // cắt input, không cắt giữa multi-byte vì substring theo char

    private final LlmClient llm;
    private final LlmCallLogRepository callLog;
    private final ObjectMapper mapper = new ObjectMapper();
    private final int samples;
    private final int minVotes;
    private final Double temperature;
    private final boolean replayCache;

    public TopicClassifier(@Qualifier("classifierLlmClient") LlmClient llm, LlmCallLogRepository callLog,
                           @Value("${marketradar.llm.samples:3}") int samples,
                           @Value("${marketradar.llm.min-votes:2}") int minVotes,
                           @Value("${marketradar.llm.temperature:1.0}") Double temperature,
                           @Value("${marketradar.llm.replay-cache:true}") boolean replayCache) {
        this.llm = llm;
        this.callLog = callLog;
        this.samples = samples;
        this.minVotes = minVotes;
        this.temperature = temperature;
        this.replayCache = replayCache;
    }

    /** Kết quả trung gian, chưa gắn routing. */
    public Classification classify(RawDoc doc) {
        String userPrompt = buildUserPrompt(doc);
        String promptHash = sha256(SYSTEM_PROMPT + "\n---\n" + userPrompt);

        Map<Category, Integer> votes = new EnumMap<>(Category.class);
        int validRuns = 0;
        int emptyRuns = 0;
        List<String> runNotes = new ArrayList<>();

        for (int i = 0; i < samples; i++) {
            String raw;
            try {
                raw = callWithCache(promptHash, i, userPrompt, doc.getId());
            } catch (LlmException e) {
                runNotes.add("run" + i + ": LLM_ERROR " + e.getMessage());
                continue;
            }
            Optional<Set<Category>> parsed = parseAndValidate(raw);
            if (parsed.isEmpty()) {
                runNotes.add("run" + i + ": SCHEMA_REJECT");
                continue;
            }
            validRuns++;
            Set<Category> labels = parsed.get();
            if (labels.isEmpty()) emptyRuns++;
            for (Category c : labels) votes.merge(c, 1, Integer::sum);
        }

        Set<Category> accepted = EnumSet.noneOf(Category.class);
        votes.forEach((c, n) -> { if (n >= minVotes) accepted.add(c); });

        Classification.Status status;
        if (validRuns < Math.min(2, samples)) {
            status = Classification.Status.UNCERTAIN_REVIEW;
        } else if (!accepted.isEmpty()) {
            status = Classification.Status.CONFIRMED;
        } else if (emptyRuns >= minVotes) {
            status = Classification.Status.OUT_OF_SCOPE;   // các run thống nhất "không thuộc scope"
        } else {
            status = Classification.Status.NO_LABEL_REVIEW; // bất đồng → review, không đoán
        }

        String votesJson = buildVotesJson(votes, validRuns, emptyRuns, runNotes);
        log.info("Classify doc#{} → {} {} (validRuns={})", doc.getId(), status, accepted, validRuns);
        return new Classification(doc, accepted, status, votesJson, llm.providerName());
    }

    // ---------- LLM call + replay cache ----------

    private String callWithCache(String promptHash, int sampleIndex, String userPrompt, Long docId)
            throws LlmException {
        if (replayCache) {
            var cached = callLog.findFirstByPromptSha256AndSampleIndexOrderByCreatedAtDesc(
                    promptHash, sampleIndex);
            if (cached.isPresent()) {
                log.debug("Replay cache hit (doc {}, sample {})", docId, sampleIndex);
                return cached.get().getResponseText();
            }
        }
        long t0 = System.currentTimeMillis();
        String response = llm.complete(SYSTEM_PROMPT, userPrompt, temperature);
        callLog.save(new LlmCallLog("CLASSIFY", llm.providerName(), promptHash,
                sampleIndex, response, docId, System.currentTimeMillis() - t0));
        return response;
    }

    // ---------- Prompt ----------

    private static final String SYSTEM_PROMPT = """
        Bạn là bộ phân loại tin tức ngành bảo hiểm nhân thọ (thị trường Việt Nam và Trung Quốc).
        Nhiệm vụ: gán 0, 1 hoặc nhiều nhãn category cho văn bản, CHỈ từ danh sách sau:
        - PRODUCT_LAUNCH: ra mắt, phê duyệt, hoặc nộp hồ sơ sản phẩm bảo hiểm mới
        - FEE_BENEFIT_COMMISSION_CHANGE: thay đổi phí, quyền lợi, hoặc hoa hồng của sản phẩm
        - PRODUCT_REGULATION: quy định pháp lý ảnh hưởng đến thiết kế/bán sản phẩm
        - SALES_DATA: số liệu doanh số, phí bảo hiểm khai thác được công bố chính thức
        - DISTRIBUTION_CHANNEL: kênh phân phối (đại lý, bancassurance, digital)

        Trả về DUY NHẤT một JSON object đúng dạng: {"labels": ["NHAN_1", "NHAN_2"]}
        Nếu văn bản không thuộc nhãn nào: {"labels": []}
        Không giải thích, không markdown, không văn bản nào khác ngoài JSON.
        """;

    private String buildUserPrompt(RawDoc doc) {
        String title = doc.getTitle() == null ? "" : doc.getTitle();
        String text = doc.getRawText() == null ? "" : doc.getRawText();
        if (text.length() > MAX_INPUT_CHARS) text = text.substring(0, MAX_INPUT_CHARS);
        return "TIÊU ĐỀ: " + title + "\n\nNỘI DUNG:\n" + text;
    }

    // ---------- Parse + schema validate (enum đóng) ----------

    private Optional<Set<Category>> parseAndValidate(String raw) {
        try {
            String cleaned = raw.strip()
                    .replaceAll("(?s)^```(?:json)?", "")
                    .replaceAll("(?s)```$", "")
                    .strip();
            JsonNode root = mapper.readTree(cleaned);
            JsonNode labels = root.get("labels");
            if (labels == null || !labels.isArray()) return Optional.empty();
            Set<Category> result = EnumSet.noneOf(Category.class);
            for (JsonNode n : labels) {
                // Nhãn ngoài enum → schema reject TOÀN BỘ run (không lọc im lặng)
                result.add(Category.valueOf(n.asText()));
            }
            return Optional.of(result);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String buildVotesJson(Map<Category, Integer> votes, int validRuns,
                                  int emptyRuns, List<String> notes) {
        ObjectNode root = mapper.createObjectNode();
        root.put("samples", samples);
        root.put("validRuns", validRuns);
        root.put("emptyRuns", emptyRuns);
        root.put("minVotes", minVotes);
        ObjectNode v = root.putObject("votes");
        votes.forEach((c, n) -> v.put(c.name(), n));
        var arr = root.putArray("runNotes");
        notes.forEach(arr::add);
        return root.toString();
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
