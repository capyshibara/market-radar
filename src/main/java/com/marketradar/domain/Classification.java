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

    @Column(nullable = false, length = 32)
    private String llmProvider;   // ANTHROPIC / STUB — minh bạch chế độ chạy

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
    public RawDoc getRawDoc() { return rawDoc; }
    public Set<Category> getLabels() { return labels; }
    public Set<Department> getDepartments() { return departments; }
    public Status getStatus() { return status; }
    public RoutingStatus getRoutingStatus() { return routingStatus; }
    public String getVotesJson() { return votesJson; }
    public String getNote() { return note; }
    public String getLlmProvider() { return llmProvider; }
    public Instant getCreatedAt() { return createdAt; }

    public void applyRouting(Set<Department> depts, RoutingStatus status, String note) {
        this.departments.clear();
        this.departments.addAll(depts);
        this.routingStatus = status;
        this.note = note;
    }
}
