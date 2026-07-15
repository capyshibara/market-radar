import com.marketradar.intelligence.ProductEventTaxonomy;

public class ProductEventTaxonomyTest {
    public static void main(String[] args) {
        check("PRODUCT_WITHDRAWAL", "BIDV MetLife announced it will suspend deployment of insurance products", "EVENT");
        check("PRODUCT_LAUNCH", "Chubb Life launched two new Universal Life insurance products", "PRODUCT_LAUNCH");
        check("BENEFIT_CHANGE", "Prudential expands hospital coverage and doubles the selected hospital network", "EVENT");
        check("MARKETING_PROMOTION", "A promotional campaign offers e-vouchers to policyholders", "REGULATION");
        check("CUSTOMER_NEED_SIGNAL", "A customer survey found a record mortality protection gap", "METRIC");
        check("SERVICE_EXPERIENCE_CHANGE", "Insurer launched a new app feature for the customer journey", "PRODUCT_LAUNCH");
        check("DISTRIBUTION_CHANGE", "Income transfers its digital insurance platform under a new partnership", "EVENT");
        check("COMPETITIVE_PERFORMANCE", "First-year premium income increased 29%", "METRIC");
        check("PRODUCT_LAUNCH", "Great Eastern Pioneers New Integrated Shield Plan", "PRODUCT_LAUNCH");
        check("PRODUCT_LAUNCH", "Pru Life introduces its first Shari'ah compliant life protection", "PRODUCT_LAUNCH");
        check("CUSTOMER_NEED_SIGNAL", "Accumulation, decumulation and longevity solutions", "EVENT");
        check("SERVICE_EXPERIENCE_CHANGE", "Điều chỉnh thời gian gửi nhắc phí bảo hiểm", "FEE_CHANGE");
        check("CORPORATE_NEWS", "Insurer achieves reaccreditation", "REGULATION");
        check("MARKETING_PROMOTION", "Chương trình khuyến mại hoàn phí 20%", "FEE_CHANGE");
        check("PRODUCT_LAUNCH", "Cathay Life ra mắt sản phẩm bảo vệ và tích luỹ", "PRODUCT_LAUNCH");
        check("DISTRIBUTION_CHANGE", "Prudential và ngân hàng ký kết hợp tác chiến lược", "EVENT");
        System.out.println("ProductEventTaxonomyTest: ALL PASS");
    }

    private static void check(String expected, String text, String legacy) {
        String actual = ProductEventTaxonomy.classify(text, text, text, legacy).name();
        if (!expected.equals(actual)) throw new AssertionError(expected + " != " + actual + " for " + text);
    }
}
