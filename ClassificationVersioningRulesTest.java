import com.marketradar.classify.ClassificationVersioning;

/** Dependency-free regression checks; run with javac/java from the repository root. */
public class ClassificationVersioningRulesTest {

    public static void main(String[] args) {
        var v1 = ClassificationVersioning.current("OPENAI_COMPAT(model-a)",
                "effective prompt v1", "content-a");
        var same = ClassificationVersioning.current("OPENAI_COMPAT(model-a)",
                "effective prompt v1", "content-a");
        var newModel = ClassificationVersioning.current("OPENAI_COMPAT(model-b)",
                "effective prompt v1", "content-a");
        var newPrompt = ClassificationVersioning.current("OPENAI_COMPAT(model-a)",
                "effective prompt v2", "content-a");
        var newContent = ClassificationVersioning.current("OPENAI_COMPAT(model-a)",
                "effective prompt v1", "content-b");

        check(v1.signature().equals(same.signature()), "same inputs must be current");
        check(!v1.signature().equals(newModel.signature()), "model change must be stale");
        check(!v1.signature().equals(newPrompt.signature()), "prompt change must be stale");
        check(!v1.signature().equals(newContent.signature()), "content change must be stale");
        check(!ClassificationVersioning.matches(v1.providerModel(), null,
                v1.contentSha256(), v1.signature(), v1),
                "legacy/null metadata must not match");
        check(ClassificationVersioning.matches(v1.providerModel(), v1.promptSha256(),
                v1.contentSha256(), v1.signature(), same),
                "complete matching metadata must match");

        check(ClassificationVersioning.plan(true, true, false, false)
                        == ClassificationVersioning.PlanAction.SKIP_CURRENT,
                "current active result must skip");
        check(ClassificationVersioning.plan(true, false, false, false)
                        == ClassificationVersioning.PlanAction.RECLASSIFY_STALE,
                "stale active result must rerun");
        check(ClassificationVersioning.plan(false, false, false, false)
                        == ClassificationVersioning.PlanAction.CLASSIFY_NEW,
                "missing active result must run");
        check(ClassificationVersioning.plan(true, false, true, false)
                        == ClassificationVersioning.PlanAction.HOLD_FAILED_VERSION,
                "failed desired version must hold instead of looping");
        check(ClassificationVersioning.plan(true, false, true, true)
                        == ClassificationVersioning.PlanAction.RECLASSIFY_STALE,
                "explicit retry must release a held version");

        check(ClassificationVersioning.isPromotableStatus("CONFIRMED"),
                "confirmed candidate is promotable");
        check(ClassificationVersioning.isPromotableStatus("OUT_OF_SCOPE"),
                "decisive out-of-scope candidate is promotable");
        check(!ClassificationVersioning.isPromotableStatus("UNCERTAIN_REVIEW"),
                "uncertain rerun must preserve prior output");
        check(!ClassificationVersioning.isPromotableStatus("NO_LABEL_REVIEW"),
                "disagreement rerun must preserve prior output");

        System.out.println("ClassificationVersioningRulesTest: OK");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
