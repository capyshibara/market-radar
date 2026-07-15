import com.marketradar.llm.ProviderSafetyRules;

/** Standalone fail-closed provider regression suite. */
public class ProviderSafetyRulesTest {
    private static int checks;

    public static void main(String[] args) {
        check(ProviderSafetyRules.isStub("STUB"), "writer stub rejected");
        check(ProviderSafetyRules.isStub("STUB_VERIFIER"), "verifier stub rejected");
        check(ProviderSafetyRules.isStub(" stub(test) "), "stub family rejected case-insensitively");
        check(ProviderSafetyRules.isStub(null), "missing provider rejected");
        check(!ProviderSafetyRules.isStub("OPENAI_COMPAT(gpt-5-mini)"), "real OpenAI-compatible model allowed");
        check(!ProviderSafetyRules.isStub("ANTHROPIC(claude-sonnet-4)"), "real Anthropic model allowed");
        System.out.println("ProviderSafetyRulesTest: " + checks + " checks passed");
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }
}
