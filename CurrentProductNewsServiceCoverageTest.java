import com.marketradar.product.CurrentProductNewsItem;
import com.marketradar.product.CurrentProductNewsService;
import com.marketradar.product.CurrentProductNewsTopic;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Standalone regression for topic-diverse current-news selection. */
public class CurrentProductNewsServiceCoverageTest {
    public static void main(String[] args) {
        List<CurrentProductNewsItem> candidates = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            candidates.add(item("F-P" + i, CurrentProductNewsTopic.PRODUCT_AND_BENEFITS, i));
        }
        candidates.add(item("F-R", CurrentProductNewsTopic.REGULATION, 3));
        candidates.add(item("F-M", CurrentProductNewsTopic.MARKET_METRICS, 4));
        candidates.add(item("F-D", CurrentProductNewsTopic.DISTRIBUTION, 5));

        List<CurrentProductNewsItem> selected = CurrentProductNewsService.selectBalancedCoverage(candidates);
        check(selected.size() == CurrentProductNewsService.MAX_ITEMS, "report cap remains enforced");
        check(selected.stream().anyMatch(i -> i.topic() == CurrentProductNewsTopic.REGULATION),
                "regulation is not crowded out");
        check(selected.stream().anyMatch(i -> i.topic() == CurrentProductNewsTopic.MARKET_METRICS),
                "market metrics are not crowded out");
        check(selected.stream().anyMatch(i -> i.topic() == CurrentProductNewsTopic.DISTRIBUTION),
                "distribution is not crowded out");
        check(selected.get(0).topic() == CurrentProductNewsTopic.REGULATION,
                "selected cards return in stable editorial group order");
        check(selected.stream().filter(i -> i.topic() == CurrentProductNewsTopic.PRODUCT_AND_BENEFITS)
                        .findFirst().orElseThrow().factCode().equals("F-P0"),
                "newest item leads within its topic");
        System.out.println("CurrentProductNewsServiceCoverageTest: ALL PASS");
    }

    private static CurrentProductNewsItem item(String code, CurrentProductNewsTopic topic, long age) {
        return new CurrentProductNewsItem(code, Math.abs(code.hashCode()), "Source title", "SOURCE", "Source", 1,
                "https://example.test/" + code, LocalDate.of(2026, 7, 16).minusDays(age),
                "EVENT", "Exact verbatim source evidence.", topic, age);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
