package com.marketradar.interpret;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.InterpretedClaim.Slot;
import com.marketradar.domain.LlmCallLog;
import com.marketradar.llm.JsonRepair;
import com.marketradar.llm.LlmClient;
import com.marketradar.llm.LlmException;
import com.marketradar.llm.TerminalLlmException;
import com.marketradar.llm.TerminalLlmRuntimeException;
import com.marketradar.repo.LlmCallLogRepository;
import com.marketradar.prompt.PromptKey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * AI#3 — Interpreter, giai đoạn TEMPLATE-FIRST (theo lộ trình D1 3 giai đoạn).
 * Model KHÔNG viết report; model chỉ điền slot ("vì sao quan trọng" / "tóm tắt điều hành")
 * từ evidence pack, mỗi câu bắt buộc kèm fact_codes.
 *
 * Observation and analysis are separate slots. Facts can be independently
 * entailed; implications are visibly labelled and normally require human review.
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

    /** Một câu do model trả về, đã qua parse (CHƯA qua gate). Batch 7 (i18n):
     * model sinh CẢ hai bản trong cùng lần gọi, không phải dịch máy tách rời.
     * biBucket (2026-08-03): null cho tin công ty thông thường, hoặc 1 trong 5 bucket đặc biệt
     * của BiFinding (MACRO_ECONOMIC/SCHEDULED_EVENT/MARKET_SHARE_OR_AWARD/TECH_AI_SIGNAL/
     * STRATEGIC_COMPARISON) — CHỈ có ý nghĩa cho slot WHY_MATTERS, các slot khác luôn null vì
     * schema JSON của chúng không có field này. */
    public record Sentence(Slot slot, String textVi, String textEn, List<String> factCodes, String biBucket) {}

    /** Kết quả 1 lần gọi: parse OK → sentences; parse hỏng → schemaRejected + raw. */
    public record InterpretOutput(boolean schemaRejected, List<Sentence> sentences, String rawResponse) {}

    /** Captures the exact prompt/model contract and evidence input before the LLM call. */
    public record InterpretationPlan(PromptKey promptKey, String effectivePrompt,
                                     InterpretationVersioning.EditionKey editionKey) {}

    private final LlmClient llm;
    private final LlmCallLogRepository callLog;
    private final ObjectMapper mapper = new ObjectMapper();
    private final boolean replayCache;
    private final com.marketradar.prompt.PromptService promptService;

    public Interpreter(LlmClient llm, LlmCallLogRepository callLog,
                       @Value("${marketradar.llm.replay-cache:true}") boolean replayCache,
                       com.marketradar.prompt.PromptService promptService) {
        this.llm = llm;
        this.callLog = callLog;
        this.replayCache = replayCache;
        this.promptService = promptService;
        // Batch 12: công bố prompt mặc định để ops xem/sửa ở /prompts (ghi đè runtime).
        promptService.registerDefault(com.marketradar.prompt.PromptKey.INTERPRET_DOC, SYSTEM_DOC);
        promptService.registerDefault(com.marketradar.prompt.PromptKey.INTERPRET_EXEC, SYSTEM_EXEC);
        promptService.registerDefault(com.marketradar.prompt.PromptKey.INTERPRET_NARRATIVE, SYSTEM_NARRATIVE);
        promptService.registerDefault(com.marketradar.prompt.PromptKey.INTERPRET_DEEP_DIVE, SYSTEM_DEEP_DIVE);
    }

    // ================= prompts =================

    private static final String SYSTEM_DOC = """
        ### MODE:INTERPRET_DOC
        Bạn là chuyên viên phân tích thị trường bảo hiểm nhân thọ. Bạn nhận một EVIDENCE PACK
        gồm các fact, mỗi fact có mã (vd F-001) và đoạn nguyên văn.

        Tách hai lớp rõ ràng: (A) OBSERVATION là fact có thể kiểm chứng; (B) IMPLICATION là
        phân tích thận trọng về ý nghĩa. Không trộn hai lớp trong một câu. Không viết một
        khuyến nghị như mệnh lệnh quản trị; management quyết định sau human review.

        Nhiệm vụ: điền 2 slot, MỖI câu viết SONG NGỮ (tiếng Việt VÀ tiếng Anh, cùng ý,
        cùng cấu trúc câu — bản tiếng Anh là bản viết song song, không phải dịch máy qua loa):
        - "why": 1-2 câu OBSERVATION KỂ RÕ SỰ VIỆC — AI LÀM GÌ, Ở ĐÂU,
          KHI NÀO và CON SỐ bao nhiêu NẾU evidence thật sự có — để người đọc HIỂU chuyện gì
          đã xảy ra. Evidence không có ngày/số thì nêu điều khoản, phạm vi hoặc hành động cụ
          thể thay thế; TUYỆT ĐỐI không bịa ngày/số để lấp chỗ trống.
          MỞ ĐẦU bằng chủ thể + hành động (vd "Generali Việt Nam ra mắt 11 sản phẩm..."),
          TUYỆT ĐỐI KHÔNG mở đầu bằng "Sự kiện này...", "Việc này...", "Điều này cho thấy...".
        - "implications": 1 câu bắt đầu bằng "Hàm ý:" / "Implication:" nêu ý nghĩa cẩn trọng
          cho thị trường hoặc quyết định. Phải chỉ rõ chuỗi lý do fact → ý nghĩa, dùng "có thể"
          khi bằng chứng chưa đủ, và không thêm tên, số hoặc ngày mới.

        NGUYÊN TẮC: câu phải TỰ ĐỦ NGHĨA khi đọc riêng lẻ (không phụ thuộc ngữ cảnh nào khác) —
        luôn nêu rõ chủ thể/tên công ty thật, không dùng đại từ mơ hồ ("động thái này", "việc
        này") thay cho tên. Mỗi câu phải trả lời được: chuyện gì? của ai? bằng chi tiết nào
        trong evidence? Chỉ trả lời "khi nào/con số nào" khi evidence có các dữ kiện đó.
        CẤM câu chỉ có kết luận trừu tượng ("có thể tạo cơ hội tăng trưởng", "có thể ảnh hưởng
        cạnh tranh") mà không kèm dữ kiện cụ thể.

        GIỌNG ĐIỆU: trung lập, khách quan; observation như bản tin, implication như phân tích
        có điều kiện. TUYỆT ĐỐI KHÔNG khen ngợi/PR bất kỳ công ty nào — nhất là đối thủ. CẤM các tính từ
        ca ngợi: "dẫn đầu", "hàng đầu", "uy tín", "danh giá", "thành công", "khẳng định vị thế",
        "nâng cao uy tín", "vinh dự", "tự hào", "ấn tượng". Nêu động thái của công ty như MỘT SỰ
        KIỆN (ai làm gì, khi nào, con số bao nhiêu), không kèm lời tán dương. Nếu evidence dùng
        từ ngữ tiếp thị, LƯỢC BỎ nó, chỉ giữ dữ kiện.

        RÀNG BUỘC TUYỆT ĐỐI:
        1. Chỉ được dùng thông tin CÓ TRONG evidence pack. Không thêm con số, ngày tháng,
           tên sản phẩm/công ty nào không có trong pack.
        2. Mọi tên sản phẩm/công ty khi nhắc đến phải đặt trong ngoặc kép "…" và chép
           NGUYÊN VĂN đúng script gốc trong evidence (tên tiếng Trung giữ chữ Hán, không dịch)
           — TRONG CẢ HAI bản tiếng Việt và tiếng Anh, y hệt nhau.
        3. Mọi con số và ngày tháng phải giống hệt nhau (cùng giá trị, cùng định dạng số/ngày)
           giữa bản tiếng Việt và bản tiếng Anh của cùng một câu.
        4. Mỗi câu phải kèm danh sách fact_codes là các mã fact làm căn cứ cho câu đó.
        5. QUAN TRỌNG (JSON hợp lệ): dấu ngoặc kép " bọc tên riêng ở ràng buộc #2 PHẢI
           escape thành \\\" bên trong JSON string — dấu " chưa escape sẽ làm hỏng cấu trúc
           JSON và toàn bộ output bị loại. Ví dụ ĐÚNG: "text_vi":"...ra mắt \\\"PRU-Khỏe Trọn Vẹn\\\"..."
        6. Trả về DUY NHẤT một JSON object đúng dạng:
           {"why":[{"text_vi":"...","text_en":"...","fact_codes":["F-001"]}],
            "implications":[{"text_vi":"Hàm ý: ...","text_en":"Implication: ...","fact_codes":["F-001"]}]}
           Không markdown, không giải thích ngoài JSON.

        HƯỚNG DẪN RIÊNG THEO BUCKET (2026-08-03 — Router, không phải bạn, đã gán nhãn "[ROUTER]
        bucket: ..." cho từng fact trong evidence pack; KHÔNG tự phân loại lại, chỉ ĐỌC nhãn đó
        để biết viết theo phong cách nào cho fact đó — fact không có nhãn [ROUTER] thì viết theo
        phong cách mặc định ở trên, đó là tin công ty thông thường):
        - MACRO_ECONOMIC: observation nêu ĐÚNG chỉ số; implication chỉ nêu ý nghĩa nếu chuỗi
          lý do có thể truy ngược về fact, không gắn chỉ số toàn ngành cho một công ty.
        - COMPETITIVE_THEME: câu phải nêu được ĐÂY LÀ 1 PATTERN/xu hướng liên quan ≥2 công ty
          hoặc toàn ngành — không viết như tin riêng 1 công ty.
        - SCHEDULED_EVENT: câu PHẢI có ngày/khoảng ngày cụ thể của sự kiện SẮP diễn ra (không
          viết nếu evidence không có ngày rõ).
        - MARKET_SHARE_OR_AWARD: câu PHẢI có số liệu/tên giải thưởng cụ thể — từ chối viết
          chung chung kiểu "có kết quả tốt" nếu evidence không có con số/tên giải cụ thể.
        - TECH_AI_SIGNAL kèm "[ROUTER] mức độ: HIGH/MEDIUM/LOW": đây là 1 dòng AI Threat Map —
          câu PHẢI giải thích RÕ VÌ SAO ở mức độ đó (năng lực/tốc độ/quy mô cụ thể), không chỉ
          nêu sự kiện suông.
        - TECH_AI_SIGNAL có "[ROUTER] chỉ số" nhưng KHÔNG có mức độ: đây là số liệu định cỡ thị
          trường AI/insurtech chung — nêu đúng chỉ số, không gán cho 1 công ty.
        - STRATEGIC_COMPARISON: câu PHẢI là 1 SO SÁNH TRỰC TIẾP nêu tên ≥2 công ty trong cùng 1
          câu — không phải liệt kê nhiều công ty làm việc khác nhau.
        """;

    private static final String SYSTEM_EXEC = """
        ### MODE:EXEC_SUMMARY
        Bạn là chuyên viên phân tích thị trường bảo hiểm nhân thọ. Bạn nhận một EVIDENCE PACK
        đã được curate cho các kỳ báo cáo 7/30/90 ngày và lịch sự kiện sắp tới, mỗi fact có
        mã (vd F-001). Mỗi câu phải tự bám ngày của fact nó cite; không gọi một fact cũ là
        "tuần này" và không biến một sự kiện dự kiến thành sự kiện đã xảy ra.

        Mỗi mục phải tách được DỮ KIỆN và Ý NGHĨA: trước hết nêu sự việc có căn cứ, sau đó
        giải thích ngắn vì sao management cần chú ý. Ý nghĩa phải là suy luận thận trọng từ
        các fact được cite; không thêm số/tên/ngày và không viết mệnh lệnh hành động.

        Nhiệm vụ: viết TÓM TẮT ĐIỀU HÀNH 3-7 câu, MỖI câu viết SONG NGỮ (tiếng Việt VÀ
        tiếng Anh, cùng ý, cùng cấu trúc câu — bản tiếng Anh là bản viết song song, không
        phải dịch máy qua loa) cho decision brief định kỳ.

        MỖI CÂU LÀ MỘT DÒNG TIN VẮN, TỰ ĐỦ NGHĨA: nêu DỮ KIỆN CỤ THỂ (ai, làm gì, và ngày/số
        NẾU evidence có; nếu không thì dùng điều khoản/phạm vi/hành động cụ thể) — không dùng
        đại từ mơ hồ ("động thái này", "việc này") thay cho tên công ty thật và không bịa dữ kiện.
        CẤM câu chỉ có kết luận trừu tượng ("có thể tạo cơ hội", "có thể ảnh hưởng cạnh tranh")
        mà thiếu sự việc cụ thể đằng sau.

        GIỌNG ĐIỆU: trung lập, khách quan, như nhà phân tích độc lập. TUYỆT ĐỐI KHÔNG khen
        ngợi/PR bất kỳ công ty nào — nhất là đối thủ. CẤM tính từ ca ngợi ("dẫn đầu", "hàng
        đầu", "uy tín", "danh giá", "thành công", "khẳng định vị thế", "vinh dự", "ấn tượng").
        Nêu động thái như sự kiện + con số, không tán dương. Bỏ ngôn ngữ tiếp thị trong evidence.

        RÀNG BUỘC TUYỆT ĐỐI:
        1. Chỉ được dùng thông tin CÓ TRONG evidence pack. Không thêm con số, ngày tháng,
           tên sản phẩm/công ty nào không có trong pack. Không xếp hạng "bán chạy nhất"
           hay nhận định doanh số nếu pack không có fact doanh số.
        2. Tên sản phẩm/công ty đặt trong ngoặc kép "…", chép NGUYÊN VĂN đúng script gốc
           — TRONG CẢ HAI bản tiếng Việt và tiếng Anh, y hệt nhau.
        3. Mọi con số và ngày tháng phải giống hệt nhau giữa hai bản của cùng một câu.
        4. Mỗi câu kèm fact_codes.
        5. QUAN TRỌNG (JSON hợp lệ): dấu ngoặc kép " bọc tên riêng ở ràng buộc #2 PHẢI
           escape thành \\\" bên trong JSON string — dấu " chưa escape sẽ làm hỏng cấu trúc
           JSON và toàn bộ output bị loại. Ví dụ ĐÚNG: "text_vi":"...ra mắt \\\"PRU-Khỏe Trọn Vẹn\\\"..."
        6. Trả về DUY NHẤT JSON: {"sentences":[{"text_vi":"...","text_en":"...","fact_codes":["F-001"]}]}
           Không markdown, không giải thích ngoài JSON.
        """;

    private static final String SYSTEM_NARRATIVE = """
        ### MODE:INTERPRET_NARRATIVE
        Bạn là chuyên viên phân tích thị trường bảo hiểm nhân thọ — không phải một tạp chí công
        khai. Giọng McKinsey: ngôi thứ ba, điềm tĩnh, khẳng định, mỗi câu nêu MỘT phát hiện,
        không phải một chủ đề.

        Tách rõ mạch OBSERVATION → PATTERN → IMPLICATION → CAVEAT. Observation phải entail
        trực tiếp; pattern cần ít nhất 2 fact độc lập; implication là suy luận có điều kiện;
        caveat nêu điều evidence chưa chứng minh. Không biến implication thành fact hay mệnh lệnh.

        Bạn nhận CHAPTER FOCUS (góc nhìn riêng của chương — BÁM SÁT nó), APPROVED ANALYSIS
        (các câu "why" ĐÃ qua Gate L1 cho từng tài liệu riêng lẻ trong 1 chương)
        và EVIDENCE (span nguyên văn làm căn cứ cho các câu đó).

        Nhiệm vụ: viết 6-9 câu TỔNG HỢP XUYÊN TÀI LIỆU cho cả chương thành MỘT BÀI TƯỜNG THUẬT
        LIỀN MẠCH (không phải danh sách sự kiện rời rạc). MỖI câu viết SONG NGỮ (tiếng Việt
        VÀ tiếng Anh, cùng ý, cùng cấu trúc câu).

        CÁCH VIẾT BÀI (quan trọng nhất — đây là lỗi lớn nhất cần sửa):
        1. KỂ CHUYỆN CÓ DỮ KIỆN CỤ THỂ: mỗi diễn biến phải nêu công ty NÀO, LÀM GÌ, KHI NÀO, con
           số bao nhiêu. CẤM câu chỉ có kết luận trừu tượng ("cho thấy xu hướng số hóa", "có thể
           là bài học") mà không kèm sự việc cụ thể.
        2. LIỀN MẠCH, CÓ MẠCH TRUYỆN: mở đầu bằng BỨC TRANH CHUNG của chương (xu hướng bao trùm,
           chỉ nêu nếu có ≥2 fact cùng hướng — không suy diễn xu hướng từ 1 fact), rồi dẫn dắt
           qua các diễn biến cụ thể bằng TỪ NỐI ("Đáng chú ý,", "Ngược lại,", "Cùng hướng đó,",
           "Trong khi đó,"), gom các diễn biến liên quan lại. Các câu phải BỔ SUNG cho nhau tạo
           thành một mạch tường thuật, KHÔNG phải 6 câu độc lập nhảy hết chủ đề này sang chủ đề
           khác.
        3. Đừng nhồi mỗi câu một công ty khác nhau không liên quan; hãy nhóm theo CHỦ ĐỀ (vd
           "số hóa & nền tảng", "sản phẩm mới", "kết quả tài chính") và kể mạch lạc trong nhóm.

        GIỌNG ĐIỆU: trung lập, khách quan, đo lường — như một analyst nội bộ, KHÔNG phải
        người viết PR. TUYỆT ĐỐI KHÔNG ca ngợi công ty
        nào, nhất là đối thủ. CẤM tính từ tán dương ("dẫn đầu", "hàng đầu", "uy tín", "danh giá",
        "thành công", "khẳng định vị thế", "vinh dự", "tự hào", "ấn tượng", "mạnh mẽ", "bền
        vững"). Trình bày động thái các công ty như DỮ KIỆN (ai, làm gì, khi nào, con số) — không
        kèm lời khen, không kèm khuyến nghị.

        RÀNG BUỘC TUYỆT ĐỐI:
        1. Chỉ được dùng thông tin CÓ TRONG APPROVED ANALYSIS/EVIDENCE. Không thêm con số,
           ngày tháng, tên sản phẩm/công ty, hay diễn giải nào không có trong đó. Không suy
           luận xu hướng nếu chỉ có 1 fact hậu thuẫn — 1 fact không phải "xu hướng".
        2. CỤ THỂ, KHÔNG CHUNG CHUNG: mỗi câu PHẢI chứa ít nhất một chi tiết xác định lấy từ
           evidence — TÊN công ty/sản phẩm, HOẶC một con số, HOẶC một mốc ngày. CẤM những câu
           đúng-với-mọi-công-ty kiểu "cho thấy sức mạnh và sự bền vững", "nâng cao năng lực
           cạnh tranh", "tối ưu quy trình và trải nghiệm khách hàng" khi không gắn với tên/số
           thật. Ưu tiên nêu ĐÍCH DANH công ty và hành động của họ hơn là nói "nhiều doanh
           nghiệp", "các công ty".
        3. GIỮ ĐÚNG BẢN CHẤT của mỗi fact: điều khoản KHUYẾN MÃI/hậu mãi của một công ty KHÔNG
           được viết như quy định pháp lý; quy định CHỈ áp cho ngân hàng/tín dụng KHÔNG được
           viết như quy định bảo hiểm; số liệu TOÀN NGÀNH KHÔNG được gán cho một đối thủ cụ thể.
        4. Mọi tên sản phẩm/công ty khi nhắc đến phải đặt trong ngoặc kép "…" và chép
           NGUYÊN VĂN đúng script gốc trong evidence — TRONG CẢ HAI bản, y hệt nhau.
        5. Mọi con số và ngày tháng phải giống hệt nhau giữa bản tiếng Việt và tiếng Anh
           của cùng một câu.
        6. Mỗi câu phải kèm danh sách fact_codes là các mã fact (từ khối EVIDENCE) làm căn
           cứ cho câu đó — KHÔNG dùng mã claim (C-xxx) ở đây, chỉ mã fact (F-xxx).
        7. QUAN TRỌNG (JSON hợp lệ): dấu ngoặc kép " bọc tên riêng PHẢI escape thành \\\"
           bên trong JSON string.
        8. Trả về DUY NHẤT JSON: {"sentences":[{"text_vi":"...","text_en":"...","fact_codes":["F-001"]}]}
           Không markdown, không giải thích ngoài JSON.
        """;

    /**
     * 2026-08-03 — DEEP_DIVE (feedback: "Sau đó sẽ đến Analyst và Fact Checker. Chúng ta cần
     * có prompt riêng cho Analyst cho từng section!"): bucket thứ 8, do Connector đề xuất
     * (Connector#proposeDeepDiveCandidates — 1 chủ thể có đủ fact CÙNG bucket, hoặc fact đến
     * từ NHIỀU bucket khác nhau cùng nói về nó, đúng mẫu slide "Insurance Asia Awards" của CFO:
     * bảng thị phần + danh sách giải thưởng → suy ra ai vắng mặt). Khác 3 mode trên: input là
     * CLAIM ĐÃ DUYỆT (không phải fact thô), có thể đến từ NHIỀU tài liệu/bucket gốc khác nhau —
     * Analyst phải tự chọn góc phân tích phù hợp, không có khuôn cố định như 7 bucket kia.
     */
    private static final String SYSTEM_DEEP_DIVE = """
        ### MODE:INTERPRET_DEEP_DIVE
        Bạn là chuyên viên phân tích thị trường bảo hiểm nhân thọ. Bạn nhận một tập FACT đã qua
        xác thực (đã DUYỆT), TẤT CẢ cùng nói về 1 CHỦ ĐỀ — có thể đến từ nhiều tài liệu, nhiều
        bucket phân loại gốc khác nhau (vd vừa có số liệu thị phần, vừa có tin công ty).

        Nhiệm vụ: viết 3-6 câu PHÂN TÍCH SÂU (không phải liệt kê lại từng fact rời rạc) —
        TỔNG HỢP các fact thành 1 LUẬN ĐIỂM rõ ràng. Ví dụ mẫu: từ bảng thị phần + danh sách
        giải thưởng, chỉ ra công ty nào có thị phần lớn nhưng vắng mặt trong giải thưởng, rồi
        nêu khả năng vì sao (dựa CHỈ vào fact có, không suy đoán ngoài evidence).

        Cấu trúc bài: (1) observation; (2) pattern hoặc khác biệt so với benchmark;
        (3) implication cho management; (4) caveat/điều chưa biết; (5) một decision question
        nếu evidence đủ. Decision question là câu hỏi, không phải mệnh lệnh hay fact.

        NGUYÊN TẮC:
        1. CÂU MỞ ĐẦU phải nêu rõ CHỦ THỂ đang phân tích (tên công ty/chủ đề) — vì bài này
           không có tiêu đề cấu trúc riêng, câu đầu chính là câu định danh cho người đọc.
        2. Chỉ dùng dữ kiện CÓ TRONG fact — không suy đoán/thêm số liệu, tên, ngày ngoài đó.
        3. Nếu các fact có mâu thuẫn (vd 2 nguồn nêu số liệu khác nhau), PHẢI nêu rõ mâu thuẫn
           đó, không tự chọn 1 bên coi là đúng.
        4. Mọi tên sản phẩm/công ty đặt trong ngoặc kép "…", NGUYÊN VĂN đúng script gốc —
           TRONG CẢ HAI bản tiếng Việt và tiếng Anh.
        5. Mọi con số/ngày tháng giống hệt nhau giữa 2 bản ngôn ngữ của cùng 1 câu.
        6. Mỗi câu kèm fact_codes làm căn cứ.
        7. QUAN TRỌNG (JSON hợp lệ): dấu ngoặc kép " bọc tên riêng PHẢI escape thành \\"
           bên trong JSON string.
        8. Trả về DUY NHẤT JSON: {"sentences":[{"text_vi":"...","text_en":"...","fact_codes":["F-001"]}]}
           Không markdown, không giải thích ngoài JSON.
        """;

    // ================= public API =================

    public InterpretationPlan planDoc(EvidencePack pack) {
        return plan(PromptKey.INTERPRET_DOC, pack.renderForPrompt());
    }

    public InterpretationPlan planExec(EvidencePack pack) {
        return plan(PromptKey.INTERPRET_EXEC, pack.renderForPrompt());
    }

    public InterpretationPlan planNarrative(NarrativePack pack) {
        return plan(PromptKey.INTERPRET_NARRATIVE, pack.renderForPrompt());
    }

    public InterpretationPlan planDeepDive(EvidencePack pack) {
        return plan(PromptKey.INTERPRET_DEEP_DIVE, pack.renderForPrompt());
    }

    private InterpretationPlan plan(PromptKey key, String renderedInput) {
        String prompt = promptService.body(key);
        return new InterpretationPlan(key, prompt,
                InterpretationVersioning.key(llm.providerName(), key.name(), prompt, renderedInput));
    }

    public InterpretOutput interpretDoc(EvidencePack pack) {
        return interpretDoc(pack, planDoc(pack));
    }

    public InterpretOutput interpretDoc(EvidencePack pack, InterpretationPlan plan) {
        requirePlan(plan, PromptKey.INTERPRET_DOC);
        String raw = call("INTERPRET_DOC", plan.effectivePrompt(), pack.renderForPrompt(), pack.rawDocId());
        if (raw == null) return new InterpretOutput(true, List.of(), "(LLM_ERROR)");
        List<Sentence> out = new ArrayList<>();
        try {
            JsonNode root = parseWithRepairFallback(raw);
            parseSentences(root.get("why"), Slot.WHY_MATTERS, out);
            parseSentences(root.get("implications"), Slot.IMPLICATION, out);
            if (out.isEmpty()) return new InterpretOutput(true, List.of(), raw);
            return new InterpretOutput(false, out, raw);
        } catch (Exception e) {
            return new InterpretOutput(true, List.of(), raw);
        }
    }

    public InterpretOutput interpretExecSummary(EvidencePack pack) {
        return interpretExecSummary(pack, planExec(pack));
    }

    public InterpretOutput interpretExecSummary(EvidencePack pack, InterpretationPlan plan) {
        requirePlan(plan, PromptKey.INTERPRET_EXEC);
        String raw = call("INTERPRET_EXEC", plan.effectivePrompt(), pack.renderForPrompt(), null);
        if (raw == null) return new InterpretOutput(true, List.of(), "(LLM_ERROR)");
        List<Sentence> out = new ArrayList<>();
        try {
            JsonNode root = parseWithRepairFallback(raw);
            parseSentences(root.get("sentences"), Slot.EXEC_SUMMARY, out);
            if (out.isEmpty()) return new InterpretOutput(true, List.of(), raw);
            return new InterpretOutput(false, out, raw);
        } catch (Exception e) {
            return new InterpretOutput(true, List.of(), raw);
        }
    }

    /** Batch 10: tổng hợp xuyên tài liệu cho 1 chương Monthly Highlight — cùng cơ chế
     * parse/gate với interpretDoc/interpretExecSummary, khác ở prompt + Slot.NARRATIVE. */
    public InterpretOutput interpretChapterNarrative(NarrativePack pack) {
        return interpretChapterNarrative(pack, planNarrative(pack));
    }

    public InterpretOutput interpretChapterNarrative(NarrativePack pack, InterpretationPlan plan) {
        requirePlan(plan, PromptKey.INTERPRET_NARRATIVE);
        String raw = call("INTERPRET_NARRATIVE", plan.effectivePrompt(), pack.renderForPrompt(), null);
        if (raw == null) return new InterpretOutput(true, List.of(), "(LLM_ERROR)");
        List<Sentence> out = new ArrayList<>();
        try {
            JsonNode root = parseWithRepairFallback(raw);
            parseSentences(root.get("sentences"), Slot.NARRATIVE, out);
            if (out.isEmpty()) return new InterpretOutput(true, List.of(), raw);
            return new InterpretOutput(false, out, raw);
        } catch (Exception e) {
            return new InterpretOutput(true, List.of(), raw);
        }
    }

    /** DEEP_DIVE (Connector đề xuất chủ thể, xem InterpretationJob#runDeepDiveSynthesis) —
     *  cùng cơ chế parse/gate với các mode khác, khác ở prompt + Slot.DEEP_DIVE. */
    public InterpretOutput interpretDeepDive(EvidencePack pack) {
        return interpretDeepDive(pack, planDeepDive(pack));
    }

    public InterpretOutput interpretDeepDive(EvidencePack pack, InterpretationPlan plan) {
        requirePlan(plan, PromptKey.INTERPRET_DEEP_DIVE);
        String raw = call("INTERPRET_DEEP_DIVE", plan.effectivePrompt(), pack.renderForPrompt(), pack.rawDocId());
        if (raw == null) return new InterpretOutput(true, List.of(), "(LLM_ERROR)");
        List<Sentence> out = new ArrayList<>();
        try {
            JsonNode root = parseWithRepairFallback(raw);
            parseSentences(root.get("sentences"), Slot.DEEP_DIVE, out);
            if (out.isEmpty()) return new InterpretOutput(true, List.of(), raw);
            return new InterpretOutput(false, out, raw);
        } catch (Exception e) {
            return new InterpretOutput(true, List.of(), raw);
        }
    }

    /**
     * Thử parse strict trước (đường phổ biến, KHÔNG đụng vào response đã hợp lệ);
     * chỉ khi lỗi mới thử lại sau khi JsonRepair sửa dấu " chưa escape trong string.
     * Quan sát thật: prompt đã nhắc escape nhưng model vẫn thỉnh thoảng quên — cần
     * lưới an toàn này, không chỉ dựa vào prompt compliance.
     */
    private JsonNode parseWithRepairFallback(String raw) throws Exception {
        String cleaned = cleanFences(raw);
        try {
            return mapper.readTree(cleaned);
        } catch (Exception first) {
            return mapper.readTree(JsonRepair.repairUnescapedQuotes(cleaned));
        }
    }

    public String providerName() { return llm.providerName(); }

    private static void requirePlan(InterpretationPlan plan, PromptKey expected) {
        if (plan == null || plan.promptKey() != expected)
            throw new IllegalArgumentException("Interpretation plan must be for " + expected);
    }

    // ================= internals =================

    /**
     * Parse "khoan dung có kỷ luật": câu có CẢ text_vi và text_en hợp lệ được nhận vào
     * danh sách (kể cả fact_codes rỗng — để Gate L1 đánh FAIL_NO_CITATION tường minh,
     * thay vì schema-reject cả batch làm mất dấu vết câu lỗi). Batch 7 (i18n): thiếu
     * MỘT trong hai bản ngôn ngữ → bỏ câu đó (không nhận bản song ngữ thiếu một nửa —
     * cùng triết lý "fail loud" như thiếu fact_codes, chỉ khác là bỏ hẳn thay vì để gate
     * bắt, vì đây là lỗi cấu trúc output chứ không phải lỗi grounding).
     */
    /** Bucket đặc biệt hợp lệ cho bi_bucket — không dùng BiFinding.* trực tiếp để tránh phụ
     *  thuộc ngược từ interpret sang report.bi; danh sách này PHẢI khớp 5 hằng số tương ứng
     *  trong BiFinding (trừ COMPETITIVE_THEME/COMPANY_EVENT — đó là fallback mặc định, model
     *  không cần tự gán, PeriodicalBiAdapter tự chọn khi bi_bucket null). */
    private static final java.util.Set<String> VALID_BI_BUCKETS = java.util.Set.of(
            "MACRO_ECONOMIC", "SCHEDULED_EVENT", "MARKET_SHARE_OR_AWARD",
            "TECH_AI_SIGNAL", "STRATEGIC_COMPARISON");

    private void parseSentences(JsonNode arr, Slot slot, List<Sentence> out) {
        if (arr == null || !arr.isArray()) return;
        for (JsonNode n : arr) {
            String textVi = n.path("text_vi").asText("").strip();
            String textEn = n.path("text_en").asText("").strip();
            if (textVi.isEmpty() || textEn.isEmpty()) continue;
            List<String> codes = new ArrayList<>();
            JsonNode fc = n.get("fact_codes");
            if (fc != null && fc.isArray()) fc.forEach(c -> {
                String v = c.asText("").strip();
                if (!v.isEmpty()) codes.add(v);
            });
            String bucket = n.path("bi_bucket").isNull() ? null : n.path("bi_bucket").asText(null);
            if (bucket != null) {
                bucket = bucket.strip().toUpperCase(java.util.Locale.ROOT);
                if (!VALID_BI_BUCKETS.contains(bucket)) bucket = null; // giá trị lạ → rơi về mặc định, không ném lỗi
            }
            out.add(new Sentence(slot, textVi, textEn, codes, bucket));
        }
    }

    /** Gọi LLM + replay-cache qua LlmCallLog (cùng cơ chế với TopicClassifier).
     * Hash gồm llm.providerName() — fix bug đổi provider vẫn cache-hit response CŨ của
     * provider trước (phát hiện 2026-07-15, xem TopicClassifier/EntailmentVerifier). */
    private String call(String purpose, String system, String user, Long rawDocId) {
        String hash = sha256(llm.providerName() + "\n===\n" + system + "\n---\n" + user);
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
            if (e instanceof TerminalLlmException) {
                throw new TerminalLlmRuntimeException(
                        purpose + " stopped: writer provider/account cannot accept requests — "
                                + e.getMessage(), e);
            }
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
