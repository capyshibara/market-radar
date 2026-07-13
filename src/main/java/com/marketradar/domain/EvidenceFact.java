package com.marketradar.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

/**
 * evidence_store — mỗi fact trỏ về một span NGUYÊN VĂN (giữ ngôn ngữ gốc)
 * trong một RawDoc. Invariant "zero claim không nguồn": mọi câu xuất bản sau này
 * phải truy được về một factId ở đây.
 */
@Entity
@Table(name = "evidence_facts")
public class EvidenceFact {

    public enum FactType { EVENT, PRODUCT_LAUNCH, FEE_CHANGE, REGULATION, METRIC }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Mã hiển thị trong report, vd F-001 — chính là "citation chip" */
    @Column(nullable = false, unique = true, length = 16)
    private String factCode;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private RawDoc rawDoc;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FactType factType;

    /** Span nguyên văn, KHÔNG dịch, KHÔNG paraphrase */
    @Lob
    @Column(columnDefinition = "CLOB", nullable = false)
    private String spanText;

    @Column(nullable = false, length = 8)
    private String spanLanguage;

    // ---- các trường hiển thị cho report (batch 1: đặt tay; sau này do extractor điền) ----
    private LocalDate eventDate;
    @Column(length = 256) private String company;
    @Column(length = 512) private String productName;
    @Column(length = 256) private String category;
    @Column(length = 256) private String categoryEn; // Batch 7 (i18n): bản tiếng Anh của category
    @Lob @Column(columnDefinition = "CLOB") private String summaryVi; // tóm tắt tiếng Việt, gắn nhãn bản dịch/tóm tắt
    @Lob @Column(columnDefinition = "CLOB") private String summaryEn; // Batch 7 (i18n): bản tiếng Anh của summary

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected EvidenceFact() {}

    public EvidenceFact(String factCode, RawDoc rawDoc, FactType factType,
                        String spanText, String spanLanguage) {
        this.factCode = factCode;
        this.rawDoc = rawDoc;
        this.factType = factType;
        this.spanText = spanText;
        this.spanLanguage = spanLanguage;
    }

    public Long getId() { return id; }
    public String getFactCode() { return factCode; }
    public RawDoc getRawDoc() { return rawDoc; }
    public FactType getFactType() { return factType; }
    public String getSpanText() { return spanText; }
    public String getSpanLanguage() { return spanLanguage; }
    public LocalDate getEventDate() { return eventDate; }
    public String getCompany() { return company; }
    public String getProductName() { return productName; }
    public String getCategory() { return category; }
    public String getCategoryEn() { return categoryEn; }
    public String getSummaryVi() { return summaryVi; }
    public String getSummaryEn() { return summaryEn; }
    public Instant getCreatedAt() { return createdAt; }

    /** Batch 7 (i18n): chọn theo ngôn ngữ hiển thị hiện tại — dùng trong template.
     * Tên khác "category"/"summary" (không phải overload) vì đã trùng chữ ký với
     * builder fluent category(String)/summaryVi(String) phía dưới. */
    public String categoryLabel(String lang) { return "vi".equals(lang) ? category : (categoryEn != null ? categoryEn : category); }
    public String summary(String lang) { return "vi".equals(lang) ? summaryVi : (summaryEn != null ? summaryEn : summaryVi); }

    /** Batch 6 (report redesign): tên ngôn ngữ hiển thị cho dòng "nguyên văn tiếng X" —
     * suy từ spanLanguage của CHÍNH fact này (không phải Source.language) vì đây là
     * ngôn ngữ thật của span trích, dùng cho invariant "luôn hiện nguyên văn gốc".
     * Batch 7 (i18n): tham số hoá theo ngôn ngữ hiển thị (không phải ngôn ngữ span). */
    public String getSpanLanguageLabel(String uiLang) {
        if ("vi".equals(uiLang)) {
            return switch (spanLanguage) {
                case "vi" -> "tiếng Việt";
                case "zh" -> "tiếng Trung";
                case "ko" -> "tiếng Hàn";
                case "ja" -> "tiếng Nhật";
                case "en" -> "tiếng Anh";
                default -> "ngôn ngữ gốc (" + spanLanguage + ")";
            };
        }
        return switch (spanLanguage) {
            case "vi" -> "Vietnamese";
            case "zh" -> "Chinese";
            case "ko" -> "Korean";
            case "ja" -> "Japanese";
            case "en" -> "English";
            default -> "original language (" + spanLanguage + ")";
        };
    }

    /**
     * Ngày hiển thị cho report (fix 2026-07-14): eventDate nếu có, không thì ngày
     * CÔNG BỐ của nguồn (publishedAt). KHÔNG bao giờ dùng fetchedAt — đó là thời
     * điểm crawl, không phải ngày của tin (xem ReportWindow). null → template hiện "—".
     */
    public LocalDate displayDate() {
        if (eventDate != null) return eventDate;
        if (rawDoc != null && rawDoc.getPublishedAt() != null) {
            return rawDoc.getPublishedAt().atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate();
        }
        return null;
    }

    public EvidenceFact eventDate(LocalDate d) { this.eventDate = d; return this; }
    public EvidenceFact company(String c) { this.company = c; return this; }
    public EvidenceFact productName(String p) { this.productName = p; return this; }
    public EvidenceFact category(String c) { this.category = c; return this; }
    public EvidenceFact categoryEn(String c) { this.categoryEn = c; return this; }
    public EvidenceFact summaryVi(String s) { this.summaryVi = s; return this; }
    public EvidenceFact summaryEn(String s) { this.summaryEn = s; return this; }
}
