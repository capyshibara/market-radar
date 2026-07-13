package com.marketradar.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chọn WRITER client (interpreter AI#3 + extractor AI#2 + dedup pairwise) theo cấu hình.
 * Thứ tự ưu tiên (Batch 9 — thêm đường OpenAI-compat để chạy Gemini/провider khác):
 *   1. marketradar.llm.base-url ĐƯỢC SET + env WRITER_API_KEY → OpenAI-compat
 *      (Gemini qua endpoint compat, DeepSeek, Qwen, vLLM...).
 *   2. env ANTHROPIC_API_KEY → Anthropic Messages API (đường cũ, không đổi hành vi).
 *   3. Không có gì → STUB.
 * Chế độ đang chạy được log RẤT TO lúc khởi động — không được mập mờ.
 */
@Configuration
public class LlmClientFactory {

    private static final Logger log = LoggerFactory.getLogger(LlmClientFactory.class);

    @Bean
    @org.springframework.context.annotation.Primary  // Batch 4: có thêm bean verifier — bean này là WRITER mặc định
    public LlmClient llmClient(
            @Value("${marketradar.llm.base-url:}") String baseUrl,
            @Value("${marketradar.llm.model:claude-sonnet-4-6}") String model,
            @Value("${marketradar.llm.max-tokens:300}") int maxTokens) {
        String compatKey = System.getenv("WRITER_API_KEY");
        if (!baseUrl.isBlank() && compatKey != null && !compatKey.isBlank()) {
            log.info("LLM MODE (WRITER): OPENAI_COMPAT (base-url={}, model={})", baseUrl, model);
            return new OpenAiCompatibleLlmClient(baseUrl, compatKey, model, maxTokens);
        }
        if (!baseUrl.isBlank()) {
            log.warn("marketradar.llm.base-url được set nhưng THIẾU env WRITER_API_KEY — bỏ qua, thử đường Anthropic.");
        }
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey != null && !apiKey.isBlank()) {
            log.info("LLM MODE (WRITER): ANTHROPIC (model={})", model);
            return new AnthropicLlmClient(apiKey, model, maxTokens);
        }
        log.warn("╔══════════════════════════════════════════════════════╗");
        log.warn("║ LLM MODE: STUB — không có WRITER_API_KEY (+base-url)  ║");
        log.warn("║ hoặc ANTHROPIC_API_KEY.                               ║");
        log.warn("║ Phân loại chạy bằng keyword heuristic, KHÔNG phải AI. ║");
        log.warn("╚══════════════════════════════════════════════════════╝");
        return new StubLlmClient();
    }
}
