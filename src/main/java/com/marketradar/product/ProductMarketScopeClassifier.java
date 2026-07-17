package com.marketradar.product;

import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.RawDoc;
import com.marketradar.domain.Source;

import java.net.URI;
import java.util.Locale;

/**
 * Deterministic market labelling for the Product news ledger.
 *
 * <p>The event market wins over the publisher's corporate headquarters. A Vietnam entity,
 * Vietnam URL or Vietnam-labelled source is domestic; everything else is an international
 * signal with a best-effort display geography. This is presentation metadata only and never
 * changes the underlying evidence or its verification state.</p>
 */
public final class ProductMarketScopeClassifier {
    private ProductMarketScopeClassifier() {}

    public record MarketPosition(ProductMarketScope scope, String geography) {}

    public static MarketPosition classify(EvidenceFact fact) {
        if (fact == null || fact.getRawDoc() == null) {
            return new MarketPosition(ProductMarketScope.INTERNATIONAL, "Global / regional");
        }
        RawDoc doc = fact.getRawDoc();
        Source source = doc.getSource();
        return classify(source == null ? null : source.getCode(),
                source == null ? null : source.getLanguage(),
                source == null ? null : source.getAllowedHost(),
                doc.getUrl(), doc.getPublisherName(), fact.getCompany());
    }

    /** Public pure seam for deterministic regression tests. */
    public static MarketPosition classify(String sourceCode, String sourceLanguage,
                                          String allowedHost, String documentUrl,
                                          String publisherName, String company) {
        String code = upper(sourceCode);
        String sourceHost = lower(allowedHost);
        String documentHost = host(documentUrl);
        String entity = lower(join(publisherName, company));
        boolean vietnam = "vi".equals(lower(sourceLanguage))
                || vietnamHost(sourceHost) || vietnamHost(documentHost)
                || token(code, "VN")
                || entity.contains("vietnam") || entity.contains("việt nam");
        if (vietnam) return new MarketPosition(ProductMarketScope.VIETNAM, "Vietnam");
        return new MarketPosition(ProductMarketScope.INTERNATIONAL,
                internationalGeography(code, sourceHost, documentHost));
    }

    private static String internationalGeography(String code, String sourceHost, String documentHost) {
        String hosts = sourceHost + " " + documentHost;
        if (token(code, "HK") || countryHost(hosts, "hk")) return "Hong Kong";
        if (token(code, "SG") || countryHost(hosts, "sg")) return "Singapore";
        if (token(code, "TW") || countryHost(hosts, "tw")) return "Taiwan";
        if (token(code, "KR") || countryHost(hosts, "kr")) return "South Korea";
        if (token(code, "JP") || countryHost(hosts, "jp")) return "Japan";
        if (token(code, "CN") || countryHost(hosts, "cn")) return "China";
        if (token(code, "ID") || countryHost(hosts, "id")) return "Indonesia";
        if (token(code, "MY") || countryHost(hosts, "my")) return "Malaysia";
        if (token(code, "PH") || countryHost(hosts, "ph")) return "Philippines";
        if (token(code, "TH") || countryHost(hosts, "th")) return "Thailand";
        return "Global / regional";
    }

    private static boolean vietnamHost(String host) {
        return host.endsWith(".vn") || host.contains(".vn:");
    }

    private static String host(String url) {
        if (url == null || url.isBlank()) return "";
        try { return lower(URI.create(url.strip()).getHost()); }
        catch (Exception ignored) { return ""; }
    }

    private static boolean token(String code, String value) {
        String padded = "_" + code.replace('-', '_') + "_";
        return padded.contains("_" + value + "_");
    }

    private static boolean countryHost(String hosts, String cc) {
        return hosts.contains("." + cc + " ") || hosts.endsWith("." + cc)
                || hosts.contains("." + cc + ".");
    }

    private static String join(String first, String second) {
        return (first == null ? "" : first) + " " + (second == null ? "" : second);
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String upper(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }
}
