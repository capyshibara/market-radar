import com.marketradar.product.BilingualTextPolicy;
import com.marketradar.product.ProductInsightWritingException;
import com.marketradar.product.ProductInsightWriter;

import java.util.List;

/** Standalone regression: language labels must not publish whole sentences in the wrong language. */
public class BilingualTextPolicyTest {
    public static void main(String[] args) {
        check(BilingualTextPolicy.safeDisplaySummary(
                        "Doanh nghiệp điều chỉnh quyền lợi sản phẩm.", true)
                        .startsWith("Doanh nghiệp"),
                "Vietnamese display summary remains available");
        check(BilingualTextPolicy.safeDisplaySummary(
                        "The insurer updated the product benefit.", false)
                        .startsWith("The insurer"),
                "English display summary remains available");
        check(BilingualTextPolicy.safeDisplaySummary(
                        "Product innovation and digital distribution require customer engagement.", true)
                        .isBlank(),
                "English prose is not displayed as Vietnamese summary");
        check(BilingualTextPolicy.safeDisplaySummary(
                        "Sản phẩm cần được kiểm tra với bằng chứng khách hàng.", false)
                        .isBlank(),
                "Vietnamese prose is not displayed as English summary");
        check(BilingualTextPolicy.safeDisplaySummary(
                        "A new health product requires review.", true).isBlank(),
                "short English sentence is not displayed as Vietnamese summary");

        BilingualTextPolicy.requireInsightLanguagePurity(validInsight());
        expectFailure(swappedVietnamese());
        expectFailure(swappedEnglish());
        expectFailure(swappedVietnameseAction());
        System.out.println("BilingualTextPolicyTest: ALL PASS");
    }

    private static ProductInsightWriter.WrittenInsight validInsight() {
        return new ProductInsightWriter.WrittenInsight(
                "Sản phẩm sức khỏe cần được kiểm tra", "Health product requires review",
                "Doanh nghiệp công bố quyền lợi mới.", "The insurer announced a new benefit.",
                "Đây là tín hiệu đơn nguồn, chưa phải xu hướng.", "This is a single-source signal, not a trend.",
                "Bộ phận Sản phẩm cần kiểm tra tác động với danh mục.", "Product should assess the portfolio impact.",
                "Chủ trì: Bộ phận Sản phẩm. Trong 30 ngày, kiểm tra đề xuất; tiêu chí là có bằng chứng khách hàng bổ sung.",
                "Owner: Product. Within 30 days, test the proposition; the criterion is additional customer evidence.",
                "Bằng chứng hiện chỉ từ một nguồn.", "Evidence currently comes from one source.", List.of("F-1"));
    }

    private static ProductInsightWriter.WrittenInsight swappedVietnamese() {
        ProductInsightWriter.WrittenInsight base = validInsight();
        return new ProductInsightWriter.WrittenInsight(
                base.headlineVi(), base.headlineEn(),
                "Product innovation and digital distribution require customer engagement.", base.whatEn(),
                base.patternVi(), base.patternEn(), base.soWhatVi(), base.soWhatEn(),
                base.nowWhatVi(), base.nowWhatEn(), base.caveatVi(), base.caveatEn(), base.citedFactCodes());
    }

    private static ProductInsightWriter.WrittenInsight swappedEnglish() {
        ProductInsightWriter.WrittenInsight base = validInsight();
        return new ProductInsightWriter.WrittenInsight(
                base.headlineVi(), base.headlineEn(), base.whatVi(),
                "Sản phẩm cần được kiểm tra với bằng chứng khách hàng.",
                base.patternVi(), base.patternEn(), base.soWhatVi(), base.soWhatEn(),
                base.nowWhatVi(), base.nowWhatEn(), base.caveatVi(), base.caveatEn(), base.citedFactCodes());
    }

    private static ProductInsightWriter.WrittenInsight swappedVietnameseAction() {
        ProductInsightWriter.WrittenInsight base = validInsight();
        return new ProductInsightWriter.WrittenInsight(
                base.headlineVi(), base.headlineEn(), base.whatVi(), base.whatEn(),
                base.patternVi(), base.patternEn(), base.soWhatVi(), base.soWhatEn(),
                "Owner: Product Innovation. Within 60 days, validate customer evidence and unit economics.",
                base.nowWhatEn(), base.caveatVi(), base.caveatEn(), base.citedFactCodes());
    }

    private static void expectFailure(ProductInsightWriter.WrittenInsight insight) {
        try {
            BilingualTextPolicy.requireInsightLanguagePurity(insight);
            throw new AssertionError("mixed-language insight must be rejected");
        } catch (ProductInsightWritingException expected) { }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
