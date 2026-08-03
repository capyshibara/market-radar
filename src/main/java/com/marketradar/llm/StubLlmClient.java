package com.marketradar.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stub OFFLINE — chỉ dùng khi KHÔNG có API key, để test E2E không tốn tiền/không cần mạng.
 * Heuristic keyword thô sơ, KHÔNG phải AI — mọi kết quả từ stub được đánh dấu
 * provider=STUB trong DB để không ai nhầm là kết quả phân loại thật.
 */
public class StubLlmClient implements LlmClient {

    private final ObjectMapper mapper = new ObjectMapper();

    /** Bản tool-calling của nhánh MODE:DEEP_RESEARCH_PLAN ở complete() bên dưới — cùng logic
     *  (tìm 1 vòng cho đủ nguồn demo rồi dừng), chỉ khác chỗ trả thẳng ToolChoice đã cấu trúc
     *  thay vì chuỗi JSON để không lệch hành vi với client thật khi DeepResearchService chạy
     *  qua completeWithTools(). */
    @Override
    public ToolChoice completeWithTools(String systemPrompt, String userPrompt,
                                        List<LlmTool> tools, Double temperature) {
        if (systemPrompt != null && systemPrompt.contains("MODE:DEEP_RESEARCH_PLAN")) {
            Matcher gm = Pattern.compile("Số nguồn đã thu thập: (\\d+)").matcher(userPrompt);
            int gatheredCount = gm.find() ? Integer.parseInt(gm.group(1)) : 0;
            ObjectNode args = mapper.createObjectNode();
            if (gatheredCount == 0) {
                Matcher qm = Pattern.compile("YÊU CẦU GỐC: (.*?)\\n---", Pattern.DOTALL).matcher(userPrompt);
                String query = qm.find() ? qm.group(1).strip() : "thông tin thị trường";
                args.put("query", query);
                args.put("reason", "[STUB] tìm nguồn mở cho yêu cầu ban đầu");
                return new ToolChoice("search", args);
            }
            args.put("reason", "[STUB] đã có nguồn tham khảo, dừng vòng lặp demo");
            return new ToolChoice("stop", args);
        }
        throw new UnsupportedOperationException("StubLlmClient.completeWithTools chỉ hỗ trợ MODE:DEEP_RESEARCH_PLAN");
    }

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
        // Deep Research (agent lặp): planner KHÔNG có state riêng, phải đọc lại "Số nguồn đã thu
        // thập: N" mà DeepResearchService tự in vào userPrompt để biết đang ở bước nào — tìm 1
        // vòng cho đủ nguồn demo rồi dừng, tránh vòng lặp vô hạn khi offline.
        if (systemPrompt != null && systemPrompt.contains("MODE:DEEP_RESEARCH_PLAN")) {
            java.util.regex.Matcher gm = java.util.regex.Pattern.compile("Số nguồn đã thu thập: (\\d+)").matcher(userPrompt);
            int gatheredCount = gm.find() ? Integer.parseInt(gm.group(1)) : 0;
            if (gatheredCount == 0) {
                java.util.regex.Matcher qm = java.util.regex.Pattern.compile("YÊU CẦU GỐC: (.*?)\\n---", java.util.regex.Pattern.DOTALL).matcher(userPrompt);
                String query = qm.find() ? qm.group(1).strip() : "thông tin thị trường";
                return "{\"action\":\"SEARCH\",\"target\":\"" + escapeJson(query)
                     + "\",\"reason\":\"[STUB] tìm nguồn mở cho yêu cầu ban đầu\"}";
            }
            return "{\"action\":\"STOP\",\"target\":\"\",\"reason\":\"[STUB] đã có nguồn tham khảo, dừng vòng lặp demo\"}";
        }
        if (systemPrompt != null && systemPrompt.contains("MODE:DEEP_RESEARCH_SYNTHESIS")) {
            java.util.regex.Matcher cm = java.util.regex.Pattern.compile("\\((\\d+) nguồn\\)").matcher(userPrompt);
            int n = cm.find() ? Integer.parseInt(cm.group(1)) : 0;
            if (n == 0) return "{\"title\":\"[STUB] Deep Research\",\"findings\":[]}";
            StringBuilder refs = new StringBuilder();
            for (int i = 1; i <= n; i++) refs.append(i).append(i < n ? "," : "");
            return "{\"title\":\"[STUB] Deep Research\",\"findings\":[{\"bucket\":\"COMPETITIVE_THEME\","
                 + "\"subject_key\":null,\"text_vi\":\"[STUB] Tổng hợp demo từ " + n
                 + " nguồn đã thu thập — không phải phân tích AI thật.\",\"text_en\":\"[STUB] Demo synthesis from " + n
                 + " gathered sources — not real AI analysis.\",\"highlight\":true,\"severity\":null,"
                 + "\"source_refs\":[" + refs + "]}]}";
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

    /** Escape tối thiểu cho chuỗi chèn vào JSON stub (query người dùng gõ có thể chứa " hoặc \). */
    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    private static boolean containsAny(String text, String... keys) {
        for (String k : keys) if (text.contains(k.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    @Override
    public String providerName() { return "STUB"; }
}
