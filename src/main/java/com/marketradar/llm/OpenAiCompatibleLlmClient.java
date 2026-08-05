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
import java.util.List;

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
        return completeWithMaxTokens(systemPrompt, userPrompt, temperature, maxTokens);
    }

    @Override
    public String completeWithMaxTokens(String systemPrompt, String userPrompt,
                                        Double temperature, int maxOutputTokens)
            throws LlmException {
        ObjectNode body = baseRequestBody(systemPrompt, userPrompt, temperature,
                Math.max(1, maxOutputTokens));
        JsonNode root = sendWithRetry(body);
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.asText().isBlank()) {
            throw new LlmException("OpenAI-compat API (" + model
                    + "): response không có choices[0].message.content");
        }
        return content.asText();
    }

    /**
     * 2026-08-03: tool-calling GỐC của provider thay cho bắt LLM trả JSON tự do trong content
     * rồi tự parse tay (JsonRepair...) — dùng cho Deep Research plan step (xem DeepResearchService
     * #plan). tool_choice="auto" (không ép "required") để còn tương thích các provider OpenAI-
     * compat khác có thể không support ép buộc; nếu model vẫn trả lời bằng text thường thay vì
     * gọi tool, coi là lỗi — caller (DeepResearchService) đã có sẵn đường xử lý an toàn (dừng
     * vòng lặp) cho mọi LlmException từ bước plan.
     */
    @Override
    public ToolChoice completeWithTools(String systemPrompt, String userPrompt,
                                        List<LlmTool> tools, Double temperature) throws LlmException {
        ObjectNode body = baseRequestBody(systemPrompt, userPrompt, temperature, maxTokens);
        ArrayNode toolsNode = body.putArray("tools");
        for (LlmTool tool : tools) {
            ObjectNode fn = toolsNode.addObject();
            fn.put("type", "function");
            ObjectNode function = fn.putObject("function");
            function.put("name", tool.name());
            function.put("description", tool.description());
            function.set("parameters", tool.parameters());
        }
        body.put("tool_choice", "auto");

        JsonNode root = sendWithRetry(body);
        JsonNode toolCalls = root.path("choices").path(0).path("message").path("tool_calls");
        if (!toolCalls.isArray() || toolCalls.isEmpty()) {
            throw new LlmException("OpenAI-compat API (" + model + "): response không gọi tool nào "
                    + "(model trả lời bằng text thường thay vì function call)");
        }
        JsonNode call = toolCalls.get(0).path("function");
        String name = call.path("name").asText("");
        String argsRaw = call.path("arguments").asText("{}");
        try {
            return new ToolChoice(name, mapper.readTree(argsRaw.isBlank() ? "{}" : argsRaw));
        } catch (Exception e) {
            throw new LlmException("OpenAI-compat API (" + model + "): arguments của tool call '"
                    + name + "' không phải JSON hợp lệ: " + truncate(argsRaw, 300), e);
        }
    }

    private ObjectNode baseRequestBody(String systemPrompt, String userPrompt,
                                       Double temperature, int outputTokenBudget) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        // 2026-07-15 (writer → gpt-5-mini): họ reasoning của OpenAI (gpt-5*, o*) TỪ CHỐI
        // "max_tokens" (đòi "max_completion_tokens") và từ chối luôn "temperature" khác mặc
        // định. Các provider compat khác (DeepSeek, Qwen, Gemini-compat) vẫn theo chuẩn cũ —
        // switch theo model, không đổi hành vi nguồn nào đang chạy.
        if (isOpenAiReasoningModel()) {
            body.put("max_completion_tokens", outputTokenBudget);
            // reasoning "low": đủ cho task schema hẹp (extract/interpret slot), không đốt
            // phần lớn budget token vào reasoning ẩn.
            body.put("reasoning_effort", "low");
        } else {
            body.put("max_tokens", outputTokenBudget);
            if (temperature != null) body.put("temperature", temperature);
        }
        ArrayNode messages = body.putArray("messages");
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            ObjectNode sys = messages.addObject();
            sys.put("role", "system");
            sys.put("content", systemPrompt);
        }
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", userPrompt);
        return body;
    }

    /** Retry BỊ CHẶN cho lỗi kết nối / 5xx / 429 (transient); KHÔNG retry 4xx khác (bad
     *  request/key sai — retry chỉ lặp lại lỗi). Idempotent: chat/completions không tạo state
     *  phía server nên retry an toàn. Dùng chung cho cả complete() và completeWithTools(). */
    private JsonNode sendWithRetry(ObjectNode body) throws LlmException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        LlmException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
                int sc = resp.statusCode();
                if (sc / 100 == 2) return mapper.readTree(resp.body());
                String responseBody = resp.body();
                String message = "OpenAI-compat API (" + model + ") HTTP " + sc
                        + ": " + truncate(responseBody, 500);
                if (terminalProviderFailure(sc, responseBody)) {
                    throw new TerminalLlmException(message);
                }
                LlmException httpErr = new LlmException(message);
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

    /** Pure rule exposed for the offline regression suite. */
    public static boolean terminalProviderFailure(int statusCode, String responseBody) {
        if (statusCode == 401 || statusCode == 402 || statusCode == 403 || statusCode == 404) return true;
        if (statusCode != 429 || responseBody == null) return false;
        String normalized = responseBody.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("insufficient_quota")
                || normalized.contains("credit_balance_exhausted")
                || normalized.contains("no credits remaining");
    }

    /** gpt-5* / o1-o4* của CHÍNH OpenAI — các model đổi param theo kiểu reasoning API. */
    private boolean isOpenAiReasoningModel() {
        String m = model.toLowerCase(java.util.Locale.ROOT);
        return (m.startsWith("gpt-5") || m.matches("^o[1-9].*"))
                && baseUrl.contains("api.openai.com");
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
