package com.marketradar.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/** Cửa gọi LLM duy nhất. Implementation: AnthropicLlmClient (thật) / StubLlmClient (offline). */
public interface LlmClient {
    /**
     * @param temperature null = không gửi tham số (một số model mới từ chối temperature).
     */
    String complete(String systemPrompt, String userPrompt, Double temperature) throws LlmException;

    /**
     * Run one completion with a task-specific output budget. Most pipeline calls should keep
     * using {@link #complete}; this seam exists for bounded, unusually large outputs such as
     * the Deep Research preview synthesis. Providers that can control the budget override this
     * method. Older/test clients remain source-compatible and safely fall back to their normal
     * configured budget.
     */
    default String completeWithMaxTokens(String systemPrompt, String userPrompt,
                                         Double temperature, int maxOutputTokens)
            throws LlmException {
        return complete(systemPrompt, userPrompt, temperature);
    }

    String providerName();

    /** Một tool cho LLM chọn qua function-calling GỐC của provider (JSON Schema chuẩn) — thay
     *  cho cách cũ bắt LLM trả JSON tự do trong "content" rồi tự parse tay (JsonRepair...): dễ vỡ
     *  khi model quên format/lẫn thêm chữ. parameters là 1 JSON Schema object (kiểu "object" với
     *  "properties"/"required") giống hệt định dạng OpenAI "function.parameters" / Anthropic
     *  "tool.input_schema" — cả 2 provider dùng chung 1 chuẩn JSON Schema nên không cần 2 schema. */
    record LlmTool(String name, String description, ObjectNode parameters) {}

    /** Tool model đã chọn + tham số nó điền — arguments không bao giờ null (object rỗng nếu
     *  provider không trả gì), tiện gọi .path(...) mà không phải tự check null trước. */
    record ToolChoice(String toolName, JsonNode arguments) {}

    /**
     * Bắt LLM chọn đúng 1 trong các tool đã khai báo thay vì trả JSON tự do. Mặc định (provider
     * chưa cài đặt riêng) ném UnsupportedOperationException — chỉ các client thật sự hỗ trợ
     * function-calling (Anthropic, OpenAI-compat, Stub cho demo offline) mới override.
     */
    default ToolChoice completeWithTools(String systemPrompt, String userPrompt,
                                         List<LlmTool> tools, Double temperature) throws LlmException {
        throw new UnsupportedOperationException(providerName() + " không hỗ trợ tool-calling");
    }

    /** 1 kết quả tìm kiếm web THẬT (không phải RSS scrape) — url/title do chính search backend
     *  trả về, snippet chỉ để tham khảo (không dùng làm bằng chứng, evidence thật lấy từ trang
     *  đã fetch nguyên văn sau đó, xem DeepResearchService#runSearch). */
    record WebSearchHit(String title, String url, String snippet) {}

    /**
     * 2026-08-03 (feedback: "Deep Research chạy không ra gì" — nguyên nhân: NewsDiscoveryService
     * cũ chỉ scrape RSS Google/Bing News KHÔNG chính thức, không SLA, dễ bị chặn/rỗng bất kỳ lúc
     * nào). Search THẬT qua tool web_search gốc của provider — mặc định ném
     * UnsupportedOperationException, chỉ client thật sự hỗ trợ (Anthropic) mới override; caller
     * PHẢI có đường lùi (fallback RSS) khi provider không hỗ trợ, không được coi thiếu tính năng
     * này là lỗi cứng.
     */
    default List<WebSearchHit> webSearch(String query, int maxUses) throws LlmException {
        throw new UnsupportedOperationException(providerName() + " không hỗ trợ web search");
    }
}
