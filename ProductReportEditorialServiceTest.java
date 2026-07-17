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
            int expectedExhibits = switch (cadence) {
                case WEEKLY -> 3;
                case MONTHLY -> 4;
                case QUARTERLY -> 5;
            };
            check(en.exhibits().size() == expectedExhibits,
                    cadence + " has the expected visual intelligence depth");
            check(vi.exhibits().size() == expectedExhibits,
                    cadence + " has matching Vietnamese exhibits");
            check(en.exhibits().stream().allMatch(exhibit -> exhibit.enabled()
                            && !exhibit.data().isEmpty()
                            && exhibit.citationCodes().startsWith("F-")),
                    cadence + " exhibits are enabled, structured and evidence-linked");
            check("BAR".equals(en.exhibits().get(0).type()),
                    cadence + " lead exhibit is a quantitative comparison");
            check(en.leadNarrative().length() > 300, cadence + " English analysis is substantive");
            check(vi.leadNarrative().length() > 250, cadence + " Vietnamese analysis is substantive");
            check(en.readerGuide().context().length() > 200,
                    cadence + " explains the context for non-expert readers");
            check(en.readerGuide().story().length() > 250,
                    cadence + " connects the signals into a plain-language story");
            check(en.readerGuide().recommendation().length() > 180,
                    cadence + " gives a concrete recommendation");
            check(vi.readerGuide().context().length() > 180
                            && vi.readerGuide().story().length() > 220,
                    cadence + " has an equally substantive Vietnamese reader guide");
            check(en.glossary().size() >= 5 && vi.glossary().size() == en.glossary().size(),
                    cadence + " has a bilingual quick glossary");
            check(en.glossary().stream().allMatch(term -> term.term().length() > 1
                            && term.definition().length() > 45),
                    cadence + " glossary entries are explanatory, not labels only");
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
