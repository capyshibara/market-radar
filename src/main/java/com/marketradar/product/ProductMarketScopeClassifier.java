package com.marketradar.product;

import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.GeographyScope;
import com.marketradar.domain.RawDoc;
import com.marketradar.domain.Source;

import java.util.Locale;

/**
 * Deterministic market labelling for the Product news ledger.
 *
 * <p>The fact/event market wins over the publisher's corporate headquarters. Language,
 * publisher name and URL suffix are deliberately not geography proxies: a Vietnamese article
 * may describe Hong Kong, while a global publication may describe Vietnam. Unknown metadata is
 * shown as unknown/global rather than guessed into the domestic decision scope.</p>
 */
public final class ProductMarketScopeClassifier {
    private ProductMarketScopeClassifier() {}

    public record MarketPosition(ProductMarketScope scope, String geography) {}

    public static MarketPosition classify(EvidenceFact fact) {
        if (fact == null || fact.getRawDoc() == null) {
            return new MarketPosition(ProductMarketScope.INTERNATIONAL, "Global / regional");
        }
        MarketPosition factMarket = classify(fact.getMarketCode(), fact.getGeographyScope());
        if (known(fact.getMarketCode(), fact.getGeographyScope())) return factMarket;

        RawDoc doc = fact.getRawDoc();
        Source source = doc.getSource();
        if (source != null && source.hasExplicitIntelligenceMetadata()) {
            return classify(source.getDefaultMarketCode(), source.getDefaultMarketScope());
        }
        return new MarketPosition(ProductMarketScope.INTERNATIONAL, "Unknown market");
    }

    /** Public pure seam used by normalized facts, reports and regression tests. */
    public static MarketPosition classify(String marketCode, GeographyScope marketScope) {
        String code = upper(marketCode);
        if ("VN".equals(code) || marketScope == GeographyScope.VIETNAM) {
            return new MarketPosition(ProductMarketScope.VIETNAM, "Vietnam");
        }
        return new MarketPosition(ProductMarketScope.INTERNATIONAL, geographyLabel(code, marketScope));
    }

    /**
     * Legacy discovery seam retained for Deep Research results that have not entered the
     * normalized corpus. It may label an explicit source-code country token for display, but
     * never treats language, publisher/company wording, or the document host as event geography.
     */
    @Deprecated
    public static MarketPosition classify(String sourceCode, String sourceLanguage,
                                          String allowedHost, String documentUrl,
                                          String publisherName, String company) {
        String code = upper(sourceCode);
        if (token(code, "VN")) return classify("VN", GeographyScope.VIETNAM);
        return new MarketPosition(ProductMarketScope.INTERNATIONAL, sourceCodeGeography(code));
    }

    private static String geographyLabel(String code, GeographyScope scope) {
        String explicit = codeGeography(code);
        if (explicit != null) return explicit;
        if (scope == GeographyScope.GLOBAL) return "Global";
        if (scope == GeographyScope.REGIONAL) return "Regional";
        if (scope == GeographyScope.MULTI_MARKET) return "Multi-market";
        if (scope == GeographyScope.COUNTRY) return "Other country";
        return "Unknown market";
    }

    private static String sourceCodeGeography(String code) {
        for (String candidate : new String[]{"HK", "SG", "TW", "KR", "JP", "CN", "ID", "MY", "PH", "TH", "US", "GB"}) {
            if (token(code, candidate)) return codeGeography(candidate);
        }
        return "Global / regional";
    }

    private static String codeGeography(String code) {
        return switch (code) {
            case "VN" -> "Vietnam";
            case "HK" -> "Hong Kong";
            case "SG" -> "Singapore";
            case "TW" -> "Taiwan";
            case "KR" -> "South Korea";
            case "JP" -> "Japan";
            case "CN" -> "China";
            case "ID" -> "Indonesia";
            case "MY" -> "Malaysia";
            case "PH" -> "Philippines";
            case "TH" -> "Thailand";
            case "US" -> "United States";
            case "GB" -> "United Kingdom";
            case "APAC" -> "Asia-Pacific";
            case "GLOBAL" -> "Global";
            default -> null;
        };
    }

    private static boolean known(String marketCode, GeographyScope scope) {
        return marketCode != null && !marketCode.isBlank()
                || (scope != null && scope != GeographyScope.UNKNOWN);
    }

    private static boolean token(String code, String value) {
        String padded = "_" + code.replace('-', '_') + "_";
        return padded.contains("_" + value + "_");
    }

    private static String upper(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }
}
