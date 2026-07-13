package com.marketradar.llm;

/**
 * Batch 9 ("Change LLM" UI): bọc một LlmClient sau một reference volatile để
 * đổi provider/model TẠI RUNTIME (từ /llm-settings) mà không cần restart JVM.
 * Mỗi slot (classifier/writer/verifier) có MỘT instance riêng — kể cả khi
 * classifier khởi động ở chế độ "dùng chung writer", nó nhận một BẢN SAO cấu
 * hình ban đầu của writer, không phải cùng object; sau đó hai slot độc lập
 * hoàn toàn (đổi cái này không tự kéo theo cái kia — dễ dự đoán hơn cho UI).
 */
public class SwitchableLlmClient implements LlmClient {

    public enum Kind { ANTHROPIC, OPENAI_COMPAT, STUB, STUB_VERIFIER }

    /** apiKey KHÔNG lưu ở đây — chỉ giữ trong delegate, không hiện lại lên UI. */
    public record Config(Kind kind, String baseUrl, String model, int maxTokens) {}

    private volatile LlmClient delegate;
    private volatile Config config;

    public SwitchableLlmClient(LlmClient delegate, Config config) {
        this.delegate = delegate;
        this.config = config;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt, Double temperature) throws LlmException {
        return delegate.complete(systemPrompt, userPrompt, temperature);
    }

    @Override
    public String providerName() { return delegate.providerName(); }

    public Config config() { return config; }

    /** Snapshot delegate hiện tại — dùng để clone trạng thái ban đầu sang slot khác (vd classifier "dùng chung writer" lúc boot). */
    public LlmClient delegate() { return delegate; }

    public synchronized void reconfigure(LlmClient newDelegate, Config newConfig) {
        this.delegate = newDelegate;
        this.config = newConfig;
    }
}
