import com.marketradar.product.CurrentProductNewsTopic;
import com.marketradar.report.ProductSourceStoryService;

/** Standalone regression for the explained source-story reading layer. */
public class ProductSourceStoryServiceTest {
    public static void main(String[] args) {
        var split = ProductSourceStoryService.splitSourceText(
                "Opening context. Exact evidence sentence. Remaining article.",
                "Exact evidence sentence.");
        check(split.matched(), "exact evidence is located inside the crawled document");
        check("Opening context. ".equals(split.before()), "text before evidence is preserved");
        check("Exact evidence sentence.".equals(split.evidence()), "evidence is preserved verbatim");
        check(" Remaining article.".equals(split.after()), "text after evidence is preserved");

        var unmatched = ProductSourceStoryService.splitSourceText("Stored article", "Different span");
        check(!unmatched.matched(), "a missing span is never falsely highlighted");
        check("Stored article".equals(unmatched.fullText()), "unmatched source remains readable");

        for (CurrentProductNewsTopic topic : CurrentProductNewsTopic.values()) {
            check(topic.readerContext(false).length() > 100,
                    topic + " has substantive English background");
            check(topic.readerContext(true).length() > 100,
                    topic + " has substantive Vietnamese background");
            check(topic.productMeaning(false).length() > 100,
                    topic + " explains English Product meaning");
            check(topic.productMeaning(true).length() > 100,
                    topic + " explains Vietnamese Product meaning");
        }
        System.out.println("ProductSourceStoryServiceTest: ALL PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
