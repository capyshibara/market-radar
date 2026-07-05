package com.marketradar.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * dedup_decisions — append-only, mỗi record là quyết định cho MỘT cặp raw_docs
 * (Batch 5, bước 9). docAId luôn < docBId (cặp không lặp, không phụ thuộc thứ tự duyệt).
 * Verdict NEEDS_REVIEW = hệ thống KHÔNG tự quyết (LLM không chắc / cùng tier
 * không phân định được / chạy stub) — người nhìn ở trang /dedup.
 */
@Entity
@Table(name = "dedup_decisions", indexes = {
        @Index(name = "idx_dedup_pair", columnList = "docAId,docBId", unique = true)
})
public class DedupDecision {

    public enum Method { EXACT_URL, EXACT_HASH, JACCARD_TITLE, LLM_PAIRWISE }

    public enum Verdict { SAME_EVENT, DIFFERENT, NEEDS_REVIEW }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false) private Long docAId;
    @Column(nullable = false) private Long docBId;

    /** Title snapshot lúc quyết định — trang /dedup đọc được không cần join */
    @Column(length = 1024) private String docATitle;
    @Column(length = 1024) private String docBTitle;

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private Method method;

    /** Jaccard score lúc quyết định (null với EXACT_*) */
    private Double jaccardScore;

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private Verdict verdict;

    /** Doc được GIỮ khi SAME_EVENT; null khi DIFFERENT/NEEDS_REVIEW */
    private Long winnerDocId;

    /** Lý do đọc được: rule nào quyết, vì sao flag — fail loud có chữ */
    @Column(length = 1024)
    private String detail;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected DedupDecision() {}

    public DedupDecision(Long docAId, Long docBId, String docATitle, String docBTitle,
                         Method method, Double jaccardScore, Verdict verdict,
                         Long winnerDocId, String detail) {
        this.docAId = docAId;
        this.docBId = docBId;
        this.docATitle = docATitle;
        this.docBTitle = docBTitle;
        this.method = method;
        this.jaccardScore = jaccardScore;
        this.verdict = verdict;
        this.winnerDocId = winnerDocId;
        this.detail = detail;
    }

    public Long getId() { return id; }
    public Long getDocAId() { return docAId; }
    public Long getDocBId() { return docBId; }
    public String getDocATitle() { return docATitle; }
    public String getDocBTitle() { return docBTitle; }
    public Method getMethod() { return method; }
    public Double getJaccardScore() { return jaccardScore; }
    public Verdict getVerdict() { return verdict; }
    public Long getWinnerDocId() { return winnerDocId; }
    public String getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
}
