package com.marketradar.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Một CÂU diễn giải do AI#3 sinh ra (hoặc demo-inject).
 * Invariant #1 "zero claim không nguồn": mỗi câu bắt buộc mang danh sách
 * factCode (CSV) trỏ về EvidenceFact; câu không qua Gate L1 vẫn được LƯU
 * (fail loud, audit được) nhưng KHÔNG BAO GIỜ xuất hiện trong report.
 */
@Entity
@Table(name = "interpreted_claims")
public class InterpretedClaim {

    /** Slot template mà câu này điền vào (template-first — AI chỉ điền slot).
     * SYNTHESIS (Phase 3+) = câu tổng hợp xuyên nhiều RawDoc cho BI report — Bucket bên dưới
     * mới là phân loại nội dung thật; Slot=SYNTHESIS chỉ đánh dấu "đây là claim loại này". */
    public enum Slot { WHY_MATTERS, IMPLICATION, EXEC_SUMMARY, SYNTHESIS }

    public enum Origin { PIPELINE, DEMO_INJECT }

    /**
     * Nhóm nội dung cho BI report (Phase 3+) — null cho claim report tuần (Slot cũ đã đủ dùng).
     * Đây KHÔNG thay thế Slot: Slot vẫn là "câu này điền vào ô nào", Bucket là "câu này (thường
     * tổng hợp xuyên nhiều RawDoc) thuộc chủ đề nào" — dùng để định tuyến trang trong BI template.
     */
    public enum Bucket {
        MACRO_ECONOMIC, COMPETITIVE_THEME, SCHEDULED_EVENT, COMPANY_EVENT,
        MARKET_SHARE_OR_AWARD, TECH_AI_SIGNAL, STRATEGIC_COMPARISON
    }

    /**
     * Trạng thái review (Batch 4). Report CHỈ nhận 4 trạng thái *_APPROVED.
     * PENDING_VERIFICATION: qua L1, chờ Gate L2 (entailment).
     * PENDING_REVIEW: cần người (L1 fail, hoặc L2 không ENTAILED, hoặc tier >= T2).
     * AUTO_APPROVED: L1 pass + ENTAILED + tier T0/T1 (diện "sample" theo E1 —
     *   MVP chưa sample thật, mọi claim diện này đều tự duyệt, ghi rõ trong NOTES).
     */
    public enum ReviewStatus {
        PENDING_VERIFICATION, PENDING_REVIEW,
        AUTO_APPROVED, APPROVED, EDITED_APPROVED, FORCE_APPROVED, REJECTED
    }

    /**
     * Kết quả Gate L1 (deterministic, KHÔNG AI).
     * Thứ tự khai báo = thứ tự ưu tiên khi nhiều lỗi cùng lúc.
     */
    public enum GateStatus {
        SCHEMA_REJECTED,            // output LLM không parse được đúng schema
        FAIL_NO_CITATION,           // câu không có fact_codes
        FAIL_UNKNOWN_FACT_CODE,     // fact_codes trỏ tới mã không tồn tại trong pack
        FAIL_NAME_NOT_IN_EVIDENCE,  // tên trong ngoặc kép không xuất hiện verbatim trong evidence
        FAIL_DATE_NOT_IN_EVIDENCE,  // ngày trong câu không khớp ngày nào trong evidence (sau chuẩn hoá)
        FAIL_NUMBER_NOT_IN_EVIDENCE,// con số trong câu không khớp số nào trong evidence (sau chuẩn hoá)
        PASS
    }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Mã hiển thị, vd C-001 — đối xứng với factCode F-001 */
    @Column(nullable = false, unique = true, length = 16)
    private String claimCode;

    /** null với EXEC_SUMMARY (câu tổng hợp cấp report, không thuộc 1 doc) */
    @ManyToOne(fetch = FetchType.LAZY)
    private RawDoc rawDoc;

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private Slot slot;

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private Origin origin;

    @Lob @Column(columnDefinition = "CLOB", nullable = false)
    private String textVi;

    /** CSV các factCode được câu trích dẫn, vd "F-001,F-002" */
    @Column(length = 512)
    private String factCodesCsv;

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private GateStatus gateStatus;

