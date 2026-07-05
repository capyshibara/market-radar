package vn.techcomlife.marketradar.llm;

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

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
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
        try {
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new LlmException("Verifier API HTTP " + resp.statusCode() + ": "
                        + truncate(resp.body(), 500));
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.asText().isBlank()) {
                throw new LlmException("Verifier API: response không có choices[0].message.content");
            }
            return content.asText();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new LlmException("Verifier API lỗi kết nối: " + e.getMessage(), e);
        }
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
