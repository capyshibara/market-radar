import com.marketradar.product.CurrentProductNewsPolicy;
import com.marketradar.product.ProductReportCadence;

import java.time.LocalDate;
import java.util.Set;

/**
 * Standalone policy regression test:
 * javac -d /tmp/current-news-test src/main/java/com/marketradar/product/ProductReportCadence.java \
 *   src/main/java/com/marketradar/intelligence/ProductMaterialityRules.java \
 *   src/main/java/com/marketradar/product/CurrentProductNewsPolicy.java CurrentProductNewsPolicyTest.java
 * java -ea -cp /tmp/current-news-test CurrentProductNewsPolicyTest
 */
public class CurrentProductNewsPolicyTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 16);
    private static int checks;

    public static void main(String[] args) {
        acceptsConfirmedCurrentFullTextItem();
        rejectsOutOfWindowItem();
        rejectsShortOrTitleOnlyItem();
        rejectsAlteredEvidenceSpan();
        rejectsUnconfirmedAndIrrelevantLabels();
        rejectsNonLifeAndClaimsItems();
        rejectsAwardsAndMarketingNoise();
        rejectsDuplicateAndLowTierItems();
        System.out.println("CurrentProductNewsPolicyTest: " + checks + " checks passed");
    }

    private static void acceptsConfirmedCurrentFullTextItem() {
        check(decision(base()).eligible(), "confirmed current full-text evidence should appear as news");
    }

    private static void rejectsOutOfWindowItem() {
        var input = copy(base(), TODAY.minusDays(31), null, null, null, null, null, null, null, null);
        check(!decision(input).eligible(), "item before inclusive 30-day window must be excluded");
    }

    private static void rejectsShortOrTitleOnlyItem() {
        var input = copy(base(), null, "x".repeat(599), false, null, null, null, null, null, null);
        check(!decision(input).eligible(), "short or non-full-text source may not appear");
    }

    private static void rejectsAlteredEvidenceSpan() {
        var input = copy(base(), null, null, null, null, null, null,
                "This similar-looking but altered evidence span is not present in the source text. " + "x".repeat(90),
                null, null);
        check(!decision(input).eligible(), "displayed evidence must remain an exact source substring");
    }

    private static void rejectsUnconfirmedAndIrrelevantLabels() {
        var unconfirmed = copy(base(), null, null, null, "UNCERTAIN_REVIEW", null, null, null, null, null);
        var irrelevant = copy(base(), null, null, null, null, Set.of("NOT_A_REAL_LABEL"), null, null, null, null);
        check(!decision(unconfirmed).eligible(), "unconfirmed classification must be excluded");
        check(!decision(irrelevant).eligible(), "unknown classification label must be excluded");
    }

    private static void rejectsNonLifeAndClaimsItems() {
        var nonLife = copy(base(), null, null, null, null, null,
                "Non-life insurer changes property coverage", null, null, null);
        var claims = copy(base(), null, null, null, null, null,
                "Insurer completes claims payout", "The insurer completed a claims payment and claim payout for a policyholder. " + "x".repeat(90), null, null);
        check(!decision(nonLife).eligible(), "non-life evidence must stay out of life Product news");
        check(!decision(claims).eligible(), "claims-payment evidence must stay out of Product news");
    }

    private static void rejectsDuplicateAndLowTierItems() {
        var duplicate = copy(base(), null, null, null, null, null, null, null, true, null);
        var tierFour = copy(base(), null, null, null, null, null, null, null, null, 4);
        check(!decision(duplicate).eligible(), "duplicate document must be excluded");
        check(!decision(tierFour).eligible(), "tier four source must be excluded");
    }

    private static void rejectsAwardsAndMarketingNoise() {
        var award = copy(base(), null, null, null, null, null,
                "Life insurer wins global insurance awards", null, null, null);
        var travel = copy(base(), null, null, null, null, null,
                "Insurer promotes travel insurance", null, null, null);
        check(!decision(award).eligible(), "awards must not become Product news");
        check(!decision(travel).eligible(), "travel insurance is outside life Product scope");
    }

    private static CurrentProductNewsPolicy.Decision decision(CurrentProductNewsPolicy.Input input) {
        return CurrentProductNewsPolicy.evaluate(input, ProductReportCadence.MONTHLY, TODAY);
    }

    private static CurrentProductNewsPolicy.Input base() {
        String span = "The insurer introduced a documented protection product for customers with stated coverage terms and rollout details. "
                + "x".repeat(100);
        return new CurrentProductNewsPolicy.Input(true, true, span + " " + "x".repeat(600), true, "OK", false, false,
                2, TODAY.minusDays(5), "CONFIRMED", Set.of("PRODUCT_LAUNCH"),
                "Life insurer introduces a new protection product", span);
    }

    private static CurrentProductNewsPolicy.Input copy(CurrentProductNewsPolicy.Input b, LocalDate published,
            String rawText, Boolean fullText, String status, Set<String> labels, String title, String span,
            Boolean duplicate, Integer tier) {
        return new CurrentProductNewsPolicy.Input(b.factActive(), b.sourceActive(),
                rawText == null ? b.rawText() : rawText, fullText == null ? b.fullTextFetched() : fullText,
                b.parseStatus(), b.sampleData(), duplicate == null ? b.duplicate() : duplicate,
                tier == null ? b.sourceTier() : tier, published == null ? b.publishedDate() : published,
                status == null ? b.classificationStatus() : status,
                labels == null ? b.classificationLabels() : labels,
                title == null ? b.title() : title, span == null ? b.verbatimEvidenceSpan() : span);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
