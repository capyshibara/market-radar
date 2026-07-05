package vn.techcomlife.marketradar.interpret;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import vn.techcomlife.marketradar.domain.EvidenceFact;
import vn.techcomlife.marketradar.domain.InterpretedClaim.Slot;
import vn.techcomlife.marketradar.domain.LlmCallLog;
import vn.techcomlife.marketradar.llm.LlmClient;
import vn.techcomlife.marketradar.llm.LlmException;
import vn.techcomlife.marketradar.repo.LlmCallLogRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * AI#3 — Interpreter, giai đoạn TEMPLATE-FIRST (theo lộ trình D1 3 giai đoạn).
 * Model KHÔNG viết report; model chỉ điền slot ("vì sao quan trọng" / "hàm ý" /
 * "tóm tắt điều hành") từ evidence pack, mỗi câu bắt buộc kèm fact_codes.
 *
 * Hợp đồng ràng buộc (bounded contract) nằm trong system prompt:
 *  - chỉ dùng thông tin có trong pack, không thêm số/ngày/tên mới;
 *  - tên sản phẩm/công ty đặt trong ngoặc kép "…" và giữ NGUYÊN VĂN script gốc
 *    (chính là thứ Gate L1 kiểm tra verbatim được);
 *  - output DUY NHẤT JSON đúng schema.
 *
 * Verifier ≠ Writer: class này KHÔNG chấm điểm output của chính nó —
 * mọi kiểm tra thuộc GroundingGateL1 (code) và lớp entailment (batch 4).
 * 1 call / doc (không self-consistency: đây là sinh văn bản, không phải phân loại;
 * độ tin nằm ở gate phía sau, không nằm ở vote).
 */
@Service
public class Interpreter {

    private static final Logger log = LoggerFactory.getLogger(Interpreter.class);

    /** Một câu do model trả về, đã qua parse (CHƯA qua gate). */
    public record Sentence(Slot slot, String text, List<String> factCodes) {}

    /** Kết quả 1 lần gọi: parse OK → sentences; parse hỏng → schemaRejected + raw. */
    public record InterpretOutput(boolean schemaRejected, List<Sentence> sentences, String rawResponse) {}

    private final LlmClient llm;
    private final LlmCallLogRepository callLog;
    private final ObjectMapper mapper = new ObjectMapper();
    private final boolean replayCache;

    public Interpreter(LlmClient llm, LlmCallLogRepository callLog,
                       @Value("${marketradar.llm.replay-cache:true}") boolean replayCache) {
        this.llm = llm;
        this.callLog = callLog;
        this.replayCache = replayCache;
    }

    // ================= prompts =================

    private static final String SYSTEM_DOC = """
        ### MODE:INTERPRET_DOC
        Bạn là chuyên viên phân tích thị trường cho Techcom Life (bảo hiểm nhân thọ, Việt Nam).
        Bạn nhận một EVIDENCE PACK gồm các fact, mỗi fact có mã (vd F-001) và đoạn nguyên văn.

        Nhiệm vụ: điền 2 slot, bằng tiếng Việt:
        - "why": 1-2 câu "vì sao sự kiện này đáng chú ý"
        - "implication": 1-2 câu "hàm ý cho Techcom Life"

        RÀNG BUỘC TUYỆT ĐỐI:
        1. Chỉ được dùng thông tin CÓ TRONG evidence pack. Không thêm con số, ngày tháng,
           tên sản phẩm/công ty nào không có trong pack.
        2. Mọi tên sản phẩm/công ty khi nhắc đến phải đặt trong ngoặc kép "…" và chép
           NGUYÊN VĂN đúng script gốc trong evidence (tên tiếng Trung giữ chữ Hán, không dịch).
        3. Mỗi câu phải kèm danh sách fact_codes là các mã fact làm căn cứ cho câu đó.
        4. Trả về DUY NHẤT một JSON object đúng dạng:
           {"why":[{"text":"...","fact_codes":["F-001"]}],"implication":[{"text":"...","fact_codes":["F-001"]}]}
           Không markdown, không giải thích ngoài JSON.
        """;

