import com.marketradar.product.ProductBriefSynthesisRules;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** Standalone: javac -cp target/classes ProductBriefSynthesisRulesTest.java && java -cp target/classes:. ProductBriefSynthesisRulesTest */
public class ProductBriefSynthesisRulesTest {
    public static void main(String[] args) {
        var a = signal("F-1", 1, "AIA_VN", "AIA", "PRODUCT_LAUNCH", "VIETNAM", 88);
        var b = signal("F-2", 2, "MOF_ISA", "Generali", "PRODUCT_LAUNCH", "VIETNAM", 82);
        var sameDoc = signal("F-3", 1, "AIA_VN", "AIA", "PRODUCT_LAUNCH", "VIETNAM", 80);
        var drafts = ProductBriefSynthesisRules.synthesize(List.of(a, b, sameDoc));
        check(drafts.size() == 1, "one theme");
        check(drafts.get(0).factCodes().size() == 3, "up to two complementary facts per document");
        check(drafts.get(0).patternVi().contains("2 sự kiện độc lập"), "cross-cluster pattern");
        check(drafts.get(0).independentClusterCount() == 2, "same document does not inflate clusters");
        check(drafts.get(0).confidence().name().equals("MEDIUM"), "confidence rubric");

        var single = ProductBriefSynthesisRules.synthesize(List.of(a)).get(0);
        check(single.patternEn().contains("single-source signal"), "no fake trend");
        check(single.caveatEn().contains("no second independent source"), "caveat");

        var unrelatedA = genericSignal("F-9", 9, "SRC_A", "Company A", 79);
        var unrelatedB = genericSignal("F-10", 10, "SRC_B", "Company B", 78);
        check(ProductBriefSynthesisRules.synthesize(List.of(unrelatedA, unrelatedB)).isEmpty(),
                "unrelated fallback events must not become a market pattern");
        System.out.println("ProductBriefSynthesisRulesTest: ALL PASS");
    }

    private static ProductBriefSynthesisRules.Signal signal(String fact, long doc, String source,
                                                             String company, String type, String market, int score) {
        return new ProductBriefSynthesisRules.Signal(fact, doc, source, 2, company, null,
                company + " launches a wealth and legacy insurance plan", "Detailed wealth plan evidence",
                type, market, "OPENAI_COMPAT(gpt-5-mini)", "market-event-v1",
                LocalDate.of(2026, 7, 1), "CLUSTER-" + doc, 1, 1, "NONE",
                null, null, "ACTIVE", true,
                company + " có động thái sản phẩm tích lũy và di sản cụ thể.",
                company + " made a specific wealth and legacy product move.",
                score, Set.of("KIQ_1_OFFER_CHANGE", "KIQ_5_NEAR_TERM_ACTION"));
    }

    private static ProductBriefSynthesisRules.Signal genericSignal(String fact, long doc, String source,
                                                                    String company, int score) {
        return new ProductBriefSynthesisRules.Signal(fact, doc, source, 2, company, null,
                company + " activity", "Generic event evidence",
                "EVENT", "VIETNAM", "OPENAI_COMPAT(gpt-5-mini)", "market-event-v1",
                LocalDate.of(2026, 7, 1), "CLUSTER-" + doc, 1, 1, "NONE",
                null, null, "ACTIVE", true,
                company + " tổ chức một hoạt động.", company + " held an activity.",
                score, Set.of("KIQ_2_MARKET_PATTERN"));
    }

    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
    }
}
