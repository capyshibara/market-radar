package com.marketradar.intelligence;

import com.marketradar.domain.GeographyScope;
import com.marketradar.domain.Source;
import com.marketradar.domain.SourceAuthority;

import java.util.Locale;
import java.util.Set;

/**
 * Deterministic default metadata for source-level curation.
 *
 * <p>These defaults are intentionally conservative. They describe who publishes
 * the channel and its normal coverage, not what every article says. Fact-level
 * entity/geography resolution can narrow or override the defaults later.</p>
 */
public final class SourceIntelligencePolicy {

    private SourceIntelligencePolicy() {}

    private static final Set<String> REGULATORS = Set.of(
            "MOF_ISA", "NSO_VN", "SBV_MARKET_OPERATIONS", "HNX_GOVERNMENT_BONDS",
            "NFRA_CN", "FSA_JP", "FSC_TW", "HKMA", "HKIA", "FSC_KR", "FSS_KR",
            "MAS_SG", "OJK_ID", "BNM_MY", "IC_PH", "NAIC", "CBIRC_NEWS");

    private static final Set<String> INDUSTRY_BODIES = Set.of(
            "IAV_VN", "IAV_LIFE_PRODUCTS", "IAV_LIFE_DISCLOSURES", "IAV_LIFE_ACTIVITIES",
            "IAV_CHUBB_IGLOO_2026", "LIMRA");

    private static final Set<String> ESTABLISHED_MEDIA = Set.of(
            "TNCK_VN", "VNECONOMY", "TBNH", "CAFEF", "VIETNAMNET_LIFE",
            "VIR_INSURANCE", "VIETNAMPLUS_INSURANCE", "BAODAUTU_LIFE",
            "VIETNAMFINANCE_LIFE", "BNEWS_FINANCE_INSURANCE", "BIZHUB_INSURANCE",
            "BAOCHINHPHU_INSURANCE", "VNEXPRESS_INSURANCE", "THEINVESTOR_INSURANCE",
            "DDD_FINANCIAL_SERVICES", "AIR", "BT_SG", "INS_ASIA_NEWS", "INS_BIZ_ASIA");

    private static final Set<String> SPECIALIST_RESEARCH = Set.of(
            "SWISSRE_INST", "MUNICHRE", "MILLIMAN_VN_LIFE_LANDSCAPE_2026");

    private static final Set<String> PROFESSIONAL_SERVICES = Set.of("MCKINSEY_INS");

    private static final Set<String> OFFICIAL_COMPANY_PREFIXES = Set.of(
            "AIA_", "MANULIFE_", "PRUDENTIAL_", "CHUBB_", "FWD_", "GENERALI_",
            "HANWHA_", "DAIICHI_", "CATHAY_", "FUBON_", "SUNLIFE_", "SHINHAN_",
            "PINGAN_", "CHINALIFE_", "NIPPON_LIFE", "TOKIO_MARINE", "MSAD",
            "GREAT_EASTERN", "INCOME_SG", "THAILIFE_", "PRULIFE_", "PHILAM_");

    private static final Set<String> OFFICIAL_COMPANY_CODES = Set.of(
            "BVNT", "MB_AGEAS", "PHU_HUNG_LIFE", "BIDV_METLIFE", "MAP_LIFE",
            "MVI_LIFE", "TECHCOM_LIFE", "BAOVIET_HOLDINGS_NEWS",
            "TECHCOMBANK_IR_LIFE_RESULTS", "BIDV_METLIFE_AGENCY_2026",
            "VIETCOMBANK_FWD_DISTRIBUTION_2026");

    public static Metadata infer(Source source) {
        if (source == null) return new Metadata(SourceAuthority.UNKNOWN, GeographyScope.UNKNOWN, null);
        return infer(source.getCode(), source.getName(), source.getAllowedHost(), source.getLanguage());
    }

    public static Metadata infer(String code, String name, String host, String language) {
        String normalizedCode = upper(code);
        SourceAuthority authority = authority(normalizedCode, name, host);
        Market market = market(normalizedCode, host, language);
        return new Metadata(authority, market.scope(), market.code());
    }

    static SourceAuthority authority(String code, String name, String host) {
        if (code.contains("FINANCIALS") || code.contains("STATUTORY")) {
            return SourceAuthority.STATUTORY_DISCLOSURE;
        }
        if (REGULATORS.contains(code) || code.startsWith("MOF_") || code.startsWith("GOV_")) {
            return SourceAuthority.REGULATOR;
        }
        if (INDUSTRY_BODIES.contains(code) || code.startsWith("IAV_")) {
            return SourceAuthority.INDUSTRY_BODY;
        }
        if (SPECIALIST_RESEARCH.contains(code) || containsAny(lower(name), "institute", "research")) {
            return SourceAuthority.SPECIALIST_RESEARCH;
        }
        if (PROFESSIONAL_SERVICES.contains(code) || containsAny(lower(name), "mckinsey", "bcg", "boston consulting")) {
            return SourceAuthority.PROFESSIONAL_SERVICES;
        }
        if (ESTABLISHED_MEDIA.contains(code)) return SourceAuthority.ESTABLISHED_MEDIA;
        if (code.contains("SOCIAL") || containsAny(lower(host), "linkedin.com", "facebook.com", "x.com")) {
            return SourceAuthority.SOCIAL_OR_BLOG;
        }
        if (isOfficialCompanyCode(code, name)) return SourceAuthority.OFFICIAL_COMPANY;
        return SourceAuthority.UNKNOWN;
    }

    private static boolean isOfficialCompanyCode(String code, String name) {
        if (OFFICIAL_COMPANY_CODES.contains(code)) return true;
        return OFFICIAL_COMPANY_PREFIXES.stream().anyMatch(code::startsWith);
    }

    static Market market(String code, String host, String language) {
        // Language is not geography: a Vietnamese translation of a global report
        // must not silently become Vietnam-market evidence.
        if (code.contains("_VN") || code.endsWith("VN")) {
            return new Market(GeographyScope.VIETNAM, "VN");
        }
        if (code.contains("GLOBAL") || code.equals("AIA_GROUP_RESULTS")
                || code.startsWith("SWISSRE") || code.startsWith("MUNICHRE")
                || code.startsWith("MCKINSEY") || code.equals("LIMRA")) {
            return new Market(GeographyScope.GLOBAL, "GLOBAL");
        }
        String country = countryFromCode(code);
        if (country != null) return new Market(GeographyScope.COUNTRY, country);
        if (containsAny(lower(host), ".vn")) return new Market(GeographyScope.VIETNAM, "VN");
        return new Market(GeographyScope.UNKNOWN, null);
    }

    private static String countryFromCode(String code) {
        if (code.equals("NAIC")) return "US";
        if (code.endsWith("_CN")) return "CN";
        if (code.endsWith("_JP")) return "JP";
        if (code.endsWith("_TW")) return "TW";
        if (code.endsWith("_HK") || code.equals("HKIA") || code.equals("HKMA")) return "HK";
        if (code.endsWith("_KR")) return "KR";
        if (code.endsWith("_SG")) return "SG";
        if (code.endsWith("_ID")) return "ID";
        if (code.endsWith("_MY")) return "MY";
        if (code.endsWith("_PH")) return "PH";
        if (code.endsWith("_TH")) return "TH";
        return null;
    }

    private static boolean containsAny(String value, String... terms) {
        if (value == null) return false;
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }

    private static String upper(String value) {
        return value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
    }

    private static String lower(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    public record Metadata(SourceAuthority authority, GeographyScope marketScope, String marketCode) {}
    record Market(GeographyScope scope, String code) {}
}
