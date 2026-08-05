import com.marketradar.llm.OpenAiCompatibleLlmClient;
import com.marketradar.llm.AnthropicLlmClient;

/** Regression: billing/auth failures must stop a paid batch instead of retrying every item. */
public class ProviderFailureRulesTest {
    public static void main(String[] args) {
        check(OpenAiCompatibleLlmClient.terminalProviderFailure(429,
                "{\"error\":{\"code\":\"credit_balance_exhausted\"}}"),
                "exhausted credit is terminal");
        check(OpenAiCompatibleLlmClient.terminalProviderFailure(429,
                "{\"error\":{\"type\":\"insufficient_quota\"}}"),
                "insufficient quota is terminal");
        check(!OpenAiCompatibleLlmClient.terminalProviderFailure(429,
                "{\"error\":{\"type\":\"rate_limit_exceeded\"}}"),
                "ordinary rate limiting remains retryable");
        check(OpenAiCompatibleLlmClient.terminalProviderFailure(401, "unauthorized"),
                "invalid authentication is terminal");
        check(OpenAiCompatibleLlmClient.terminalProviderFailure(402, "payment required"),
                "HTTP payment required is terminal");
        check(!OpenAiCompatibleLlmClient.terminalProviderFailure(500, "temporary"),
                "server failures remain retryable");
        check(AnthropicLlmClient.terminalProviderFailure(429,
                "{\"error\":{\"message\":\"credit balance is too low\"}}"),
                "Anthropic exhausted credit is terminal");
        check(!AnthropicLlmClient.terminalProviderFailure(429,
                "{\"error\":{\"type\":\"rate_limit_error\"}}"),
                "Anthropic ordinary rate limiting is not a billing failure");
        System.out.println("ProviderFailureRulesTest: ALL PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError("Failed: " + message);
    }
}
