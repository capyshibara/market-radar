package com.marketradar.domain;

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
    /** How the original content entered the evidence store; never inferred from the URL. */
    public enum IntakeMethod { CRAWLED, MANUAL_TEXT, FILE_UPLOAD }

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

    /**
     * Batch 9 (fix Hanh 2026-07-14): true = rawText là TOÀN VĂN bài viết đã fetch
     * thành công (không phải chỉ tiêu đề). Dùng để quyết định có cần fetch lại
     * không — trước đây IngestionJob chỉ check "URL đã tồn tại + OK" để bỏ qua,
     * nghĩa là doc title-only từ TRƯỚC KHI có tính năng full-text fetch sẽ
     * KHÔNG BAO GIỜ được backfill (bug). Field mặc định false nên mọi row cũ
     * (tạo trước migration này) tự động đủ điều kiện backfill ở lần ingest tới.
     */
    // columnDefinition có DEFAULT FALSE: bắt buộc để ALTER TABLE ADD COLUMN ... NOT NULL
    // không lỗi trên DB đã có sẵn row cũ (H2/hầu hết DB từ chối thêm cột NOT NULL
    // không có default cho bảng không rỗng — bug thật gặp khi test trên bản copy dữ liệu).
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean fullTextFetched = false;

    @Enumerated(EnumType.STRING)
    // Default keeps additive schema update safe for the existing populated H2 database.
    @Column(nullable = false, length = 32, columnDefinition = "varchar(32) default 'CRAWLED'")
    private IntakeMethod intakeMethod = IntakeMethod.CRAWLED;

    @Column(length = 240)
    private String publisherName;

    @Column(length = 512)
    private String originalFilename;

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
    public boolean isFullTextFetched() { return fullTextFetched; }

    /** Used only when an operator has supplied complete text or a file directly. */
    public void markFullTextAvailable() { this.fullTextFetched = true; }
    public IntakeMethod getIntakeMethod() { return intakeMethod; }
    public void setIntakeMethod(IntakeMethod intakeMethod) {
        this.intakeMethod = intakeMethod == null ? IntakeMethod.CRAWLED : intakeMethod;
    }
    public String getPublisherName() { return publisherName; }
    public void setPublisherName(String publisherName) { this.publisherName = publisherName; }
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }

    /** Batch 9: nâng cấp doc title-only lên toàn văn TẠI CHỖ (không insert row mới,
     * tránh phải xử lý dedup EXACT_URL cho 2 bản của cùng 1 bài). */
    public void upgradeToFullText(String contentHash, String rawText, String note) {
        this.contentHash = contentHash;
        this.rawText = rawText;
        this.note = note;
        this.fullTextFetched = true;
    }
}
