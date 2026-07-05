package com.marketradar.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Kết quả Gate L2 (entailment độc lập) cho một claim — APPEND-ONLY:
 * chạy lại verify tạo record mới, record cũ giữ nguyên (audit trail).
 * Record mới nhất theo createdAt là verdict hiện hành.
 */
@Entity
@Table(name = "claim_verifications")
public class ClaimVerification {

    /**
     * VERIFIER_ERROR: output không parse được / API lỗi — KHÔNG bao giờ
     * quy về pass, luôn route review (fail loud).
     */
    public enum Verdict { ENTAILED, CONTRADICTED, NEUTRAL, VERIFIER_ERROR }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private InterpretedClaim claim;

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private Verdict verdict;

    /** Lý do verifier đưa ra — hiển thị cho reviewer, không dùng làm điểm số. */
    @Lob @Column(columnDefinition = "CLOB")
    private String rationale;

    @Column(nullable = false, length = 64)
    private String verifierProvider;

    /** Response nguyên văn — bằng chứng cho verdict, kể cả khi parse lỗi. */
    @Lob @Column(columnDefinition = "CLOB")
    private String rawResponse;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected ClaimVerification() {}

    public ClaimVerification(InterpretedClaim claim, Verdict verdict, String rationale,
                             String verifierProvider, String rawResponse) {
        this.claim = claim;
        this.verdict = verdict;
        this.rationale = rationale;
        this.verifierProvider = verifierProvider;
        this.rawResponse = rawResponse;
    }

    public Long getId() { return id; }
    public InterpretedClaim getClaim() { return claim; }
    public Verdict getVerdict() { return verdict; }
    public String getRationale() { return rationale; }
    public String getVerifierProvider() { return verifierProvider; }
    public String getRawResponse() { return rawResponse; }
    public Instant getCreatedAt() { return createdAt; }
}
