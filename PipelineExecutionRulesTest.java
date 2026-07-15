import com.marketradar.llm.LlmClient;
import com.marketradar.llm.SwitchableLlmClient;
import com.marketradar.pipeline.PipelineExecutionRules;

public class PipelineExecutionRulesTest {
    public static void main(String[] args) {
        var stub = new SwitchableLlmClient(new Noop(),
                new SwitchableLlmClient.Config(SwitchableLlmClient.Kind.STUB, "", "stub", 1));
        try {
            PipelineExecutionRules.requireConfigured("extract", stub);
            throw new AssertionError("STUB must be blocked");
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().contains("BLOCKED")) throw expected;
        }
        System.out.println("PipelineExecutionRulesTest: ALL PASS");
    }

    private static final class Noop implements LlmClient {
        public String complete(String systemPrompt, String userPrompt, Double temperature) { return ""; }
        public String providerName() { return "STUB"; }
    }
}
