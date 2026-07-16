import com.marketradar.product.CurrentProductNewsItem;
import com.marketradar.product.CurrentProductNewsTopic;
import com.marketradar.report.ProductExecutiveBrief;
import com.marketradar.report.ProductReportAdapter;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Standalone regression for the deterministic report editorial layer. */
public class ProductExecutiveBriefTest {
    public static void main(String[] args) {
        LocalDate asOf = LocalDate.of(2026, 7, 16);
        List<CurrentProductNewsItem> news = List.of(
                item("F-101", "Regulation A", "MOF", "Ministry of Finance", 1, "REGULATION", asOf),
                item("F-102", "Regulation B", "MOF", "Ministry of Finance", 1, "REGULATION", asOf.minusDays(1)),
                item("F-103", "New benefit", "INSURER", "Life insurer", 2, "PRODUCT_LAUNCH", asOf.minusDays(2)));
        var snapshot = new ProductReportAdapter.Snapshot(null,
                ProductReportAdapter.Availability.INSUFFICIENT_EVIDENCE,
                ProductReportAdapter.InsufficientReason.NO_CORROBORATED_INSIGHT,
                asOf.minusDays(29), asOf, List.of(), List.of(), Map.of(), news);

        ProductExecutiveBrief brief = ProductExecutiveBrief.from(snapshot, false);
        check("EVIDENCE_BRIEF".equals(brief.modeCode()), "sparse evidence has an honest evidence mode");
        check(brief.verifiedDevelopmentCount() == 3, "counts admitted developments");
        check(brief.sourceCount() == 2, "deduplicates publishers");
        check(brief.tierOneSourceCount() == 1, "counts tier-one publishers");
        check(brief.priorityAreas().size() == 2, "groups evidence into Product priorities");
        check("REGULATION".equals(brief.priorityAreas().get(0).code()), "orders leading priority by coverage");
        check(brief.priorityAreas().get(0).developmentCount() == 2, "priority count is transparent");
        check(brief.signals().size() == 3, "source titles remain available as navigation");
        check(brief.actionHorizons().size() == 3, "provides a validation horizon without a market claim");
        check(brief.actionHorizons().get(0).action().contains("Regulation & compliance"),
                "near-term action is tailored to the leading Product priority");
        check(brief.decisionReadiness().contains("gate remains closed"), "does not weaken insight gate");

        var emptySnapshot = new ProductReportAdapter.Snapshot(null,
                ProductReportAdapter.Availability.INSUFFICIENT_EVIDENCE,
                ProductReportAdapter.InsufficientReason.NO_CURRENT_EDITION,
                asOf.minusDays(6), asOf, List.of(), List.of(), Map.of(), List.of());
        ProductExecutiveBrief empty = ProductExecutiveBrief.from(emptySnapshot, false);
        check(empty.actionHorizons().size() == 1, "empty coverage gives a focused remediation action");
        check(empty.headline().startsWith("No current"), "empty state stays explicit");
        System.out.println("ProductExecutiveBriefTest: ALL PASS");
    }

    private static CurrentProductNewsItem item(String factCode, String title, String sourceCode,
                                                String sourceName, int tier, String factType,
                                                LocalDate date) {
        return new CurrentProductNewsItem(factCode, factCode.hashCode(), title, sourceCode,
                sourceName, tier, "https://example.test/" + factCode, date, factType,
                "Verbatim evidence span for " + title + " that remains source-backed.",
                CurrentProductNewsTopic.from(java.util.Set.of(), factType), 0);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
