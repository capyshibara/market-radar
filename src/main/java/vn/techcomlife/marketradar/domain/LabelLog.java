package vn.techcomlife.marketradar.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * label_store — MỌI hành động của reviewer thành một record nhãn.
 * MVP CHỈ LOG (đúng scope doc gốc); "học để tốt lên" là vision.
 * oldText giữ VERBATIM text trước khi sửa → cùng với InterpretedClaim
 * tạo thành audit trail đầy đủ của luồng edit.
 */
@Entity
@Table(name = "label_log")
public class LabelLog {

    public enum Action { APPROVE, EDIT_APPROVE, REJECT, FORCE_APPROVE }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String claimCode;

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private Action action;

    @Lob @Column(columnDefinition = "CLOB")
    private String oldText;

    @Lob @Column(columnDefinition = "CLOB")
    private String newText;          // null nếu không sửa text

    /** Bắt buộc với REJECT và FORCE_APPROVE. */
    @Lob @Column(columnDefinition = "CLOB")
    private String reason;

    /** Trạng thái gate/verdict TẠI THỜI ĐIỂM quyết định — context của nhãn. */
    @Column(length = 40)  private String gateStatusAtDecision;
    @Column(length = 20)  private String verdictAtDecision;
    @Column(length = 4)   private String riskTierAtDecision;

    /** MVP: tên tự khai trên form — chưa có auth. */
    @Column(nullable = false, length = 128)
    private String reviewerName;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected LabelLog() {}

    public LabelLog(String claimCode, Action action, String oldText, String newText,
                    String reason, String gateStatusAtDecision, String verdictAtDecision,
                    String riskTierAtDecision, String reviewerName) {
        this.claimCode = claimCode;
        this.action = action;
        this.oldText = oldText;
        this.newText = newText;
        this.reason = reason;
        this.gateStatusAtDecision = gateStatusAtDecision;
        this.verdictAtDecision = verdictAtDecision;
        this.riskTierAtDecision = riskTierAtDecision;
        this.reviewerName = reviewerName;
    }

    public Long getId() { return id; }
    public String getClaimCode() { return claimCode; }
    public Action getAction() { return action; }
    public String getOldText() { return oldText; }
    public String getNewText() { return newText; }
    public String getReason() { return reason; }
    public String getGateStatusAtDecision() { return gateStatusAtDecision; }
    public String getVerdictAtDecision() { return verdictAtDecision; }
    public String getRiskTierAtDecision() { return riskTierAtDecision; }
    public String getReviewerName() { return reviewerName; }
    public Instant getCreatedAt() { return createdAt; }
}
