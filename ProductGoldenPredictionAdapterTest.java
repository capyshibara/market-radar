import com.marketradar.evaluation.ProductGoldenPredictionAdapter;

import java.time.LocalDate;
import java.util.Set;

public class ProductGoldenPredictionAdapterTest {
    private static final LocalDate SNAPSHOT = LocalDate.of(2026, 7, 15);

    public static void main(String[] args) {
        var launch = predict("Launches a new life insurance product", "FULL_TEXT",
                Set.of("PRODUCT_LAUNCH"));
        check(launch.departmentRelevant(), "product launch routes to Product");
        check("PRODUCT_LAUNCH".equals(launch.primaryEventType()), "uses current taxonomy");
        check("NEEDS_EVIDENCE".equals(launch.qualityDecision()),
                "full-text flag cannot replace unavailable fixture evidence");
        check(!launch.publishEligible() && !launch.evidenceEvaluable(),
                "fixture prediction fails closed");
        check(launch.unavailableFields().contains("rawText"), "missing rawText is explicit");

        var promotion = predict("Chương trình khuyến mại hoàn phí 20%", "TITLE_ONLY",
                Set.of("FEE_BENEFIT_COMMISSION_CHANGE"));
        check(promotion.departmentRelevant(), "labelled title-only offer remains pending evidence");
        check("MARKETING_PROMOTION".equals(promotion.primaryEventType()), "promotion taxonomy");
        check("NEEDS_EVIDENCE".equals(promotion.qualityDecision()), "title-only is blocked");

        var award = predict("Insurer wins technology award", "FULL_TEXT", Set.of());
        check(!award.departmentRelevant(), "corporate award is excluded");
        check("EXCLUDE".equals(award.qualityDecision()), "excluded item cannot publish");

        var genericPartnership = predict("Insurer and bank sign strategic partnership",
                "FULL_TEXT", Set.of("DISTRIBUTION_CHANNEL"));
        check(!genericPartnership.departmentRelevant(),
                "generic partnership lacks material distribution mechanism");

        System.out.println("ProductGoldenPredictionAdapterTest: ALL PASS");
    }

    private static ProductGoldenPredictionAdapter.Prediction predict(
            String title, String depth, Set<String> labels) {
        return ProductGoldenPredictionAdapter.predict(
                new ProductGoldenPredictionAdapter.FixtureCase(
                        "TEST", title, depth, labels, SNAPSHOT));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError("Failed: " + message);
    }
}
