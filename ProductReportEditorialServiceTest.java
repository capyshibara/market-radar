import com.marketradar.product.ProductReportCadence;
import com.marketradar.report.ProductReportEditorialService;

import java.util.Locale;

/** Standalone regression for the separate human-curated Product report layer. */
public class ProductReportEditorialServiceTest {
    public static void main(String[] args) {
        ProductReportEditorialService service = new ProductReportEditorialService();
        for (ProductReportCadence cadence : ProductReportCadence.values()) {
            var en = service.current(cadence, Locale.ENGLISH);
            var vi = service.current(cadence, Locale.forLanguageTag("vi"));
            check(en.takeaways().size() == 3, cadence + " has three English editorial takeaways");
            check(vi.takeaways().size() == 3, cadence + " has three Vietnamese editorial takeaways");
            check(en.decisions().size() == 3, cadence + " has three decision prompts");
            check(en.chart() != null && en.chart().citationCode().startsWith("F-"),
                    cadence + " chart is evidence-linked");
            check(en.leadNarrative().length() > 300, cadence + " English analysis is substantive");
            check(vi.leadNarrative().length() > 250, cadence + " Vietnamese analysis is substantive");
            check(en.marketBridge() != null, cadence + " has a domestic/international bridge");
            check(en.marketBridge().domesticRead().length() > 80,
                    cadence + " has a substantive domestic read");
            check(en.marketBridge().internationalRead().length() > 80,
                    cadence + " has a substantive international read");
            check(en.marketBridge().decisionQuestion().endsWith("?"),
                    cadence + " bridge ends in a decision question");
            check("HUMAN_CURATED".equals(en.status()), cadence + " is labelled human-curated");
            check(!en.citedFactCodes().isEmpty(), cadence + " retains an auditable fact register");
        }
        System.out.println("ProductReportEditorialServiceTest: ALL PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
