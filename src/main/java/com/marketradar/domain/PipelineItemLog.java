package com.marketradar.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Batch 10: MỘT row cho mỗi item (nguồn/doc/claim) mà một lần chạy stage xử lý —
 * durable, sống sót qua restart, khác với text log tạm thời trước đây (StringBuilder
 * summary chỉ tồn tại trong RAM tới lần chạy kế/tới khi restart). Đây là chỗ trả lời
 * "bài này bị chặn/lỗi ở bước nào, vì sao" mà trước đây KHÔNG có nơi lưu — lỗi
 * FETCH_REJECTED của ingest, ERROR của classify, LLM_ERROR/SCHEMA_REJECTED của
 * extract chỉ hiện ra trong text tạm rồi biến mất.
 *
 * rawDocId: null cho item cấp SOURCE (ingest) hoặc claim EXEC_SUMMARY (không gắn
 * với 1 doc) — các trường hợp còn lại điền để trang /pipeline/history join theo doc.
 */
@Entity
@Table(name = "pipeline_item_log", indexes = {
        @Index(name = "idx_item_log_rawdoc", columnList = "rawDocId"),
        @Index(name = "idx_item_log_run", columnList = "runLogId")
})
public class PipelineItemLog {

    public enum ItemType { SOURCE, RAW_DOC, CLAIM }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long runLogId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ItemType itemType;

    @Column(nullable = false, length = 64)
    private String itemRef;            // source code, doc id (string), hoặc claim code

    @Column(length = 300)
    private String itemLabel;          // tiêu đề/tên hiển thị

    private Long rawDocId;             // null cho SOURCE hoặc EXEC_SUMMARY claim

    // Fix 2026-07-15: 40 → 200. InterpretationJob nối NHIỀU GateStatus FAIL_* (comma-joined)
    // thành 1 status khi 1 doc có nhiều câu fail các lý do khác nhau — 40 ký tự chỉ đủ cho
    // 1-2 tên enum, không đủ khi extraction giàu fact hơn sinh nhiều câu/doc hơn (batch audit
    // 2026-07-15: bug tiềm ẩn từ trước, lộ ra khi tăng MAX_FACTS_PER_DOC + input dài hơn).
    @Column(nullable = false, length = 200)
    private String status;

    @Column(length = 500)
    private String message;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected PipelineItemLog() {}

    public PipelineItemLog(Long runLogId, ItemType itemType, String itemRef, String itemLabel,
                           Long rawDocId, String status, String message) {
        this.runLogId = runLogId;
        this.itemType = itemType;
        this.itemRef = itemRef;
        this.itemLabel = truncate(itemLabel, 300);
        this.rawDocId = rawDocId;
        // truncate phòng thủ (cùng pattern itemLabel/message) — status vẫn ưu tiên đủ dài
        // (length=200 ở trên) để không mất thông tin trong ca thường gặp.
        this.status = truncate(status, 200);
        this.message = truncate(message, 500);
    }

    private static String truncate(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max) + "…");
    }

    public Long getId() { return id; }
    public Long getRunLogId() { return runLogId; }
    public ItemType getItemType() { return itemType; }
    public String getItemRef() { return itemRef; }
    public String getItemLabel() { return itemLabel; }
    public Long getRawDocId() { return rawDocId; }
    public String getStatus() { return status; }
    public String getMessage() { return message; }
    public Instant getCreatedAt() { return createdAt; }
}
