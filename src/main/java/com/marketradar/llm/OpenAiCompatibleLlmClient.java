package com.marketradar.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Client generic cho mọi endpoint theo chuẩn OpenAI chat/completions
 * (OpenAI thật, Gemini qua endpoint OpenAI-compat, vLLM/Ollama local...).
 * Mục đích Batch 4: verifier Gate L2 — Hanh chốt model bằng CONFIG
 * (base-url + model + env key), không phải sửa code.
 *
 * Format body/response theo chuẩn POST {baseUrl}/chat/completions:
 *   body: model, max_tokens, messages[{role,content}]
 *   response: choices[0].message.content
 * (chuẩn de-facto được các provider OpenAI-compat cam kết giữ — nhưng CHƯA
 * verify chạy thật trong container offline này, cần smoke-test khi có mạng.)
 */
public class OpenAiCompatibleLlmClient implements LlmClient {

    // HTTP/1.1 ép buộc: DeepSeek (và một số OpenAI-compat khác) đóng kết nối h2 keep-alive
    // giữa chuỗi call tuần tự → IOException(null) hàng loạt. HTTP/1.1 + retry ổn định hơn hẳn
    // (quan sát thật 2026-07-12: 286/552 call classify lỗi "null" trước khi có fix này).
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build();
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MS = 800;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;   // vd https://api.openai.com/v1
    private final String apiKey;
    private final String model;
    private final int maxTokens;

    public OpenAiCompatibleLlmClient(String baseUrl, String apiKey, String model, int maxTokens) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt, Double temperature)
            throws LlmException {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        if (temperature != null) body.put("temperature", temperature);
        ArrayNode messages = body.putArray("messages");
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            ObjectNode sys = messages.addObject();
            sys.put("role", "system");
            sys.put("content", systemPrompt);
        }
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", userPrompt);

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        // Retry BỊ CHẶN cho lỗi kết nối / 5xx / 429 (transient); KHÔNG retry 4xx khác
        // (bad request/key sai — retry chỉ lặp lại lỗi). Idempotent: chat/completions
        // không tạo state phía server nên retry an toàn.
        LlmException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
                int sc = resp.statusCode();
                if (sc / 100 == 2) {
                    JsonNode root = mapper.readTree(resp.body());
                    JsonNode content = root.path("choices").path(0).path("message").path("content");
                    if (content.isMissingNode() || content.asText().isBlank()) {
                        throw new LlmException("OpenAI-compat API (" + model
                                + "): response không có choices[0].message.content");
                    }
                    return content.asText();
                }
                LlmException httpErr = new LlmException("OpenAI-compat API (" + model + ") HTTP " + sc
                        + ": " + truncate(resp.body(), 500));
                if (sc != 429 && sc / 100 != 5) throw httpErr; // 4xx khác — không retry
                last = httpErr;
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    throw new LlmException("OpenAI-compat API (" + model + ") bị interrupt", e);
                }
                last = new LlmException("OpenAI-compat API (" + model + ") lỗi kết nối: "
                        + e.getMessage(), e);
            }
            if (attempt < MAX_ATTEMPTS) {
                try { Thread.sleep(RETRY_BACKOFF_MS * attempt); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new LlmException("interrupt khi chờ retry", ie); }
            }
        }
        throw new LlmException(last.getMessage() + " (sau " + MAX_ATTEMPTS + " lần thử)", last);
    }

    @Override
    public String providerName() { return "OPENAI_COMPAT(" + model + ")"; }

    /** Dùng cho kiểm tra khác-họ lúc khởi động. */
    public String baseUrl() { return baseUrl; }
    public String model() { return model; }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "…");
    }
}
