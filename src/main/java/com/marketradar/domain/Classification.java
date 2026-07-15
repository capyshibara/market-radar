package com.marketradar.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * Kết quả phân loại + routing cho một RawDoc.
 * Confidence KHÔNG phải verbalized: status suy ra từ self-consistency vote
 * (N lần gọi độc lập, nhãn nhận khi đạt minVotes). votesJson lưu chi tiết để audit.
 */
@Entity
@Table(name = "classifications")
public class Classification {

    public enum Status {
        CONFIRMED,          // ≥1 nhãn đạt vote tối thiểu
        OUT_OF_SCOPE,       // đa số run thống nhất "không thuộc category nào"
        UNCERTAIN_REVIEW,   // <2 run hợp lệ (lỗi API/schema-reject) → chờ review
        NO_LABEL_REVIEW     // các run bất đồng, không nhãn nào đủ vote → chờ review
    }

    public enum RoutingStatus {
        ROUTED,             // đã tra bảng, có ≥1 phòng ban nhận
        ADMIN_QUEUE,        // category không có trong bảng tra → hàng đợi admin
        HELD_FOR_REVIEW     // classification chưa CONFIRMED → không route (fail loud)
    }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Prevent two reruns from silently overwriting the same active one-to-one row. */
    @Version
    @Column(nullable = false, columnDefinition = "bigint default 0")
    private long rowVersion;

    @OneToOne(optional = false, fetch = FetchType.LAZY)
    private RawDoc rawDoc;

    @ElementCollection(targetClass = Category.class, fetch = FetchType.EAGER)
    @CollectionTable(name = "classification_labels")
    @Enumerated(EnumType.STRING)
    private Set<Category> labels = EnumSet.noneOf(Category.class);

    @ElementCollection(targetClass = Department.class, fetch = FetchType.EAGER)
    @CollectionTable(name = "classification_departments")
    @Enumerated(EnumType.STRING)
    private Set<Department> departments = EnumSet.noneOf(Department.class);

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private Status status;

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private RoutingStatus routingStatus;

    /** JSON đếm vote từng nhãn qua N run — bằng chứng cho quyết định nhận/loại */
    @Lob @Column(columnDefinition = "CLOB")
    private String votesJson;

    @Column(length = 512)
    private String note;

    @Column(nullable = false, length = 128)
    private String llmProvider;   // ANTHROPIC / STUB — minh bạch chế độ chạy

    /**
     * Version material for the active result. Nullable so Hibernate can add the
     * columns to databases that already contain legacy classifications. A null
     * value deliberately means "legacy/stale", never "current by assumption".
     */
    @Column(length = 64)
    private String classifierPromptSha256;

    @Column(length = 64)
    private String classifierContentSha256;

    @Column(length = 64)
    private String classifierVersionSignature;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected Classification() {}

    public Classification(RawDoc rawDoc, Set<Category> labels, Status status,
                          String votesJson, String llmProvider) {
        this.rawDoc = rawDoc;
        this.labels.addAll(labels);
        this.status = status;
        this.votesJson = votesJson;
        this.llmProvider = llmProvider;
        this.routingStatus = RoutingStatus.HELD_FOR_REVIEW;
    }

    public Long getId() { return id; }
    public long getRowVersion() { return rowVersion; }
    public RawDoc getRawDoc() { return rawDoc; }
    public Set<Category> getLabels() { return labels; }
    public Set<Department> getDepartments() { return departments; }
    public Status getStatus() { return status; }
    public RoutingStatus getRoutingStatus() { return routingStatus; }
    public String getVotesJson() { return votesJson; }
    public String getNote() { return note; }
    public String getLlmProvider() { return llmProvider; }
    public String getClassifierPromptSha256() { return classifierPromptSha256; }
    public String getClassifierContentSha256() { return classifierContentSha256; }
    public String getClassifierVersionSignature() { return classifierVersionSignature; }
    public Instant getCreatedAt() { return createdAt; }

    public void applyRouting(Set<Department> depts, RoutingStatus status, String note) {
        this.departments.clear();
        this.departments.addAll(depts);
        this.routingStatus = status;
        this.note = note;
    }

    public void applyClassifierVersion(String promptSha256, String contentSha256,
                                       String versionSignature) {
        this.classifierPromptSha256 = promptSha256;
        this.classifierContentSha256 = contentSha256;
        this.classifierVersionSignature = versionSignature;
    }

    /**
     * Promote a rerun into the existing one-to-one row. The persistence service
     * snapshots this row to the append-only attempt ledger before calling here.
     */
    public void replaceActiveResult(Classification replacement) {
        if (!rawDoc.getId().equals(replacement.rawDoc.getId())) {
            throw new IllegalArgumentException("replacement belongs to another RawDoc");
        }
        this.labels.clear();
        this.labels.addAll(replacement.labels);
        this.departments.clear();
        this.departments.addAll(replacement.departments);
        this.status = replacement.status;
        this.routingStatus = replacement.routingStatus;
        this.votesJson = replacement.votesJson;
        this.note = replacement.note;
        this.llmProvider = replacement.llmProvider;
        this.classifierPromptSha256 = replacement.classifierPromptSha256;
        this.classifierContentSha256 = replacement.classifierContentSha256;
        this.classifierVersionSignature = replacement.classifierVersionSignature;
        this.createdAt = Instant.now();
    }
}
