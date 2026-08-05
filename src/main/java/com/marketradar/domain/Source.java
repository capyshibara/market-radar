package com.marketradar.domain;

import jakarta.persistence.*;

/**
 * source_registry — bảng tier cố định (Invariant: whitelist + tier).
 * allowedHost là host DUY NHẤT được phép fetch cho nguồn này (exact match)
 * — mọi URL khác host đều bị SafeFetcher từ chối, kể cả link trong RSS entry.
 */
@Entity
@Table(name = "source_registry")
public class Source {

    /**
     * SITEMAP is intentionally separate from RSS. Both are XML, but a sitemap is an
     * archive/discovery index whose entries point at articles and carry lastmod,
     * while RSS normally carries titles/descriptions and only a short recent window.
     */
    public enum SourceType { RSS, SITEMAP, HTML, PDF, JSON }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;          // vd: MOF_ISA

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 1000)
    private String fetchUrl;      // URL fetch trực tiếp (RSS feed / trang danh sách / PDF) — 1000: một số nguồn (vd MUNICHRE) dùng query string dài (AEM/GraphQL search params)

    /** Trang con người bấm vào xem được — null nghĩa là fetchUrl cũng dùng được cho việc đó
     *  (đa số nguồn HTML/RSS/PDF: đúng là cùng 1 URL). Cần khác fetchUrl khi fetchUrl là API
     *  POST/GraphQL (vd MOF_ISA cần body rootCategoryId, CATHAY_VN cần GraphQL body) — bấm
     *  thẳng fetchUrl trên trình duyệt (luôn là GET, không có body) sẽ trông như hỏng dù nguồn
     *  hoàn toàn sống; xem SourceHealthCheckService/IngestionJob để biết method/body thật. */
    @Column(length = 1000)
    private String browseUrl;

    @Column(nullable = false)
    private String allowedHost;   // host whitelist, exact match

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SourceType type;

    /**
     * Legacy presentation tier retained for database/backward compatibility only.
     * It used to mix geography and credibility and MUST NOT be used for new
     * publication, weighting or corroboration decisions. Use authority and
     * defaultMarketScope/defaultMarketCode instead.
     */
    @Column(nullable = false)
    private int tier;

    /** Source quality/authority: the CFO curation axis. Nullable during legacy backfill. */
    @Enumerated(EnumType.STRING)
    @Column(name = "authority", length = 32)
    private SourceAuthority authority;

    /** Default source coverage only; fact-level geography may override it. */
    @Enumerated(EnumType.STRING)
    @Column(name = "default_market_scope", length = 24)
    private GeographyScope defaultMarketScope;

    /** ISO-like market code such as VN, SG, GB, US, APAC or GLOBAL. */
    @Column(name = "default_market_code", length = 16)
    private String defaultMarketCode;

    @Column(nullable = false, length = 8)
    private String language;      // vi / zh / en

    @Column(nullable = false)
    private boolean active = true;

    /** true = URL chưa verify được (đặt trong môi trường offline) — phải kiểm tra trước demo */
    @Column(nullable = false)
    private boolean urlUnverified = true;

    protected Source() {}

    public Source(String code, String name, String fetchUrl, String allowedHost,
                  SourceType type, int tier, String language) {
        this.code = code;
        this.name = name;
        this.fetchUrl = fetchUrl;
        this.allowedHost = allowedHost;
        this.type = type;
        this.tier = tier;
        this.language = language;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getFetchUrl() { return fetchUrl; }
    public String getBrowseUrl() { return browseUrl; }
    /** URL để hiển thị làm hyperlink cho người — browseUrl nếu có, không thì fetchUrl. */
    public String getDisplayUrl() { return browseUrl != null && !browseUrl.isBlank() ? browseUrl : fetchUrl; }
    public String getAllowedHost() { return allowedHost; }
    public SourceType getType() { return type; }
    public int getTier() { return tier; }
    public SourceAuthority getAuthority() {
        return authority == null ? legacyAuthority(tier) : authority;
    }
    public GeographyScope getDefaultMarketScope() {
        return defaultMarketScope == null ? legacyMarketScope(tier) : defaultMarketScope;
    }
    public String getDefaultMarketCode() {
        if (defaultMarketCode != null && !defaultMarketCode.isBlank()) return defaultMarketCode;
        return getDefaultMarketScope() == GeographyScope.VIETNAM ? "VN" : null;
    }
    /** True when the new independent authority/market axes have been persisted. */
    public boolean hasExplicitIntelligenceMetadata() {
        return authority != null && defaultMarketScope != null;
    }
    public String getLanguage() { return language; }
    public boolean isActive() { return active; }
    public boolean isUrlUnverified() { return urlUnverified; }

    /** Compatibility display helper backed by explicit source market metadata.
     * Language is never used as a geography proxy. Fact-level market still wins. */
    public String getCountryLabel(String uiLang) {
        String code = getDefaultMarketCode();
        if ("vi".equals(uiLang)) {
            return switch (code == null ? "" : code) {
                case "VN" -> "Việt Nam";
                case "CN" -> "Trung Quốc";
                case "KR" -> "Hàn Quốc";
                case "JP" -> "Nhật Bản";
                case "HK" -> "Hồng Kông";
                case "SG" -> "Singapore";
                case "US" -> "Hoa Kỳ";
                case "GB" -> "Vương quốc Anh";
                case "GLOBAL" -> "Toàn cầu";
                default -> code == null ? "Chưa xác định" : code;
            };
        }
        return switch (code == null ? "" : code) {
            case "VN" -> "Vietnam";
            case "CN" -> "China";
            case "KR" -> "South Korea";
            case "JP" -> "Japan";
            case "HK" -> "Hong Kong";
            case "SG" -> "Singapore";
            case "US" -> "United States";
            case "GB" -> "United Kingdom";
            case "GLOBAL" -> "Global";
            default -> code == null ? "Unknown market" : code;
        };
    }

    public void setActive(boolean active) { this.active = active; }
    public void setUrlUnverified(boolean urlUnverified) { this.urlUnverified = urlUnverified; }
    public void setTier(int tier) { this.tier = tier; }
    public void setBrowseUrl(String browseUrl) { this.browseUrl = browseUrl; }
    public void setIntelligenceMetadata(SourceAuthority authority,
                                        GeographyScope defaultMarketScope,
                                        String defaultMarketCode) {
        this.authority = authority == null ? SourceAuthority.UNKNOWN : authority;
        this.defaultMarketScope = defaultMarketScope == null ? GeographyScope.UNKNOWN : defaultMarketScope;
        this.defaultMarketCode = defaultMarketCode == null || defaultMarketCode.isBlank()
                ? null : defaultMarketCode.strip().toUpperCase(java.util.Locale.ROOT);
    }

    private static SourceAuthority legacyAuthority(int tier) {
        return switch (tier) {
            case 1 -> SourceAuthority.OFFICIAL_COMPANY;
            case 2 -> SourceAuthority.ESTABLISHED_MEDIA;
            case 3 -> SourceAuthority.OTHER_PUBLISHER;
            default -> SourceAuthority.UNKNOWN;
        };
    }

    private static GeographyScope legacyMarketScope(int tier) {
        // The old tier mixed authority and geography. A tier value therefore
        // cannot safely recover market coverage: tier-1 includes Vietnamese
        // regulators as well as foreign regulators, while tier-2 includes both
        // domestic and international publishers. Startup metadata migration
        // persists the real value for known sources; genuinely legacy rows stay
        // UNKNOWN until an operator curates them.
        return GeographyScope.UNKNOWN;
    }

    /** Startup migrations may repair a known source whose listing/API moved. */
    public void reconfigure(String name, String fetchUrl, String allowedHost,
                            SourceType type, int tier, String language) {
        this.name = name;
        this.fetchUrl = fetchUrl;
        this.allowedHost = allowedHost;
        this.type = type;
        this.tier = tier;
        this.language = language;
    }
}
