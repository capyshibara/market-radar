import com.marketradar.product.ProductBriefInsight;
import com.marketradar.report.ProductReportAdapter;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Standalone visibility regression. Run after Maven compile:
 * javac -cp target/classes ProductReportAdapterTest.java
 * java -cp target/classes:. ProductReportAdapterTest
 */
public class ProductReportAdapterTest {
    public static void main(String[] args) {
        check(ProductReportAdapter.placement(ProductBriefInsight.PublicationDisposition.DECISION_READY)
                == ProductReportAdapter.Placement.EXECUTIVE, "HIGH belongs in executive summary");
        check(ProductReportAdapter.placement(ProductBriefInsight.PublicationDisposition.WATCH)
                == ProductReportAdapter.Placement.WATCH, "LOW must remain a watch signal, not a trend");
        check(ProductReportAdapter.placement(ProductBriefInsight.PublicationDisposition.REJECT) == null,
                "REJECT must never be rendered");
        check(ProductReportAdapter.placement(null)
                == null, "missing disposition must never be rendered");
        var watchBrief = new ProductReportAdapter.Snapshot(null,
                ProductReportAdapter.Availability.WATCH_BRIEF,
                ProductReportAdapter.InsufficientReason.NONE,
                LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 16),
                List.of(), List.of(), Map.of());
        check(watchBrief.watchBrief(), "WATCH_BRIEF must render as a distinct report state");
        check(!watchBrief.decisionReady(), "a Watch Brief must never be called decision-ready");
        check(watchBrief.watchBriefInsights().isEmpty(), "only persisted safe signals can render");
        System.out.println("ProductReportAdapterTest: ALL PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
