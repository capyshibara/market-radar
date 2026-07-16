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
        check(fragment.contains("INSUFFICIENT_EVIDENCE"), "explicit insufficient state");
        check(fragment.contains("CURRENT WATCH BRIEF"), "explicit current Watch Brief state");
        check(fragment.contains("productWatchBriefInsights"), "Watch Brief renders only safe adapter signals");
        check(fragment.contains("do not treat it as a market-wide trend"),
                "Watch Brief cannot be represented as a market conclusion");
        check(fragment.contains("productWatchSignals"), "separate WATCH rendering");
        check(fragment.contains("references"), "references derive from rendered adapter snapshot");
        System.out.println("ProductReportContractTest: ALL PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
