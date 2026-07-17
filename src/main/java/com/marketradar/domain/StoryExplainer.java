package com.marketradar.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Bản viết lại có giải thích cho một source story — cả EN lẫn VI, sinh một lần
 * từ toàn văn đã lưu rồi tái sử dụng cho mọi người đọc (một lần gọi writer duy nhất).
 *
 * <p>Đây là lớp hỗ trợ đọc hiểu, KHÔNG phải bằng chứng: nguyên văn evidence span và
 * toàn văn nguồn vẫn là hồ sơ kiểm tra duy nhất khi có khác biệt.</p>
 */
@Entity
@Table(name = "story_explainer",
        uniqueConstraints = @UniqueConstraint(name = "uk_story_explainer_fact", columnNames = "factCode"))
public class StoryExplainer {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String factCode;

    @Column(nullable = false)
    private Long rawDocId;

    @Lob @Column(columnDefinition = "CLOB", nullable = false) private String rewriteEn;
    @Lob @Column(columnDefinition = "CLOB", nullable = false) private String rewriteVi;
    /** Mỗi dòng một thuật ngữ: "Term — plain explanation". Có thể rỗng. */
    @Lob @Column(columnDefinition = "CLOB") private String termsEn;
    @Lob @Column(columnDefinition = "CLOB") private String termsVi;

    @Column(nullable = false, length = 64)
    private String writerProvider;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected StoryExplainer() {}

    public StoryExplainer(String factCode, Long rawDocId, String rewriteEn, String rewriteVi,
                          String termsEn, String termsVi, String writerProvider) {
        this.factCode = factCode; this.rawDocId = rawDocId;
        this.rewriteEn = rewriteEn; this.rewriteVi = rewriteVi;
        this.termsEn = termsEn; this.termsVi = termsVi;
        this.writerProvider = writerProvider;
    }

    public Long getId() { return id; }
    public String getFactCode() { return factCode; }
    public Long getRawDocId() { return rawDocId; }
    public String getRewriteEn() { return rewriteEn; }
    public String getRewriteVi() { return rewriteVi; }
    public String getTermsEn() { return termsEn; }
    public String getTermsVi() { return termsVi; }
    public String getWriterProvider() { return writerProvider; }
    public Instant getCreatedAt() { return createdAt; }

    /** Template seams: một dòng = một thuật ngữ đã giải thích. */
    public java.util.List<String> getTermsEnList() { return splitLines(termsEn); }
    public java.util.List<String> getTermsViList() { return splitLines(termsVi); }

    private static java.util.List<String> splitLines(String value) {
        if (value == null || value.isBlank()) return java.util.List.of();
        return java.util.Arrays.stream(value.split("\\R"))
                .map(String::strip).filter(line -> !line.isBlank()).toList();
    }
}
