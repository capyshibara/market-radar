package com.marketradar.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bean LLM riêng cho AI#1 (TopicClassifier). Phân loại chạy self-consistency
 * (mặc định 3 lần/doc) trên MỌI doc ingest — đây là bước tốn LLM call nhiều
 * nhất khi khối lượng crawl tăng, và là phân loại 5 nhãn đơn giản, không cần
 * model đắt tiền của writer (AI#3 — viết claim song ngữ, cần chất lượng cao hơn).
 *
 * Cấu hình (marketradar.classifier.*) + env CLASSIFIER_API_KEY. Không cấu hình
 * đủ 3 thứ (base-url, model, key) → dùng LẠI bean writer (@Primary) như trước,
 * hành vi KHÔNG đổi nếu Hanh chưa set gì (an toàn ngược — backward compatible).
 *
 * Gợi ý provider rẻ cho stage này (đánh giá cost/capability tháng 7/2026):
 *   Qwen-Flash   base-url=https://dashscope-intl.aliyuncs.com/compatible-mode/v1  model=qwen-flash   (~$0.05/$0.20 mỗi 1M token)
 *   DeepSeek V4 Flash  base-url=https://api.deepseek.com  model=deepseek-chat      (~$0.14/$0.28 mỗi 1M token)
 * Không ràng buộc "khác họ với writer" ở đây — Invariant #2 (verifier ≠ writer)
 * chỉ áp cho Gate L2, không áp cho classifier.
 *
 * Batch 9 (đối xứng với writer): marketradar.classifier.anthropic-model được set
 * + env ANTHROPIC_API_KEY → boot thẳng bằng Claude native, không cần qua
 * /llm-settings trước. Đây chỉ là tiện ích lúc khởi động — /llm-settings luôn
 * cho đổi bất kỳ slot nào sang Anthropic tại runtime rồi, kể cả không set cái này.
 */
@Configuration
public class ClassifierClientFactory {

    private static final Logger log = LoggerFactory.getLogger(ClassifierClientFactory.class);

    @Bean
    @Qualifier("classifierLlmClient")
    public LlmClient classifierLlmClient(
            LlmClient llmClient, // bean @Primary — writer; dùng làm fallback
            @Value("${marketradar.classifier.base-url:}") String baseUrl,
            @Value("${marketradar.classifier.model:}") String model,
            @Value("${marketradar.classifier.anthropic-model:}") String anthropicModel,
            @Value("${marketradar.classifier.max-tokens:200}") int maxTokens) {

        String apiKey = System.getenv("CLASSIFIER_API_KEY");
        if (apiKey != null && !apiKey.isBlank() && !baseUrl.isBlank() && !model.isBlank()) {
            log.info("CLASSIFIER MODE: OPENAI_COMPAT (base-url={}, model={}) — tách riêng khỏi writer "
                    + "để giảm chi phí phân loại khối lượng lớn (self-consistency 3x/doc).", baseUrl, model);
            return new SwitchableLlmClient(new OpenAiCompatibleLlmClient(baseUrl, apiKey, model, maxTokens),
                    new SwitchableLlmClient.Config(SwitchableLlmClient.Kind.OPENAI_COMPAT, baseUrl, model, maxTokens));
        }

        String anthropicKey = System.getenv("ANTHROPIC_API_KEY");
        if (!anthropicModel.isBlank() && anthropicKey != null && !anthropicKey.isBlank()) {
            log.info("CLASSIFIER MODE: ANTHROPIC (model={}) — cấu hình riêng khỏi writer qua "
                    + "marketradar.classifier.anthropic-model.", anthropicModel);
            return new SwitchableLlmClient(new AnthropicLlmClient(anthropicKey, anthropicModel, maxTokens),
                    new SwitchableLlmClient.Config(SwitchableLlmClient.Kind.ANTHROPIC, null, anthropicModel, maxTokens));
        }

        // Batch 9: instance RIÊNG (không phải cùng object với writer) — khởi tạo bằng bản
        // SAO CHÉP trạng thái writer lúc boot, sau đó hai slot độc lập hoàn toàn qua
        // /llm-settings (đổi cái này không tự kéo theo cái kia — dễ đoán hơn cho UI).
        SwitchableLlmClient writer = (SwitchableLlmClient) llmClient;
        log.info("CLASSIFIER MODE: dùng chung writer client ({}) — chưa cấu hình provider riêng "
                + "(marketradar.classifier.base-url/model + env CLASSIFIER_API_KEY, hoặc "
                + "marketradar.classifier.anthropic-model + env ANTHROPIC_API_KEY) để giảm chi phí.",
                llmClient.providerName());
        return new SwitchableLlmClient(writer.delegate(), writer.config());
    }
}