    private static final String SYSTEM_EXEC = """
        ### MODE:EXEC_SUMMARY
        Bạn là chuyên viên phân tích thị trường cho Techcom Life (bảo hiểm nhân thọ, Việt Nam).
        Bạn nhận một EVIDENCE PACK gồm các fact của tuần, mỗi fact có mã (vd F-001).

        Nhiệm vụ: viết TÓM TẮT ĐIỀU HÀNH 3-7 câu tiếng Việt cho tuần san.

        RÀNG BUỘC TUYỆT ĐỐI:
        1. Chỉ được dùng thông tin CÓ TRONG evidence pack. Không thêm con số, ngày tháng,
           tên sản phẩm/công ty nào không có trong pack. Không xếp hạng "bán chạy nhất"
           hay nhận định doanh số nếu pack không có fact doanh số.
        2. Tên sản phẩm/công ty đặt trong ngoặc kép "…", chép NGUYÊN VĂN đúng script gốc.
        3. Mỗi câu kèm fact_codes.
        4. Trả về DUY NHẤT JSON: {"sentences":[{"text":"...","fact_codes":["F-001"]}]}
           Không markdown, không giải thích ngoài JSON.
        """;

    // ================= public API =================

    public InterpretOutput interpretDoc(EvidencePack pack) {
        String raw = call("INTERPRET_DOC", SYSTEM_DOC, pack.renderForPrompt(), pack.rawDocId());
        if (raw == null) return new InterpretOutput(true, List.of(), "(LLM_ERROR)");
        List<Sentence> out = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(cleanFences(raw));
            parseSentences(root.get("why"), Slot.WHY_MATTERS, out);
            parseSentences(root.get("implication"), Slot.IMPLICATION, out);
            if (out.isEmpty()) return new InterpretOutput(true, List.of(), raw);
            return new InterpretOutput(false, out, raw);
        } catch (Exception e) {
            return new InterpretOutput(true, List.of(), raw);
        }
    }

    public InterpretOutput interpretExecSummary(EvidencePack pack) {
        String raw = call("INTERPRET_EXEC", SYSTEM_EXEC, pack.renderForPrompt(), null);
        if (raw == null) return new InterpretOutput(true, List.of(), "(LLM_ERROR)");
        List<Sentence> out = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(cleanFences(raw));
            parseSentences(root.get("sentences"), Slot.EXEC_SUMMARY, out);
            if (out.isEmpty()) return new InterpretOutput(true, List.of(), raw);
            return new InterpretOutput(false, out, raw);
        } catch (Exception e) {
            return new InterpretOutput(true, List.of(), raw);
        }
    }

    public String providerName() { return llm.providerName(); }

    // ================= internals =================

    /**
     * Parse "khoan dung có kỷ luật": câu có text hợp lệ được nhận vào danh sách
     * (kể cả fact_codes rỗng — để Gate L1 đánh FAIL_NO_CITATION tường minh,
     * thay vì schema-reject cả batch làm mất dấu vết câu lỗi).
     */
    private void parseSentences(JsonNode arr, Slot slot, List<Sentence> out) {
        if (arr == null || !arr.isArray()) return;
        for (JsonNode n : arr) {
            String text = n.path("text").asText("").strip();
            if (text.isEmpty()) continue;
            List<String> codes = new ArrayList<>();
            JsonNode fc = n.get("fact_codes");
            if (fc != null && fc.isArray()) fc.forEach(c -> {
                String v = c.asText("").strip();
                if (!v.isEmpty()) codes.add(v);
            });
            out.add(new Sentence(slot, text, codes));
        }
    }

    /** Gọi LLM + replay-cache qua LlmCallLog (cùng cơ chế với TopicClassifier). */
    private String call(String purpose, String system, String user, Long rawDocId) {
        String hash = sha256(system + "\n---\n" + user);
        if (replayCache) {
            var cached = callLog.findFirstByPromptSha256AndSampleIndexOrderByCreatedAtDesc(hash, 0);
            if (cached.isPresent()) {
                log.debug("Replay cache hit ({}, doc {})", purpose, rawDocId);
                return cached.get().getResponseText();
            }
        }
        long t0 = System.currentTimeMillis();
        try {
            // temperature=null: không gửi (sinh văn bản 1 lần, không cần đa dạng self-consistency)
            String response = llm.complete(system, user, null);
            callLog.save(new LlmCallLog(purpose, llm.providerName(), hash, 0,
                    response, rawDocId, System.currentTimeMillis() - t0));
            return response;
        } catch (LlmException e) {
            log.error("{} lỗi LLM (doc {}): {}", purpose, rawDocId, e.getMessage());
            return null;
        }
    }

    private static String cleanFences(String raw) {
        return raw.strip()
                .replaceAll("(?s)^```(?:json)?", "")
                .replaceAll("(?s)```$", "")
                .strip();
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
