import com.marketradar.llm.SwitchableLlmClient;
import com.marketradar.pipeline.ReprocessPreflightRules;
import com.marketradar.extract.ExtractionContentDiagnostics;

/** Standalone regression test for the no-go/go preflight contract. */
public class ReprocessPreflightRulesTest {
    public static void main(String[] args) {
        var blocked = ReprocessPreflightRules.evaluate(new ReprocessPreflightRules.Input(
                SwitchableLlmClient.Kind.STUB, SwitchableLlmClient.Kind.STUB,
                SwitchableLlmClient.Kind.STUB_VERIFIER, false, false,
                697, 155, 155, 0, 0, 684, 12,
                new ExtractionContentDiagnostics.LengthStats(542, 600, 6000, 16000, 40000, 8, 7200)));
        check(!blocked.ready(), "stub providers and missing backup must block");
        check(blocked.orderedStages().get(0).equals("targeted-refetch"), "content gaps schedule refetch");
        check(blocked.orderedStages().contains("regenerate-product-7-30-90"),
                "preflight advertises exact Product cadence generation");
        check(!blocked.orderedStages().contains("interpret-stale")
                        && !blocked.orderedStages().contains("human-review"),
                "unified Product path does not advertise unused legacy claim stages");

        var ready = ReprocessPreflightRules.evaluate(new ReprocessPreflightRules.Input(
                SwitchableLlmClient.Kind.OPENAI_COMPAT, SwitchableLlmClient.Kind.ANTHROPIC,
                SwitchableLlmClient.Kind.OPENAI_COMPAT, false, true,
                697, 0, 0, 0, 0, 684, 0,
                new ExtractionContentDiagnostics.LengthStats(697, 600, 6000, 16000, 24000, 0, 7200)));
        check(ready.ready(), "configured idle pipeline with backup is ready");
        check(ready.checks().stream().noneMatch(c -> c.severity() == ReprocessPreflightRules.Severity.BLOCKER
                && !c.passed()), "no failed blocker");
        check(ready.checks().stream().anyMatch(c -> c.code().equals("CONTENT_LENGTH_DEPTH")
                && c.message().contains("medianChars=6000")), "content-depth diagnostic is visible");
        System.out.println("ReprocessPreflightRulesTest: ALL PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
