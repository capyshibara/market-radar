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
     * NARRATIVE (batch 10): câu tổng hợp xuyên-tài-liệu cho 1 chương Monthly Highlight —
     * cùng họ với EXEC_SUMMARY (rawDoc null, report-level), khác ở chỗ gắn 1 chapterCode. */
    public enum Slot { WHY_MATTERS, IMPLICATION, EXEC_SUMMARY, NARRATIVE }

    public enum Origin { PIPELINE, DEMO_INJECT }

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

    /** Bilingual report support: bản tiếng Anh, do Interpreter sinh CÙNG lúc với textVi
     * (một lời gọi LLM, hai ngôn ngữ) — không phải bản dịch máy tách rời. Gate L1 kiểm
     * CẢ HAI bản (xem GroundingGateL1#checkBilingual); Gate L2/entailment vẫn chỉ chạy
     * trên textVi (nguồn sự thật cho quyết định publish — tránh nhân đôi lời gọi verifier
     * và khả năng hai ngôn ngữ bất đồng verdict). Sửa tay ở /review (edit) chỉ sửa textVi;
     * textEn giữ nguyên bản gốc AI sinh trong trường hợp đó (biết trước, chấp nhận cho MVP). */
    @Lob @Column(columnDefinition = "CLOB", nullable = false)
    private String textEn;

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

    /** Batch 10: null trừ khi slot=NARRATIVE — tên hằng số com.marketradar.interpret.Chapter
     * (VN_COMPETITOR/VN_REGULATION/REGIONAL_LESSONS) mà câu này thuộc về. rawDoc luôn null
     * cho NARRATIVE (giống EXEC_SUMMARY) nên không suy được chương từ rawDoc — cần cột riêng. */
    @Column(length = 32)
    private String chapterCode;

    /**
     * Append-only edition metadata for PIPELINE claims. Signature identifies the
     * interpreter contract + provider/model + effective prompt; inputHash identifies
     * the exact rendered evidence pack. All sentences from one call share editionId.
     * DEMO/manual legacy rows may keep these nullable and are never auto-superseded.
     */
    @Column(length = 64)
    private String interpretationSignature;

    @Column(length = 64)
    private String interpretationInputHash;

    @Column(length = 36)
    private String interpretationEditionId;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean superseded = false;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected InterpretedClaim() {}

    public InterpretedClaim(String claimCode, RawDoc rawDoc, Slot slot, Origin origin,
                            String textVi, String textEn, String factCodesCsv,
                            GateStatus gateStatus, String gateDetailJson, String llmProvider) {
        this.claimCode = claimCode;
        this.rawDoc = rawDoc;
        this.slot = slot;
        this.origin = origin;
        this.textVi = textVi;
        this.textEn = textEn;
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
    public String getTextEn() { return textEn; }
    /** Batch 7 (i18n): chọn bản theo ngôn ngữ hiển thị hiện tại, dùng trong template: th:text="${c.text(#locale.language)}" */
    public String text(String lang) { return "vi".equals(lang) ? textVi : textEn; }
    public String getFactCodesCsv() { return factCodesCsv; }
    public GateStatus getGateStatus() { return gateStatus; }
    public String getGateDetailJson() { return gateDetailJson; }
    public String getLlmProvider() { return llmProvider; }
    public Instant getCreatedAt() { return createdAt; }
    public String getRiskTier() { return riskTier; }
    public ReviewStatus getReviewStatus() { return reviewStatus; }
    public String getChapterCode() { return chapterCode; }
    public String getInterpretationSignature() { return interpretationSignature; }
    public String getInterpretationInputHash() { return interpretationInputHash; }
    public String getInterpretationEditionId() { return interpretationEditionId; }
    public boolean isSuperseded() { return superseded; }

    // ---- Setters CHỈ cho luồng review (Batch 4) ----
    // Text gốc trước mọi lần sửa được giữ verbatim trong LabelLog — đó là audit trail.
    public void setRiskTier(String riskTier) { this.riskTier = riskTier; }
    public void setReviewStatus(ReviewStatus reviewStatus) { this.reviewStatus = reviewStatus; }
    public void setTextVi(String textVi) { this.textVi = textVi; }
    public void setGateStatus(GateStatus gateStatus) { this.gateStatus = gateStatus; }
    public void setGateDetailJson(String gateDetailJson) { this.gateDetailJson = gateDetailJson; }
    public void setChapterCode(String chapterCode) { this.chapterCode = chapterCode; }

    public void setInterpretationEdition(String signature, String inputHash, String editionId) {
        this.interpretationSignature = signature;
        this.interpretationInputHash = inputHash;
        this.interpretationEditionId = editionId;
    }

    public void markSuperseded() { this.superseded = true; }
}
