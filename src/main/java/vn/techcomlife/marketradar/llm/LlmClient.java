package vn.techcomlife.marketradar.llm;

/** Cửa gọi LLM duy nhất. Implementation: AnthropicLlmClient (thật) / StubLlmClient (offline). */
public interface LlmClient {
    /**
     * @param temperature null = không gửi tham số (một số model mới từ chối temperature).
     */
    String complete(String systemPrompt, String userPrompt, Double temperature) throws LlmException;
    String providerName();
}
