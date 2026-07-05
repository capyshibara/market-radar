package vn.techcomlife.marketradar.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chọn client theo môi trường: có ANTHROPIC_API_KEY → client thật; không → STUB.
 * Chế độ đang chạy được log RẤT TO lúc khởi động — không được mập mờ.
 */
@Configuration
public class LlmClientFactory {

    private static final Logger log = LoggerFactory.getLogger(LlmClientFactory.class);

    @Bean
    @org.springframework.context.annotation.Primary  // Batch 4: có thêm bean verifier — bean này là WRITER mặc định
    public LlmClient llmClient(
            @Value("${marketradar.llm.model:claude-sonnet-4-6}") String model,
            @Value("${marketradar.llm.max-tokens:300}") int maxTokens) {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey != null && !apiKey.isBlank()) {
            log.info("LLM MODE: ANTHROPIC (model={})", model);
            return new AnthropicLlmClient(apiKey, model, maxTokens);
        }
        log.warn("╔══════════════════════════════════════════════════════╗");
        log.warn("║ LLM MODE: STUB — KHÔNG có ANTHROPIC_API_KEY.          ║");
        log.warn("║ Phân loại chạy bằng keyword heuristic, KHÔNG phải AI. ║");
        log.warn("║ Set env ANTHROPIC_API_KEY để dùng model thật.         ║");
        log.warn("╚══════════════════════════════════════════════════════╝");
        return new StubLlmClient();
    }
}
