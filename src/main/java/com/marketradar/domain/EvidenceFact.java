package com.marketradar.domain;

import jakarta.persistence.*;
import com.marketradar.intelligence.EntityResolutionRules;
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
    /** Explicit semantic dates from extractor v3; null means source did not state them. */
    private LocalDate occurredDate;
    private LocalDate effectiveDate;
    private LocalDate expiryDate;
    private LocalDate forecastHorizon;
    @Column(length = 256) private String company;
    @Column(length = 512) private String productName;
    @Column(length = 256) private String category;
    @Column(length = 256) private String categoryEn; // Batch 7 (i18n): bản tiếng Anh của category
    @Lob @Column(columnDefinition = "CLOB") private String summaryVi; // tóm tắt tiếng Việt, gắn nhãn bản dịch/tóm tắt
    @Lob @Column(columnDefinition = "CLOB") private String summaryEn; // Batch 7 (i18n): bản tiếng Anh của summary

    /**
     * 2026-08-03 (Router — feedback "phân loại linh hoạt hơn dựa trên file báo cáo mẫu thật"):
     * các nhãn Router gán CHO FACT, trước khi Analyst chạy — thay cho cách cũ là gán biBucket
     * SAU KHI viết câu, lồng trong prompt Interpreter (InterpretedClaim#biBucket, nay bỏ hẳn).
     * Tất cả nullable — bảng evidence_facts đã có dữ liệu từ trước, xem lý do ở Boolean
     * vietnamOnly của DeepResearchRun (primitive/NOT NULL sẽ làm ALTER TABLE crash).
     *
     * biBucket: 1 trong 8 giá trị BiFinding.* (7 cũ + DEEP_DIVE mới, xem BiFinding).
     * subjectKey: tên công ty đã chuẩn hoá (CompetitorRegistry) / tên chủ đề (COMPETITIVE_THEME)
     *   / tên cặp so sánh (STRATEGIC_COMPARISON) — null nếu bucket không cần nhóm.
     * highlightCardLabel: CHỈ có ý nghĩa khi bucket=COMPANY_EVENT — nhãn ngắn Router tự đặt
     *   theo đúng nội dung (PRODUCT_LAUNCH, BANCASSURANCE, HIRING_SIGNAL...), danh sách MỞ —
     *   trừ 1 giá trị bắt buộc dùng đúng lúc: "PARENT_GROUP" khi fact nói về kết quả/số liệu
     *   công ty MẸ toàn cầu chứ không tách riêng thị trường Việt Nam (ca thật thấy trong file
     *   mẫu CFO gửi: "Vietnam not broken out separately... No Vietnam-specific questions in
     *   investor call Q&A" — đúng vấn đề quy kết sai công ty CFO nêu từ đầu, xử lý bằng cách
     *   hiện minh bạch + cảnh báo, không lọc ẩn).
     * severity/severityTrend: CHỈ bucket=TECH_AI_SIGNAL. severity null = AI_SIZING (số liệu thị
     *   trường); có giá trị = AI_THREATMAP (đánh giá theo công ty). severityTrend (RISING/
     *   FALLING/STABLE) là cờ phụ — file mẫu có "MEDIUM, RISING", không chỉ 3 mức tĩnh.
     * eventDateRangeStart/End: CHỈ bucket=SCHEDULED_EVENT hoặc COMPANY_EVENT khi nguồn nêu rõ
     *   ngày/khoảng ngày — để dựng được lưới lịch thật (file mẫu slide 3/4 là lưới tháng thật,
     *   không phải bảng 2 cột) thay vì chuỗi tự do eventDateLabel cũ ở BiFinding.
     * kpiLabel/kpiValue: CHỈ khi fact là 1 CHỈ SỐ độc lập không gắn 1 công ty cụ thể (vd "GDP
     *   Growth" / "7.9%", "Insurtech market size (2034)" / "USD 878.7 million") — dùng cho
     *   MACRO_ECONOMIC và nhánh AI_SIZING của TECH_AI_SIGNAL. kpiValue giữ nguyên chuỗi đã định
     *   dạng (không tách number/unit riêng — định dạng thật quá đa dạng để ép khuôn cứng).
     * highlight: có đủ quan trọng để lên trang Tóm tắt điều hành không — quyết định NỘI DUNG
     *   thật (materiality), thay cho rule cũ "slot==EXEC_SUMMARY" (chỉ là do bước nào sinh ra
     *   câu, không phải do câu đó quan trọng hay không).
     */
    @Column(length = 32) private String biBucket;
    @Column(length = 256) private String subjectKey;
    @Column(length = 64) private String highlightCardLabel;
    @Column(length = 16) private String severity;
    @Column(length = 16) private String severityTrend;
    private LocalDate eventDateRangeStart;
    private LocalDate eventDateRangeEnd;
    @Column(length = 128) private String kpiLabel;
    @Column(length = 128) private String kpiValue;
    private Boolean highlight;

    // ---- reusable curation dimensions (independent from a report layout) ----
    @Enumerated(EnumType.STRING)
    @Column(name = "intelligence_topic", length = 32)
    private IntelligenceTopic intelligenceTopic;

    @Enumerated(EnumType.STRING)
    @Column(name = "temporal_role", length = 24)
    private TemporalRole temporalRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_authority", length = 32)
    private SourceAuthority sourceAuthority;

    @Enumerated(EnumType.STRING)
    @Column(name = "geography_scope", length = 24)
    private GeographyScope geographyScope;

    @Column(name = "market_code", length = 16)
    private String marketCode;

    @Column(name = "subject_entity_key", length = 64)
    private String subjectEntityKey;

    @Column(name = "subject_entity_name", length = 256)
    private String subjectEntityName;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_entity_kind", length = 32)
    private EntityResolutionRules.EntityKind subjectEntityKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_resolution_status", length = 24)
    private EntityResolutionRules.Status entityResolutionStatus;

    @Lob
    @Column(name = "entity_resolution_detail", columnDefinition = "CLOB")
    private String entityResolutionDetail;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    /** Null for legacy/manual seed facts created before versioned extraction runs. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "extraction_run_id")
    private FactExtractionRun extractionRun;

    /**
     * Old editions are retained for audit and claim traceability. Only the latest
     * successful edition is active for synthesis/report reads.
     */
    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean active = true;

    private Instant supersededAt;
    private Long supersededByRunId;

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
    public LocalDate getOccurredDate() { return occurredDate; }
    public LocalDate getEffectiveDate() { return effectiveDate; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public LocalDate getForecastHorizon() { return forecastHorizon; }
    public String getCompany() { return company; }
    public String getProductName() { return productName; }
    public String getCategory() { return category; }
    public String getCategoryEn() { return categoryEn; }
    public String getSummaryVi() { return summaryVi; }
    public String getSummaryEn() { return summaryEn; }
    public String getBiBucket() { return biBucket; }
    public String getSubjectKey() { return subjectKey; }
    public String getHighlightCardLabel() { return highlightCardLabel; }
    public String getSeverity() { return severity; }
    public String getSeverityTrend() { return severityTrend; }
    public LocalDate getEventDateRangeStart() { return eventDateRangeStart; }
    public LocalDate getEventDateRangeEnd() { return eventDateRangeEnd; }
    public String getKpiLabel() { return kpiLabel; }
    public String getKpiValue() { return kpiValue; }
    public boolean isHighlight() { return Boolean.TRUE.equals(highlight); }
    public IntelligenceTopic getIntelligenceTopic() { return intelligenceTopic; }
    public TemporalRole getTemporalRole() { return temporalRole; }
    public SourceAuthority getSourceAuthority() { return sourceAuthority; }
    public GeographyScope getGeographyScope() { return geographyScope; }
    public String getMarketCode() { return marketCode; }
    public String getSubjectEntityKey() { return subjectEntityKey; }
    public String getSubjectEntityName() { return subjectEntityName; }
    public EntityResolutionRules.EntityKind getSubjectEntityKind() { return subjectEntityKind; }
    public EntityResolutionRules.Status getEntityResolutionStatus() { return entityResolutionStatus; }
    public String getEntityResolutionDetail() { return entityResolutionDetail; }
    public Instant getCreatedAt() { return createdAt; }
    public FactExtractionRun getExtractionRun() { return extractionRun; }
    public boolean isActive() { return active; }
    public Instant getSupersededAt() { return supersededAt; }
    public Long getSupersededByRunId() { return supersededByRunId; }

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
    public EvidenceFact occurredDate(LocalDate d) { this.occurredDate = d; return this; }
    public EvidenceFact effectiveDate(LocalDate d) { this.effectiveDate = d; return this; }
    public EvidenceFact expiryDate(LocalDate d) { this.expiryDate = d; return this; }
    public EvidenceFact forecastHorizon(LocalDate d) { this.forecastHorizon = d; return this; }
    public EvidenceFact company(String c) { this.company = c; return this; }
    public EvidenceFact productName(String p) { this.productName = p; return this; }
    public EvidenceFact category(String c) { this.category = c; return this; }
    public EvidenceFact categoryEn(String c) { this.categoryEn = c; return this; }
    public EvidenceFact summaryVi(String s) { this.summaryVi = s; return this; }
    public EvidenceFact summaryEn(String s) { this.summaryEn = s; return this; }
    public EvidenceFact extractionRun(FactExtractionRun run) { this.extractionRun = run; return this; }

    public EvidenceFact biBucket(String b) { this.biBucket = b; return this; }
    public EvidenceFact subjectKey(String s) { this.subjectKey = s; return this; }
    public EvidenceFact highlightCardLabel(String l) { this.highlightCardLabel = l; return this; }
    public EvidenceFact severity(String s) { this.severity = s; return this; }
    public EvidenceFact severityTrend(String t) { this.severityTrend = t; return this; }
    public EvidenceFact eventDateRangeStart(LocalDate d) { this.eventDateRangeStart = d; return this; }
    public EvidenceFact eventDateRangeEnd(LocalDate d) { this.eventDateRangeEnd = d; return this; }
    public EvidenceFact kpiLabel(String l) { this.kpiLabel = l; return this; }
    public EvidenceFact kpiValue(String v) { this.kpiValue = v; return this; }
    public EvidenceFact highlight(boolean h) { this.highlight = h; return this; }
    public EvidenceFact intelligenceTopic(IntelligenceTopic value) { this.intelligenceTopic = value; return this; }
    public EvidenceFact temporalRole(TemporalRole value) { this.temporalRole = value; return this; }
    public EvidenceFact sourceAuthority(SourceAuthority value) { this.sourceAuthority = value; return this; }
    public EvidenceFact geography(GeographyScope scope, String code) {
        this.geographyScope = scope;
        this.marketCode = code == null || code.isBlank() ? null
                : code.strip().toUpperCase(java.util.Locale.ROOT);
        return this;
    }
    public EvidenceFact entityResolution(EntityResolutionRules.Resolution resolution) {
        if (resolution == null) return this;
        this.entityResolutionStatus = resolution.status();
        this.entityResolutionDetail = resolution.reason();
        EntityResolutionRules.Entity entity = resolution.singleEntity();
        if (entity != null) {
            this.subjectEntityKey = entity.key();
            this.subjectEntityName = entity.canonicalName();
            this.subjectEntityKind = entity.kind();
        }
        return this;
    }
}
