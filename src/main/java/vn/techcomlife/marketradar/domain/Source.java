package vn.techcomlife.marketradar.domain;

import jakarta.persistence.*;

/**
 * source_registry — bảng tier cố định (Invariant: whitelist + tier).
 * allowedHost là host DUY NHẤT được phép fetch cho nguồn này (exact match)
 * — mọi URL khác host đều bị SafeFetcher từ chối, kể cả link trong RSS entry.
 */
@Entity
@Table(name = "source_registry")
public class Source {

    public enum SourceType { RSS, HTML, PDF }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;          // vd: MOF_ISA

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String fetchUrl;      // URL fetch trực tiếp (RSS feed / trang danh sách / PDF)

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

    public void setActive(boolean active) { this.active = active; }
    public void setUrlUnverified(boolean urlUnverified) { this.urlUnverified = urlUnverified; }
}
