package com.marketradar.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * alert_log — append-only, mọi lần Hot Alert được cân nhắc bắn (Batch 5).
 * Nguyên tắc: alert THẤT BẠI không bao giờ được phá hành động duyệt —
 * lỗi HTTP chỉ thành một record FAILED ở đây (fail loud nhưng cô lập).
 */
@Entity
@Table(name = "alert_log")
public class AlertLog {

    public enum Channel { SLACK, STUB }

    /** SENT = webhook 2xx · FAILED = lỗi HTTP/mạng · SKIPPED_DUPLICATE = claim này đã alert rồi */
    public enum Status { SENT, FAILED, SKIPPED_DUPLICATE }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String claimCode;

    @Column(nullable = false, length = 4)
    private String riskTier;

    /** Ngữ cảnh phát sinh alert, vd REVIEW:APPROVE / AUTO_APPROVED / TEST */
    @Column(nullable = false, length = 32)
    private String trigger_;

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private Channel channel;

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private Status status;

    /** Payload text nguyên văn đã (định) gửi — audit được nội dung alert */
    @Lob @Column(columnDefinition = "CLOB", nullable = false)
    private String payloadText;

    /** HTTP status từ webhook; null với STUB/SKIPPED */
    private Integer httpStatus;

    @Column(length = 1024)
    private String note;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected AlertLog() {}

    public AlertLog(String claimCode, String riskTier, String trigger,
                    Channel channel, Status status, String payloadText,
                    Integer httpStatus, String note) {
        this.claimCode = claimCode;
        this.riskTier = riskTier;
        this.trigger_ = trigger;
        this.channel = channel;
        this.status = status;
        this.payloadText = payloadText;
        this.httpStatus = httpStatus;
        this.note = note;
    }

    public Long getId() { return id; }
    public String getClaimCode() { return claimCode; }
    public String getRiskTier() { return riskTier; }
    public String getTrigger() { return trigger_; }
    public Channel getChannel() { return channel; }
    public Status getStatus() { return status; }
    public String getPayloadText() { return payloadText; }
    public Integer getHttpStatus() { return httpStatus; }
    public String getNote() { return note; }
    public Instant getCreatedAt() { return createdAt; }
}
