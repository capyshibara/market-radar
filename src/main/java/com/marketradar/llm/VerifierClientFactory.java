package com.marketradar.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bean verifier cho Gate L2 (Batch 4). Invariant #2 "Verifier ≠ Writer" được
 * ENFORCE LÚC KHỞI ĐỘNG (Invariant2.assertDifferentFamily — cùng logic dùng lại
 * ở /llm-settings khi đổi provider tại runtime): nếu writer là ANTHROPIC mà
 * verifier cũng trỏ về model/endpoint họ Claude → app TỪ CHỐI chạy.
 *
 * Cấu hình (application.yml → marketradar.verifier.*):
 *   base-url  — endpoint chuẩn OpenAI-compat (đổi provider = đổi dòng này)
 *   model     — tên model verifier
 * API key đọc từ env VERIFIER_API_KEY. Không có key → StubVerifierClient
 * (mặc định NEUTRAL → mọi thứ vào review, không bao giờ tự xuất bản).
 *
 * Batch 9 ("Change LLM" UI): bean trả về là SwitchableLlmClient.
 * Batch 9 (đối xứng với writer): marketradar.verifier.anthropic-model + env
 * ANTHROPIC_API_KEY → boot thẳng bằng Claude native cho verifier (chỉ hợp lệ khi
 * writer KHÔNG phải Claude — Invariant2 kiểm tra y hệt nhánh OpenAI-compat bên dưới).
 * Đây chỉ là tiện ích lúc khởi động — /llm-settings luôn cho đổi runtime rồi.
 */
@Configuration
public class VerifierClientFactory {

    private static final Logger log = LoggerFactory.getLogger(VerifierClientFactory.class);

    @Bean
    @Qualifier("verifierLlmClient")
    public LlmClient verifierLlmClient(
            LlmClient writerClient,  // bean @Primary — writer
            @Value("${marketradar.verifier.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${marketradar.verifier.model:gpt-4o-mini}") String model,
            @Value("${marketradar.verifier.anthropic-model:}") String anthropicModel,
            @Value("${marketradar.verifier.max-tokens:512}") int maxTokens) {

        String anthropicKey = System.getenv("ANTHROPIC_API_KEY");
        if (!anthropicModel.isBlank() && anthropicKey != null && !anthropicKey.isBlank()) {
            var anthropicConfig = new SwitchableLlmClient.Config(
                    SwitchableLlmClient.Kind.ANTHROPIC, null, anthropicModel, maxTokens);
            Invariant2.assertDifferentFamily(((SwitchableLlmClient) writerClient).config(), anthropicConfig);
            log.info("VERIFIER MODE: ANTHROPIC (model={}) — khác họ với writer {}",
                    anthropicModel, writerClient.providerName());
            return new SwitchableLlmClient(new AnthropicLlmClient(anthropicKey, anthropicModel, maxTokens), anthropicConfig);
        }

        String apiKey = System.getenv("VERIFIER_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("╔══════════════════════════════════════════════════════════╗");
            log.warn("║ VERIFIER MODE: STUB — KHÔNG có VERIFIER_API_KEY.          ║");
            log.warn("║ Mọi claim thật sẽ nhận NEUTRAL → bắt buộc human review.   ║");
            log.warn("║ KHÔNG có auto-publish khi thiếu verifier thật (fail loud).║");
            log.warn("╚══════════════════════════════════════════════════════════╝");
            return new SwitchableLlmClient(new StubVerifierClient(),
                    new SwitchableLlmClient.Config(SwitchableLlmClient.Kind.STUB_VERIFIER, null, "STUB_VERIFIER", maxTokens));
        }

        var verifierConfig = new SwitchableLlmClient.Config(
                SwitchableLlmClient.Kind.OPENAI_COMPAT, baseUrl, model, maxTokens);
        Invariant2.assertDifferentFamily(((SwitchableLlmClient) writerClient).config(), verifierConfig);

        log.info("VERIFIER MODE: OPENAI_COMPAT (base-url={}, model={}) — khác họ với writer {}",
                baseUrl, model, writerClient.providerName());
        return new SwitchableLlmClient(new OpenAiCompatibleLlmClient(baseUrl, apiKey, model, maxTokens), verifierConfig);
    }
}
