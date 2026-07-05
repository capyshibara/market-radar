package vn.techcomlife.marketradar.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * raw_docs — lưu nguyên bản đã chuẩn hoá về text + metadata.
 * Invariant "fail loud": parse lỗi vẫn ghi record với status lỗi + note,
 * KHÔNG bao giờ đoán/điền nội dung.
 */
@Entity
@Table(name = "raw_docs", indexes = {
        @Index(name = "idx_rawdoc_hash", columnList = "contentHash", unique = true)
})
public class RawDoc {

    public enum ParseStatus { OK, PARSE_ERROR, FETCH_REJECTED, EMPTY_CONTENT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private Source source;

    @Column(nullable = false, length = 2048)
    private String url;

    @Column(length = 1024)
    private String title;

    private Instant publishedAt;      // null nếu nguồn không cung cấp — không đoán

    @Column(nullable = false)
    private Instant fetchedAt;

    /** SHA-256 hex của rawText — dedup exact ở tầng ingest */
    @Column(nullable = false, length = 64)
    private String contentHash;

    @Lob
    @Column(columnDefinition = "CLOB")
    private String rawText;           // text đã trích, GIỮ NGUYÊN ngôn ngữ gốc

    @Column(nullable = false, length = 8)
    private String language;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ParseStatus parseStatus;

    @Column(length = 1024)
    private String note;              // lý do lỗi/skip — phục vụ audit

    @Column(nullable = false)
    private boolean sampleData = false; // true = dữ liệu mẫu đặt tay cho demo template

    /**
     * Batch 5 (dedup): != null nghĩa là doc này là BẢN TRÙNG của doc id kia
     * (bản thua theo rule official>media, mới>cũ). Doc trùng KHÔNG bị xoá
     * (audit trail giữ nguyên) — chỉ bị LỌC khỏi report. Quyết định chi tiết
     * nằm ở dedup_decisions.
     */
    private Long duplicateOfId;

    protected RawDoc() {}

    public RawDoc(Source source, String url, String title, Instant publishedAt,
                  Instant fetchedAt, String contentHash, String rawText,
                  String language, ParseStatus parseStatus, String note) {
        this.source = source;
        this.url = url;
        this.title = title;
        this.publishedAt = publishedAt;
        this.fetchedAt = fetchedAt;
        this.contentHash = contentHash;
        this.rawText = rawText;
        this.language = language;
        this.parseStatus = parseStatus;
        this.note = note;
    }

    public Long getId() { return id; }
    public Source getSource() { return source; }
    public String getUrl() { return url; }
    public String getTitle() { return title; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getFetchedAt() { return fetchedAt; }
    public String getContentHash() { return contentHash; }
    public String getRawText() { return rawText; }
    public String getLanguage() { return language; }
    public ParseStatus getParseStatus() { return parseStatus; }
    public String getNote() { return note; }
    public boolean isSampleData() { return sampleData; }
    public void setSampleData(boolean sampleData) { this.sampleData = sampleData; }
    public Long getDuplicateOfId() { return duplicateOfId; }
    public void setDuplicateOfId(Long duplicateOfId) { this.duplicateOfId = duplicateOfId; }
}
