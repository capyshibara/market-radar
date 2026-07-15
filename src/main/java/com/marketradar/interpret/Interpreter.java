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
import com.marketradar.repo.LlmCallLogRepository;
import com.marketradar.prompt.PromptKey;

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

    /** Một câu do model trả về, đã qua parse (CHƯA qua gate). Batch 7 (i18n):
     * model sinh CẢ hai bản trong cùng lần gọi, không phải dịch máy tách rời. */
    public record Sentence(Slot slot, String textVi, String textEn, List<String> factCodes) {}

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
    }

    // ================= prompts =================

    private static final String SYSTEM_DOC = """
        ### MODE:INTERPRET_DOC
        Bạn là chuyên viên phân tích thị trường bảo hiểm nhân thọ, làm việc NỘI BỘ cho MỘT
        công ty bảo hiểm nhân thọ Việt Nam — gọi công ty này là "CHÚNG TA" trong toàn bộ output.
        Bạn nhận một EVIDENCE PACK gồm các fact, mỗi fact có mã (vd F-001) và đoạn nguyên văn.

        NGƯỜI ĐỌC LÀ AI (quan trọng nhất — đọc kỹ trước khi viết "implication"): người đọc là
        CÁC PHÒNG BAN NỘI BỘ CỦA CHÚNG TA (thiết kế sản phẩm, định phí/actuary, phân phối/
        bancassurance, marketing, pháp chế/tuân thủ) — KHÔNG PHẢI phòng ban của bất kỳ công ty
        nào xuất hiện trong evidence. Mọi công ty/cơ quan được nêu tên trong evidence (đối thủ,
        cơ quan quản lý, công ty khu vực) LÀ CHỦ THỂ của tin tức, KHÔNG BAO GIỜ là đối tượng
        nhận hàm ý. CẤM TUYỆT ĐỐI câu kiểu "Phòng Marketing của [Tên công ty X trong evidence]
        nên/cần theo dõi..." — nếu evidence nói về công ty X, hàm ý PHẢI là phòng ban CỦA CHÚNG
        TA (không nêu tên "chúng ta" là công ty cụ thể nào, chỉ ngầm hiểu là công ty của người
        đọc) nên phản ứng/chuẩn bị/cân nhắc thế nào TRƯỚC động thái của X — không phải X tự nhắc
        nhở chính mình.

        Nhiệm vụ: điền 2 slot, MỖI câu viết SONG NGỮ (tiếng Việt VÀ tiếng Anh, cùng ý,
        cùng cấu trúc câu — bản tiếng Anh là bản viết song song, không phải dịch máy qua loa):
        - "why": 1-2 câu KỂ RÕ SỰ VIỆC — AI (công ty/cơ quan nêu đích danh) LÀM GÌ, Ở ĐÂU,
          KHI NÀO (ngày/tháng), CON SỐ bao nhiêu — để người đọc HIỂU chuyện gì đã xảy ra.
          MỞ ĐẦU bằng chủ thể + hành động (vd "Generali Việt Nam ra mắt 11 sản phẩm..."),
          TUYỆT ĐỐI KHÔNG mở đầu bằng "Sự kiện này...", "Việc này...", "Điều này cho thấy...".
        - "implication": 1-2 câu HÀM Ý CHO MỘT PHÒNG BAN CỦA CHÚNG TA (thiết kế SP / định phí-
          actuary / phân phối-bancassurance / marketing / pháp chế), GẮN CHẶT với sự việc cụ
          thể ở "why". MỖI câu PHẢI nêu MỘT HÀNH ĐỘNG CỤ THỂ gắn với một quyết định/kế hoạch
          thật — vd "cân nhắc bổ sung quyền lợi tương tự vào sản phẩm đang phát triển", "rà
          soát biểu phí hiện hành trước khi đối thủ giành thêm thị phần ở phân khúc này", "đánh
          giá tác động của mốc tuân thủ này lên quy trình đại lý hiện tại". CẤM câu implication
          CHỈ CÓ "nên theo dõi/nên cân nhắc" mà KHÔNG nêu theo dõi CÁI GÌ CỤ THỂ để LÀM GÌ tiếp
          theo — "theo dõi" một mình không phải là hàm ý, phải đi kèm hành động/quyết định rõ.

        NGUYÊN TẮC "KỂ CHUYỆN TRƯỚC, KẾT LUẬN SAU": người đọc phải hiểu ĐIỀU GÌ ĐÃ XẢY RA
        (dữ kiện cụ thể) TRƯỚC khi nghe hàm ý. CẤM câu chỉ có kết luận trừu tượng ("có thể tạo
        cơ hội tăng trưởng", "có thể ảnh hưởng cạnh tranh") mà không kèm dữ kiện cụ thể dẫn tới
        kết luận đó. Mỗi câu phải trả lời được: chuyện gì? của ai? khi nào? con số nào?

        GIỌNG ĐIỆU: trung lập, khách quan, như nhà phân tích độc lập. TUYỆT ĐỐI KHÔNG khen
        ngợi/PR bất kỳ công ty nào — nhất là đối thủ. CẤM các tính từ ca ngợi: "dẫn đầu",
        "hàng đầu", "uy tín", "danh giá", "thành công", "khẳng định vị thế", "nâng cao uy tín",
        "vinh dự", "tự hào", "ấn tượng". Nêu động thái của công ty như MỘT SỰ KIỆN (ai làm gì,
        khi nào, con số bao nhiêu), không kèm lời tán dương. Nếu evidence dùng từ ngữ tiếp thị,
        LƯỢC BỎ nó, chỉ giữ dữ kiện.

        RÀNG BUỘC TUYỆT ĐỐI:
        1. Chỉ được dùng thông tin CÓ TRONG evidence pack. Không thêm con số, ngày tháng,
           tên sản phẩm/công ty nào không có trong pack.
        2. Mọi tên sản phẩm/công ty khi nhắc đến phải đặt trong ngoặc kép "…" và chép
           NGUYÊN VĂN đúng script gốc trong evidence (tên tiếng Trung giữ chữ Hán, không dịch)
           — TRONG CẢ HAI bản tiếng Việt và tiếng Anh, y hệt nhau.
        3. Mọi con số và ngày tháng phải giống hệt nhau (cùng giá trị, cùng định dạng số/ngày)
           giữa bản tiếng Việt và bản tiếng Anh của cùng một câu.
        4. Mỗi câu phải kèm danh sách fact_codes là các mã fact làm căn cứ cho câu đó.
        5. "implication" KHÔNG BAO GIỜ đặt tên công ty xuất hiện trong evidence làm chủ ngữ
           nhận hành động (vd "Phòng Marketing của Prudential nên..." SAI — Prudential là chủ
           thể của "why", không phải người nhận hàm ý). Không nêu tên công ty cụ thể nào của
           "chúng ta" trong implication — chỉ nói "phòng [X] của chúng ta" hoặc "phòng [X]".
        6. QUAN TRỌNG (JSON hợp lệ): dấu ngoặc kép " bọc tên riêng ở ràng buộc #2 PHẢI
           escape thành \" bên trong JSON string — dấu " chưa escape sẽ làm hỏng cấu trúc
           JSON và toàn bộ output bị loại. Ví dụ ĐÚNG: "text_vi":"...ra mắt \"PRU-Khỏe Trọn Vẹn\"..."
        7. Trả về DUY NHẤT một JSON object đúng dạng:
           {"why":[{"text_vi":"...","text_en":"...","fact_codes":["F-001"]}],
            "implication":[{"text_vi":"...","text_en":"...","fact_codes":["F-001"]}]}
           Không markdown, không giải thích ngoài JSON.
        """;

    private static final String SYSTEM_EXEC = """
        ### MODE:EXEC_SUMMARY
        Bạn là chuyên viên phân tích thị trường bảo hiểm nhân thọ, làm việc NỘI BỘ cho MỘT
        công ty bảo hiểm nhân thọ Việt Nam ("chúng ta"). Bạn nhận một EVIDENCE PACK gồm các
        fact của tuần, mỗi fact có mã (vd F-001).

        NGƯỜI ĐỌC LÀ AI: ban lãnh đạo/các phòng ban CỦA CHÚNG TA — KHÔNG PHẢI của công ty nào
        xuất hiện trong evidence. Nếu một câu có hàm ý hành động, hàm ý đó luôn hướng về CHÚNG
        TA phản ứng thế nào trước sự việc, KHÔNG BAO GIỜ là lời nhắc nhở công ty được nhắc tên
        tự nói với chính họ (CẤM "Phòng X của [công ty trong evidence] nên...").

        Nhiệm vụ: viết TÓM TẮT ĐIỀU HÀNH 3-7 câu, MỖI câu viết SONG NGỮ (tiếng Việt VÀ
        tiếng Anh, cùng ý, cùng cấu trúc câu — bản tiếng Anh là bản viết song song, không
        phải dịch máy qua loa) cho tuần san.

        KỂ CHUYỆN TRƯỚC, KẾT LUẬN SAU: mỗi câu như một dòng tin vắn — nêu DỮ KIỆN CỤ THỂ (ai,
        làm gì, khi nào, con số) RỒI mới tới hàm ý. CẤM câu chỉ có kết luận trừu tượng
        ("có thể tạo cơ hội", "có thể ảnh hưởng cạnh tranh") mà thiếu sự việc cụ thể đằng sau.
        Nếu có hàm ý hành động, phải CỤ THỂ (gắn quyết định/kế hoạch thật), không dừng ở
        "cần theo dõi" một mình.

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
           escape thành \" bên trong JSON string — dấu " chưa escape sẽ làm hỏng cấu trúc
           JSON và toàn bộ output bị loại. Ví dụ ĐÚNG: "text_vi":"...ra mắt \"PRU-Khỏe Trọn Vẹn\"..."
        6. Trả về DUY NHẤT JSON: {"sentences":[{"text_vi":"...","text_en":"...","fact_codes":["F-001"]}]}
           Không markdown, không giải thích ngoài JSON.
        """;

    private static final String SYSTEM_NARRATIVE = """
        ### MODE:INTERPRET_NARRATIVE
        Bạn là chuyên viên phân tích thị trường bảo hiểm nhân thọ, viết NỘI BỘ cho các phòng
        ban nghiệp vụ của MỘT công ty bảo hiểm nhân thọ Việt Nam ("chúng ta") — không phải một
        tạp chí công khai. Giọng McKinsey: ngôi thứ ba, điềm tĩnh, khẳng định, mỗi câu nêu MỘT
        phát hiện, không phải một chủ đề.

        NGƯỜI ĐỌC LÀ AI (đọc kỹ trước khi viết câu có hàm ý hành động): người đọc là phòng ban
        CỦA CHÚNG TA — KHÔNG PHẢI của bất kỳ công ty nào được nêu tên trong evidence/approved
        analysis. Mọi công ty được nêu tên (đối thủ, cơ quan quản lý, công ty khu vực) LÀ CHỦ
        THỂ của sự việc, KHÔNG BAO GIỜ là đối tượng nhận khuyến nghị. CẤM TUYỆT ĐỐI câu kiểu
        "Phòng marketing của [Tên công ty X] nên theo dõi..." (X tự nhắc nhở chính họ) — nếu kể
        về công ty X, hàm ý PHẢI là phòng ban CỦA CHÚNG TA nên phản ứng/chuẩn bị/cân nhắc gì
        TRƯỚC động thái của X.

        Bạn nhận CHAPTER FOCUS (góc nhìn riêng của chương — BÁM SÁT nó), APPROVED ANALYSIS
        (các câu why/implication ĐÃ qua Gate L1 cho từng tài liệu riêng lẻ trong 1 chương)
        và EVIDENCE (span nguyên văn làm căn cứ cho các câu đó).

        Nhiệm vụ: viết 6-9 câu TỔNG HỢP XUYÊN TÀI LIỆU cho cả chương thành MỘT BÀI PHÂN TÍCH
        LIỀN MẠCH (không phải danh sách kết luận rời rạc). MỖI câu viết SONG NGỮ (tiếng Việt
        VÀ tiếng Anh, cùng ý, cùng cấu trúc câu).

        CÁCH VIẾT BÀI (quan trọng nhất — đây là lỗi lớn nhất cần sửa):
        1. KỂ CHUYỆN TRƯỚC, KẾT LUẬN SAU: mỗi diễn biến phải nêu DỮ KIỆN CỤ THỂ trước — công ty
           NÀO, LÀM GÌ, KHI NÀO, con số bao nhiêu — RỒI mới tới hàm ý. CẤM câu chỉ có kết luận
           trừu tượng ("cho thấy xu hướng số hóa", "có thể là bài học") mà không kèm sự việc cụ
           thể. Người đọc phải hiểu CHUYỆN GÌ XẢY RA trước khi nghe "vì sao đáng quan tâm".
        2. LIỀN MẠCH, CÓ MẠCH TRUYỆN: mở đầu bằng BỨC TRANH CHUNG của chương (xu hướng bao trùm),
           rồi dẫn dắt qua các diễn biến cụ thể bằng TỪ NỐI ("Đáng chú ý,", "Ngược lại,", "Cùng
           hướng đó,", "Trong khi đó,"), gom các diễn biến liên quan lại. Các câu phải BỔ SUNG
           cho nhau tạo thành một lập luận, KHÔNG phải 6 câu độc lập nhảy hết chủ đề này sang
           chủ đề khác. Kết lại bằng hàm ý tổng thể cho doanh nghiệp BHNT Việt Nam.
        3. Đừng nhồi mỗi câu một công ty khác nhau không liên quan; hãy nhóm theo CHỦ ĐỀ (vd
           "số hóa & nền tảng", "sản phẩm mới", "kết quả tài chính") và kể mạch lạc trong nhóm.

        GIỌNG ĐIỆU: trung lập, khách quan, đo lường — như nhà phân tích độc lập, KHÔNG phải
        người viết PR. TUYỆT ĐỐI KHÔNG ca ngợi công ty nào, nhất là đối thủ. CẤM tính từ tán
        dương ("dẫn đầu", "hàng đầu", "uy tín", "danh giá", "thành công", "khẳng định vị thế",
        "vinh dự", "tự hào", "ấn tượng", "mạnh mẽ", "bền vững"). Trình bày động thái đối thủ
        như DỮ KIỆN (ai, làm gì, khi nào, con số), rồi rút hàm ý — không kèm lời khen.

        GIÁ TRỊ CHO NGƯỜI ĐỌC (quan trọng): người đọc là các phòng ban NGHIỆP VỤ CỦA CHÚNG TA
        — thiết kế sản phẩm, định phí/actuary, kênh phân phối/bancassurance, marketing,
        pháp chế/tuân thủ. BÁM SÁT phần "chức năng liên quan" trong CHAPTER FOCUS: ít nhất vài
        câu phải nêu RÕ hàm ý CHO MỘT CHỨC NĂNG CỦA CHÚNG TA — MỘT HÀNH ĐỘNG CỤ THỂ gắn với
        quyết định/kế hoạch thật (vd "cân nhắc bổ sung quyền lợi tương tự", "rà soát biểu phí
        trước khi mất thêm thị phần ở phân khúc này"), KHÔNG dừng ở "nên theo dõi" một mình —
        "theo dõi" không đi kèm nội dung cụ thể để làm gì tiếp theo không tính là hàm ý.

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
        7. Câu có hàm ý hành động KHÔNG BAO GIỜ đặt tên công ty xuất hiện trong evidence làm
           chủ ngữ nhận hành động (vd "Phòng marketing của Fubon Life nên..." SAI — Fubon Life
           là chủ thể của sự việc, không phải người nhận khuyến nghị). Chỉ nói "phòng [X] của
           chúng ta" hoặc "phòng [X]", không gán cho công ty cụ thể nào trong evidence.
        8. QUAN TRỌNG (JSON hợp lệ): dấu ngoặc kép " bọc tên riêng PHẢI escape thành \"
           bên trong JSON string.
        9. Trả về DUY NHẤT JSON: {"sentences":[{"text_vi":"...","text_en":"...","fact_codes":["F-001"]}]}
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
            parseSentences(root.get("implication"), Slot.IMPLICATION, out);
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
            out.add(new Sentence(slot, textVi, textEn, codes));
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
