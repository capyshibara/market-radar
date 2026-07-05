package com.marketradar.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

/**
 * Bean verifier cho Gate L2 (Batch 4). Invariant #2 "Verifier ≠ Writer" được
 * ENFORCE LÚC KHỞI ĐỘNG: nếu writer là ANTHROPIC mà verifier cũng trỏ về
 * model/endpoint họ Claude → app TỪ CHỐI chạy (fail loud, không chạy sai âm thầm).
 *
 * Cấu hình (application.yml → marketradar.verifier.*):
 *   base-url  — endpoint chuẩn OpenAI-compat (đổi provider = đổi dòng này)
 *   model     — tên model verifier
 * API key đọc từ env VERIFIER_API_KEY. Không có key → StubVerifierClient
 * (mặc định NEUTRAL → mọi thứ vào review, không bao giờ tự xuất bản).
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
            @Value("${marketradar.verifier.max-tokens:512}") int maxTokens) {

        String apiKey = System.getenv("VERIFIER_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("╔══════════════════════════════════════════════════════════╗");
            log.warn("║ VERIFIER MODE: STUB — KHÔNG có VERIFIER_API_KEY.          ║");
            log.warn("║ Mọi claim thật sẽ nhận NEUTRAL → bắt buộc human review.   ║");
            log.warn("║ KHÔNG có auto-publish khi thiếu verifier thật (fail loud).║");
            log.warn("╚══════════════════════════════════════════════════════════╝");
            return new StubVerifierClient();
        }

        // ---- Kiểm tra khác họ (Invariant #2) ----
        String writerFamily = writerClient.providerName().toUpperCase(Locale.ROOT);
        String url = baseUrl.toLowerCase(Locale.ROOT);
        String mdl = model.toLowerCase(Locale.ROOT);
        boolean verifierIsClaudeFamily = url.contains("anthropic") || mdl.startsWith("claude");
        if (writerFamily.startsWith("ANTHROPIC") && verifierIsClaudeFamily) {
            throw new IllegalStateException(
                "VI PHẠM INVARIANT #2 (Verifier ≠ Writer): writer là ANTHROPIC nhưng verifier "
                + "cấu hình model/endpoint họ Claude (" + baseUrl + ", " + model + "). "
                + "Đổi marketradar.verifier.* sang model khác họ rồi khởi động lại.");
        }

        log.info("VERIFIER MODE: OPENAI_COMPAT (base-url={}, model={}) — khác họ với writer {}",
                baseUrl, model, writerFamily);
        return new OpenAiCompatibleLlmClient(baseUrl, apiKey, model, maxTokens);
    }
}
