import com.marketradar.product.ProductBriefInsight;
import com.marketradar.report.ProductReportAdapter;

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
        System.out.println("ProductReportAdapterTest: ALL PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
