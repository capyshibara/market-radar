import com.marketradar.classify.ClassificationSchemaRules;

import java.util.List;
import java.util.Set;

/** Standalone regression suite for fail-closed classifier labels. */
public class ClassificationSchemaRulesTest {
    private static final Set<String> ALLOWED = Set.of(
            "PRODUCT_LAUNCH", "FEE_BENEFIT_COMMISSION_CHANGE", "PRODUCT_REGULATION",
            "SALES_DATA", "DISTRIBUTION_CHANNEL");
    private static int checks;

    public static void main(String[] args) {
        check(ClassificationSchemaRules.validateLabels(List.of("PRODUCT_LAUNCH"), ALLOWED).isPresent(), "supported");
        check(ClassificationSchemaRules.validateLabels(List.of(), ALLOWED).isPresent(), "empty out-of-scope");
        check(ClassificationSchemaRules.validateLabels(
                List.of("PRODUCT_LAUNCH", "FEE_BENEFIT_COMMISSION_CHANGE"), ALLOWED).isPresent(), "multilabel");
        check(ClassificationSchemaRules.validateLabels(List.of("AWARD"), ALLOWED).isEmpty(), "award unsupported");
        check(ClassificationSchemaRules.validateLabels(
                List.of("PRODUCT_LAUNCH", "MARKETING_PROMOTION"), ALLOWED).isEmpty(), "invented label rejects all");
        check(ClassificationSchemaRules.validateLabels(java.util.Arrays.asList((String) null), ALLOWED).isEmpty(), "null");
        check(ClassificationSchemaRules.validateLabels(List.of(" product_launch "), ALLOWED).isEmpty(), "exact case");
        System.out.println("ClassificationSchemaRulesTest: " + checks + " checks passed");
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }
}
