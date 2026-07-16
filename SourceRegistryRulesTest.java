import com.marketradar.domain.Source;
import com.marketradar.source.SourceRegistryRules;

/** Standalone regression tests for source-registration trust boundaries. */
public class SourceRegistryRulesTest {
    public static void main(String[] args) {
        var url = SourceRegistryRules.validateUrl("  https://NEWS.Example.COM/feed?q=insurance  ");
        check("news.example.com".equals(url.allowedHost()), "host is derived and normalized");
        check("https://news.example.com/feed?q=insurance".equals(url.fetchUrl()), "URL is normalized");

        rejects(() -> SourceRegistryRules.validateUrl("http://example.com/feed"), "HTTPS is required");
        rejects(() -> SourceRegistryRules.validateUrl("https://user:pass@example.com/feed"), "credentials rejected");
        rejects(() -> SourceRegistryRules.validateUrl("https://127.0.0.1/feed"), "IP literal rejected");
        rejects(() -> SourceRegistryRules.validateUrl("https://example.com:8443/feed"), "custom port rejected");
        rejects(() -> SourceRegistryRules.validateUrl("https://example.com/feed#section"), "fragment rejected");

        check("NEWS_VN".equals(SourceRegistryRules.validateCode("news_vn")), "code normalized");
        rejects(() -> SourceRegistryRules.validateCode("bad-code"), "invalid code rejected");
        check(SourceRegistryRules.validateTier(4) == 4, "tier 4 accepted");
        rejects(() -> SourceRegistryRules.validateTier(5), "invalid tier rejected");
        check("vi".equals(SourceRegistryRules.validateLanguage("VI")), "language normalized");
        check("ja".equals(SourceRegistryRules.validateLanguage("JA")), "registry languages accepted");
        rejects(() -> SourceRegistryRules.validateLanguage("fr"), "unsupported language rejected");

        check(SourceRegistryRules.validateType("rss") == Source.SourceType.RSS, "type normalized");
        rejects(() -> SourceRegistryRules.validateType("script"), "unknown parser rejected");
        check(SourceRegistryRules.parserSupportedForActivation(Source.SourceType.RSS), "RSS activation supported");
        check(SourceRegistryRules.parserSupportedForActivation(Source.SourceType.PDF), "PDF activation supported");
        check(!SourceRegistryRules.parserSupportedForActivation(Source.SourceType.HTML), "generic HTML held inactive");
        check(!SourceRegistryRules.parserSupportedForActivation(Source.SourceType.JSON), "generic JSON held inactive");
        System.out.println("SourceRegistryRulesTest: ALL PASS");
    }

    private static void rejects(Runnable operation, String message) {
        try {
            operation.run();
            throw new AssertionError(message);
        } catch (SourceRegistryRules.ValidationException expected) {
            // expected
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
