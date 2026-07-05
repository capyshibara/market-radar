package vn.techcomlife.marketradar.domain;

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
    @Lob @Column(columnDefinition = "CLOB") private String summaryVi; // tóm tắt tiếng Việt, gắn nhãn bản dịch/tóm tắt

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
    public String getSummaryVi() { return summaryVi; }
    public Instant getCreatedAt() { return createdAt; }

    public EvidenceFact eventDate(LocalDate d) { this.eventDate = d; return this; }
    public EvidenceFact company(String c) { this.company = c; return this; }
    public EvidenceFact productName(String p) { this.productName = p; return this; }
    public EvidenceFact category(String c) { this.category = c; return this; }
    public EvidenceFact summaryVi(String s) { this.summaryVi = s; return this; }
}
