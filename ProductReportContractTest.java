import com.marketradar.product.ProductReportCadence;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/** Standalone exact-period and no-legacy-template regression. */
public class ProductReportContractTest {
    public static void main(String[] args) throws Exception {
        LocalDate end = LocalDate.of(2026, 7, 16);
        check(ProductReportCadence.WEEKLY.start(end).equals(LocalDate.of(2026, 7, 10)), "7-day inclusive start");
        check(ProductReportCadence.MONTHLY.start(end).equals(LocalDate.of(2026, 6, 17)), "30-day inclusive start");
        check(ProductReportCadence.QUARTERLY.start(end).equals(LocalDate.of(2026, 4, 18)), "90-day inclusive start");
        check(!ProductReportCadence.WEEKLY.matches(ProductReportCadence.QUARTERLY.start(end), end, end),
                "90-day edition cannot masquerade as current weekly");

        for (String name : new String[]{"weekly-report.html", "monthly-report.html",
                "product-brief.html", "email-summary.html"}) {
            String html = Files.readString(Path.of("src/main/resources/templates", name));
            check(html.contains("fragments/product-report :: body"), name + " uses unified Product model");
            for (String legacy : new String[]{"execClaims", "claimsByDoc", "chapterArticles",
                    "recommendations", "allFacts", "vnFacts", "regionalFacts"}) {
                check(!html.contains(legacy), name + " has no legacy fallback " + legacy);
            }
        }
        String fragment = Files.readString(Path.of("src/main/resources/templates/fragments/product-report.html"));
        check(fragment.contains("editorialBrief.leadNarrative"),
                "human editorial argument leads the report");
        check(fragment.contains("editorialBrief.takeaways"),
                "human-curated Product takeaways are rendered");
        check(fragment.contains("editorialBrief.decisions"),
                "report translates analysis into Product decisions");
        check(fragment.contains("editorialBrief.readerGuide.context")
                        && fragment.contains("editorialBrief.readerGuide.story")
                        && fragment.contains("editorialBrief.readerGuide.recommendation"),
                "report orients non-experts with context, story and recommendation");
        check(fragment.contains("editorialBrief.glossary"),
                "report explains specialist terms in a quick glossary");
        check(fragment.contains("WHAT THE EVIDENCE SAYS")
                        && fragment.contains("WHY THIS MATTERS"),
                "editorial insights use explicit plain-language reading labels");
        check(fragment.contains("editorialHeroExhibit")
                        && fragment.contains("editorialDashboardExhibits"),
                "report includes a lead chart and visual intelligence dashboard");
        check(fragment.contains("product-report-exhibit"),
                "all exhibits use the shared web/PDF component");
        check(fragment.contains("editorialBrief.marketBridge.domesticRead"),
                "human editorial separates the domestic read");
        check(fragment.contains("editorialBrief.marketBridge.internationalRead"),
                "human editorial separates international comparison signals");
        check(fragment.contains("editorialBrief.marketBridge.vietnamImplication"),
                "report explicitly bridges international evidence back to Vietnam");
        check(fragment.contains("currentProductNewsScopes"),
                "source developments are partitioned by market before topic");
        check(fragment.contains("n.marketScopeLabelEn") && fragment.contains("n.marketScopeLabelVi"),
                "each source development carries a bilingual market label");
        check(fragment.contains("executiveBrief.modeLabel"), "human-readable evidence/watch/decision brief state");
        check(fragment.contains("productWatchBriefInsights"), "Watch Brief renders only safe adapter signals");
        check(fragment.contains("not market-wide conclusions"),
                "Watch Brief cannot be represented as a market conclusion");
        check(fragment.contains("productWatchSignals"), "separate WATCH rendering");
        check(fragment.contains("currentProductNews"), "current cited-news layer is rendered independently");
        check(fragment.contains("n.originalEvidence"), "news layer renders exact source evidence, not generated summary");
        check(fragment.contains("n.displaySummaryVi") && fragment.contains("n.displaySummaryEn"),
                "news layer renders a report-language summary when safely available");
        check(fragment.contains("/report/story/") && fragment.contains("Read the full explained story")
                        && fragment.contains("item.citationCodeList"),
                "every report layer links synthesis back to an explained source story");
        check(fragment.contains("Original-language source evidence"), "original evidence is explicitly labelled by language");
        check(fragment.contains("immutable evidence layer"),
                "human synthesis is explicitly separated from immutable evidence");
        check(fragment.contains("METHOD · COMPACT"),
                "rules and publication logic are kept in a compact method note");
        check(!fragment.contains("priority-grid"),
                "legacy rule-heavy priority cards no longer dominate the report");
        check(fragment.contains("references"), "references derive from rendered adapter snapshot");
        String exhibit = Files.readString(Path.of(
                "src/main/resources/templates/fragments/product-report-exhibit.html"));
        for (String type : new String[]{"BAR", "KPI", "TIMELINE", "FLOW", "MATRIX", "ROADMAP"}) {
            check(exhibit.contains("exhibit.type == '" + type + "'"),
                    "shared exhibit component renders " + type);
        }
        String styles = Files.readString(Path.of(
                "src/main/resources/templates/fragments/product-report-styles.html"));
        check(styles.contains("/assets/meridian/d-monochrome-blue.svg"),
                "Product report restores the design-system canvas pattern");
        String editor = Files.readString(Path.of(
                "src/main/resources/templates/product-report-editor.html"));
        check(editor.contains("Exhibit Studio") && editor.contains("exhibit."),
                "human review can edit structured exhibits");
        check(editor.contains("guide.context") && editor.contains("guide.story")
                        && editor.contains("guide.recommendation") && editor.contains("glossary."),
                "human review can edit the non-expert guide and glossary");
        String sourceStory = Files.readString(Path.of(
                "src/main/resources/templates/product-source-story.html"));
        check(sourceStory.contains("story.retelling") && sourceStory.contains("story.context")
                        && sourceStory.contains("story.productMeaning"),
                "source story retells and explains the evidence in the report language");
        check(sourceStory.contains("story.sourceText.before")
                        && sourceStory.contains("story.sourceText.evidence")
                        && sourceStory.contains("story.sourceText.after"),
                "source story highlights the exact evidence inside the full crawled text");
        check(sourceStory.contains("Open original") && sourceStory.contains("FULL CRAWLED DOCUMENT"),
                "source story preserves a path to the publisher and complete stored document");
        System.out.println("ProductReportContractTest: ALL PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
