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

    public enum SourceType { RSS, HTML, PDF, JSON }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;          // vd: MOF_ISA

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 1000)
    private String fetchUrl;      // URL fetch trực tiếp (RSS feed / trang danh sách / PDF) — 1000: một số nguồn (vd MUNICHRE) dùng query string dài (AEM/GraphQL search params)

    @Column(nullable = false)
    private String allowedHost;   // host whitelist, exact match

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SourceType type;

    /** 1 = chính phủ/regulator, 2 = báo chính thống, 3 = báo thứ cấp, 4 = blog/MXH */
    @Column(nullable = false)
    private int tier;

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
    public String getAllowedHost() { return allowedHost; }
    public SourceType getType() { return type; }
    public int getTier() { return tier; }
    public String getLanguage() { return language; }
    public boolean isActive() { return active; }
    public boolean isUrlUnverified() { return urlUnverified; }

    /**
     * Nhãn thị trường hiển thị trong report (vd "Trung Quốc"), suy ra từ ngôn ngữ
     * nguồn — domain KHÔNG có trường "country" riêng. Đây là suy luận HIỂN THỊ
     * (xấp xỉ, vd "en" có thể là HK/SG/nhiều nơi), không phải fact; dùng cho dòng
     * mô tả "Danh mục · Thị trường" trong report, không phải căn cứ đối chiếu.
     * Batch 7 (i18n): tham số hoá theo ngôn ngữ hiển thị hiện tại.
     */
    public String getCountryLabel(String uiLang) {
        if ("vi".equals(uiLang)) {
            return switch (language) {
                case "vi" -> "Việt Nam";
                case "zh" -> "Trung Quốc";
                case "ko" -> "Hàn Quốc";
                case "ja" -> "Nhật Bản";
                default -> "Quốc tế";
            };
        }
        return switch (language) {
            case "vi" -> "Vietnam";
            case "zh" -> "China";
            case "ko" -> "Korea";
            case "ja" -> "Japan";
            default -> "International";
        };
    }

    public void setActive(boolean active) { this.active = active; }
    public void setUrlUnverified(boolean urlUnverified) { this.urlUnverified = urlUnverified; }
}
