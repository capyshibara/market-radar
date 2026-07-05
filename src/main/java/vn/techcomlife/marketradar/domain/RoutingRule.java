package vn.techcomlife.marketradar.domain;

import jakarta.persistence.*;

/**
 * Bảng tra routing many-to-many: category → department.
 * Invariant #4: routing CHỈ qua bảng này — deterministic, auditable,
 * không bao giờ qua ngưỡng similarity.
 */
@Entity
@Table(name = "routing_rules",
       uniqueConstraints = @UniqueConstraint(columnNames = {"category", "department"}))
public class RoutingRule {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private Category category;

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private Department department;

    /** true = bảng placeholder tự soạn — ontology thật là deliverable riêng */
    @Column(nullable = false)
    private boolean placeholder = true;

    protected RoutingRule() {}
    public RoutingRule(Category category, Department department) {
        this.category = category; this.department = department;
    }
    public Category getCategory() { return category; }
    public Department getDepartment() { return department; }
    public boolean isPlaceholder() { return placeholder; }
}