    /** JSON chi tiết mọi phép kiểm của gate — bằng chứng cho quyết định pass/fail */
    @Lob @Column(columnDefinition = "CLOB")
    private String gateDetailJson;

    @Column(nullable = false, length = 32)
    private String llmProvider;   // ANTHROPIC / STUB / DEMO

    /**
     * Risk tier T0..T4 (Batch 4 — PLACEHOLDER rule, xem RiskTierRouter).
     * Lưu dạng String để không khoá enum khi Impact Scorer thật (bước 9) thay thế.
     */
    @Column(nullable = false, length = 4)
    private String riskTier = "T2";

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private ReviewStatus reviewStatus = ReviewStatus.PENDING_REVIEW;

    /** Chỉ set cho claim BI report (Phase 3+); null cho claim report tuần (WHY_MATTERS/IMPLICATION/EXEC_SUMMARY). */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private Bucket bucket;

    /** Khoá nhóm/lặp trang cho BI report — tên đối thủ hoặc tên xu hướng. Null cho claim report tuần. */
    @Column(length = 256)
    private String subjectKey;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected InterpretedClaim() {}

    public InterpretedClaim(String claimCode, RawDoc rawDoc, Slot slot, Origin origin,
                            String textVi, String factCodesCsv,
                            GateStatus gateStatus, String gateDetailJson, String llmProvider) {
        this.claimCode = claimCode;
        this.rawDoc = rawDoc;
        this.slot = slot;
        this.origin = origin;
        this.textVi = textVi;
        this.factCodesCsv = factCodesCsv;
        this.gateStatus = gateStatus;
        this.gateDetailJson = gateDetailJson;
        this.llmProvider = llmProvider;
    }

    public Long getId() { return id; }
    public String getClaimCode() { return claimCode; }
    public RawDoc getRawDoc() { return rawDoc; }
    public Slot getSlot() { return slot; }
    public Origin getOrigin() { return origin; }
    public String getTextVi() { return textVi; }
    public String getFactCodesCsv() { return factCodesCsv; }
    public GateStatus getGateStatus() { return gateStatus; }
    public String getGateDetailJson() { return gateDetailJson; }
    public String getLlmProvider() { return llmProvider; }
    public Instant getCreatedAt() { return createdAt; }
    public String getRiskTier() { return riskTier; }
    public ReviewStatus getReviewStatus() { return reviewStatus; }
    public Bucket getBucket() { return bucket; }
    public String getSubjectKey() { return subjectKey; }

    // ---- Chỉ dùng khi tạo claim BI report (Phase 3+) ----
    public InterpretedClaim bucket(Bucket bucket) { this.bucket = bucket; return this; }
    public InterpretedClaim subjectKey(String subjectKey) { this.subjectKey = subjectKey; return this; }

    /** Phase 4 (BI report — THREATMAP exhibit): suy mức độ HIGH/MEDIUM/LOW từ riskTier đã có sẵn
     * (RiskTierRouter, code thuần) — KHÔNG để AI tự chấm mức độ nghiêm trọng (sẽ là bịa đặt không
     * căn cứ, vi phạm invariant "zero claim không nguồn"). T3/T4 → HIGH, T2 → MEDIUM, còn lại → LOW. */
    public String getThreatSeverityLabel() {
        return switch (riskTier) {
            case "T3", "T4" -> "HIGH";
            case "T2" -> "MEDIUM";
            default -> "LOW";
        };
    }

    // ---- Setters CHỈ cho luồng review (Batch 4) ----
    // Text gốc trước mọi lần sửa được giữ verbatim trong LabelLog — đó là audit trail.
    public void setRiskTier(String riskTier) { this.riskTier = riskTier; }
    public void setReviewStatus(ReviewStatus reviewStatus) { this.reviewStatus = reviewStatus; }
    public void setTextVi(String textVi) { this.textVi = textVi; }
    public void setGateStatus(GateStatus gateStatus) { this.gateStatus = gateStatus; }
    public void setGateDetailJson(String gateDetailJson) { this.gateDetailJson = gateDetailJson; }
}
