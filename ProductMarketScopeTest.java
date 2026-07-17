import com.marketradar.product.CurrentProductNewsItem;
import com.marketradar.product.CurrentProductNewsScopeGroup;
import com.marketradar.product.CurrentProductNewsTopic;
import com.marketradar.product.ProductMarketScope;
import com.marketradar.product.ProductMarketScopeClassifier;

import java.time.LocalDate;
import java.util.List;

/** Standalone regression for domestic/international Product report separation. */
public class ProductMarketScopeTest {
    public static void main(String[] args) {
        var aiaVietnam = ProductMarketScopeClassifier.classify("AIA_VN", "en",
                "www.aia.com.vn", "https://www.aia.com.vn/product-notice", "AIA Vietnam", "AIA Vietnam");
        check(aiaVietnam.scope() == ProductMarketScope.VIETNAM,
                "event market beats an English-language source");

        var hongKong = ProductMarketScopeClassifier.classify("AIA_HK", "en",
                "www.aia.com.hk", "https://www.aia.com.hk/wealth-flexi", "AIA", "AIA Hong Kong");
        check(hongKong.scope() == ProductMarketScope.INTERNATIONAL,
                "Hong Kong development remains international");
        check("Hong Kong".equals(hongKong.geography()), "international geography remains visible");

        var manualVietnam = ProductMarketScopeClassifier.classify("MANUAL", "en",
                "manual.local", "https://example.com/article", "Vietnam Insurance Association", null);
        check(manualVietnam.scope() == ProductMarketScope.VIETNAM,
                "manual intake can still use the event entity to identify Vietnam");

        List<CurrentProductNewsScopeGroup> groups = CurrentProductNewsScopeGroup.from(List.of(
                item("F-VN", ProductMarketScope.VIETNAM, "Vietnam"),
                item("F-HK", ProductMarketScope.INTERNATIONAL, "Hong Kong")));
        check(groups.size() == 2, "reader always exposes both market scopes");
        check(groups.get(0).isVietnam() && groups.get(0).getItemCount() == 1,
                "Vietnam is the first decision scope");
        check(!groups.get(1).isVietnam() && groups.get(1).getItemCount() == 1,
                "international evidence is a separate comparison scope");
        System.out.println("ProductMarketScopeTest: ALL PASS");
    }

    private static CurrentProductNewsItem item(String code, ProductMarketScope scope, String geography) {
        return new CurrentProductNewsItem(code, Math.abs(code.hashCode()), "Source title", "SOURCE",
                "Source", 1, "https://example.test/" + code, LocalDate.of(2026, 7, 16),
                "EVENT", "Exact evidence.", CurrentProductNewsTopic.OTHER_PRODUCT_SIGNAL, 0,
                "Tóm tắt.", "Summary.", "en", false, scope, geography);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
