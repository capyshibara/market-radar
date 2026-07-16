import com.marketradar.llm.LlmClient;
import com.marketradar.product.GroundedProductInsightWriter;
import com.marketradar.product.ProductBriefSynthesisRules;
import com.marketradar.product.ProductInsightWritingException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** Standalone fake-client test. It never makes a network or model call. */
public class GroundedProductInsightWriterTest {
    public static void main(String[] args) {
        FakeClient fake = new FakeClient("FAKE_WRITER(test)", validJson());
        var writer = new GroundedProductInsightWriter(fake, "fixed-test-prompt");
        var out = writer.write(draft());
        check(fake.calls == 1, "fake writer called once");
        check(fake.temperature == null, "model-compatible null temperature");
        check(out.citedFactCodes().equals(List.of("FACT-1")), "citation preserved");
        check(out.nowWhatEn().startsWith("Owner: Product"), "Product owns action");
        var repaired = writer.writeCorrected(draft(), "L1 rejected an unsupported product name");
        check(fake.calls == 2, "bounded correction makes one fresh writer call");
        check(fake.system.contains("never say a feature was launched, announced, withdrawn"),
                "repair explicitly prevents title/metadata-derived lifecycle claims");
        check(fake.system.contains("Every Vietnamese and English factual sentence must be independently entailed"),
                "repair requires bilingual factual entailment");
        check(repaired.nowWhatVi().equals(draft().nowWhatVi()),
                "deterministic Product action survives model wording variation");

        expectFailure(new GroundedProductInsightWriter(
                new FakeClient("FAKE_WRITER(test)", validJson().replace("FACT-1", "MISSING")), "p"),
                "unavailable citation fails closed");
        expectFailure(new GroundedProductInsightWriter(
                new FakeClient("STUB", validJson()), "p"), "STUB fails closed");
        System.out.println("GroundedProductInsightWriterTest: ALL PASS");
    }

    private static ProductBriefSynthesisRules.Draft draft() {
        var signal = new ProductBriefSynthesisRules.Signal("FACT-1", 1, "SOURCE-1", 1,
                "Insurer", "Plan", "Insurer launches a health plan", "Health plan launch",
                "PRODUCT_LAUNCH", "VIETNAM", "FAKE_WRITER(test)", "market-event-v1",
                LocalDate.of(2026, 7, 10), "EVENT-1", 1, 1, "NONE", null, null,
                "ACTIVE", true, "Doanh nghiệp ra mắt sản phẩm sức khỏe.",
                "The insurer launched a health product.", 90,
                Set.of("KIQ_1_OFFER_CHANGE", "KIQ_5_NEAR_TERM_ACTION"));
        return ProductBriefSynthesisRules.synthesize(List.of(signal)).get(0);
    }

    private static String validJson() {
        return """
                {"headlineVi":"Sản phẩm sức khỏe mới","headlineEn":"A new health product",
                "whatVi":"Doanh nghiệp ra mắt sản phẩm sức khỏe.",
                "whatEn":"The insurer launched a health product.",
                "patternVi":"Đây là tín hiệu đơn nguồn, chưa phải xu hướng.",
                "patternEn":"This is a single-source signal, not a trend.",
                "soWhatVi":"Bộ phận Sản phẩm cần kiểm tra khoảng trống trong danh mục sức khỏe.",
                "soWhatEn":"Product should test the health portfolio gap.",
                "nowWhatVi":"Chủ trì: Bộ phận Sản phẩm. Trong 30 ngày, kiểm tra đề xuất; tiêu chí là có bằng chứng khách hàng bổ sung.",
                "nowWhatEn":"Owner: Product. Within 30 days, test the proposition; the criterion is additional customer evidence.",
                "caveatVi":"Bằng chứng hiện chỉ từ một nguồn.",
                "caveatEn":"Evidence currently comes from one source.","factCodes":["FACT-1"]}
                """;
    }

    private static void expectFailure(GroundedProductInsightWriter writer, String message) {
        try { writer.write(draft()); throw new AssertionError(message); }
        catch (ProductInsightWritingException expected) { }
    }

    private static class FakeClient implements LlmClient {
        private final String provider;
        private final String response;
        int calls;
        Double temperature;
        String system;
        FakeClient(String provider, String response) { this.provider = provider; this.response = response; }
        public String complete(String system, String user, Double temperature) {
            calls++; this.system = system; this.temperature = temperature; return response;
        }
        public String providerName() { return provider; }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
