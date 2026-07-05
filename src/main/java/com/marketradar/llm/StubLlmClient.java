package com.marketradar.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Stub OFFLINE — chỉ dùng khi KHÔNG có API key, để test E2E không tốn tiền/không cần mạng.
 * Heuristic keyword thô sơ, KHÔNG phải AI — mọi kết quả từ stub được đánh dấu
 * provider=STUB trong DB để không ai nhầm là kết quả phân loại thật.
 */
public class StubLlmClient implements LlmClient {

    @Override
    public String complete(String systemPrompt, String userPrompt, Double temperature) {
        // Batch 3: stub cho Interpreter — nhận diện qua marker MODE trong system prompt.
        // Text stub cố tình KHÔNG chứa số/ngày/tên → luôn qua Gate L1 (chỉ để test luồng).
        if (systemPrompt != null && systemPrompt.contains("MODE:INTERPRET_DOC")) {
            String code = firstFactCode(userPrompt);
            return "{\"why\":[{\"text_vi\":\"[STUB] Sự kiện này đáng chú ý với thị trường đang theo dõi.\","
                 + "\"text_en\":\"[STUB] This development is notable for the market under watch.\","
                 + "\"fact_codes\":[\"" + code + "\"]}],"
                 + "\"implication\":[{\"text_vi\":\"[STUB] Cần theo dõi thêm để đánh giá hàm ý cho danh mục sản phẩm.\","
                 + "\"text_en\":\"[STUB] Further monitoring is needed to assess the implication for the product portfolio.\","
                 + "\"fact_codes\":[\"" + code + "\"]}]}";
        }
        if (systemPrompt != null && systemPrompt.contains("MODE:EXEC_SUMMARY")) {
            String code = firstFactCode(userPrompt);
            return "{\"sentences\":[{\"text_vi\":\"[STUB] Tuần qua ghi nhận các diễn biến trong phạm vi theo dõi.\","
                 + "\"text_en\":\"[STUB] This week saw developments within the tracked scope.\","
                 + "\"fact_codes\":[\"" + code + "\"]}]}";
        }
        String t = userPrompt.toLowerCase(Locale.ROOT);
        List<String> labels = new ArrayList<>();
        if (containsAny(t, "获批", "推出", "ra mắt", "phê duyệt sản phẩm", "launch"))
            labels.add("PRODUCT_LAUNCH");
        if (containsAny(t, "phí", "费率", "hoa hồng", "quyền lợi", "佣金", "điều chỉnh phí"))
            labels.add("FEE_BENEFIT_COMMISSION_CHANGE");
        if (containsAny(t, "thông tư", "nghị định", "监管", "办法", "quy định"))
            labels.add("PRODUCT_REGULATION");
        if (containsAny(t, "doanh thu", "保费收入", "doanh số", "premium income"))
            labels.add("SALES_DATA");
        if (containsAny(t, "đại lý", "banca", "bancassurance", "渠道", "kênh phân phối"))
            labels.add("DISTRIBUTION_CHANNEL");
        return "{\"labels\": [" + String.join(", ",
                labels.stream().map(l -> "\"" + l + "\"").toList()) + "]}";
    }


    /** Lấy mã fact đầu tiên xuất hiện trong prompt (vd F-001) — chỉ dùng cho stub. */
    private static String firstFactCode(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("F-\\d{3}").matcher(text);
        return m.find() ? m.group() : "F-000";
    }

    private static boolean containsAny(String text, String... keys) {
        for (String k : keys) if (text.contains(k.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    @Override
    public String providerName() { return "STUB"; }
}
