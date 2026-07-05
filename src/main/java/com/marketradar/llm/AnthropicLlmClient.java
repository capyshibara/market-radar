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
 * Client REST cho Anthropic Messages API.
 * Format đã verify qua docs (07/2026): POST https://api.anthropic.com/v1/messages
 * headers: x-api-key, anthropic-version: 2023-06-01, content-type: application/json
 * body: model, max_tokens, system, messages[]; response: content[] (lọc block type="text").
 *
 * Lưu ý: temperature/top_p KHÔNG còn được hỗ trợ trên một số model mới (Opus 4.7+,
 * trả 400) — vì vậy temperature là nullable, null = không gửi.
 * Không phải bean Spring trực tiếp — được LlmClientFactory khởi tạo khi có API key.
 */
public class AnthropicLlmClient implements LlmClient {

    private static final String ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;
    private final String model;
    private final int maxTokens;

    public AnthropicLlmClient(String apiKey, String model, int maxTokens) {
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
        body.put("system", systemPrompt);
        if (temperature != null) body.put("temperature", temperature);
        ArrayNode messages = body.putArray("messages");
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);

        HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(Duration.ofSeconds(60))
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new LlmException("Lỗi mạng gọi Anthropic API: " + e.getMessage(), e);
        }

        if (response.statusCode() != 200) {
            // Không log body request (có thể chứa nội dung nhạy cảm) — chỉ status + body lỗi
            throw new LlmException("Anthropic API HTTP " + response.statusCode()
                    + ": " + truncate(response.body(), 500));
        }

        try {
            JsonNode root = mapper.readTree(response.body());
            StringBuilder text = new StringBuilder();
            for (JsonNode block : root.path("content")) {
                if ("text".equals(block.path("type").asText())) {
                    text.append(block.path("text").asText());
                }
            }
            if (text.isEmpty()) throw new LlmException("Response không có block text nào");
            return text.toString();
        } catch (IOException e) {
            throw new LlmException("Không parse được response JSON: " + e.getMessage(), e);
        }
    }

    @Override
    public String providerName() { return "ANTHROPIC"; }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "…");
    }
}
