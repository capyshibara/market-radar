package com.marketradar.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Batch 10 (feedback Hanh — "hard to see which news is blocked in which stage,
 * no clue what runs are a real batch"): MỘT row cho mỗi lần bấm Run một stage.
 *
 * batchId: mọi lần bấm Ingest mở batch MỚI (tăng dần); mọi stage khác (classify/
 * extract/interpret/verify) được gán vào batch của lần Ingest GẦN NHẤT — vì Ingest
 * là điểm khởi đầu tự nhiên của một vòng làm việc (mang tin mới vào), còn các
 * stage sau xử lý phần backlog cho tới khi Ingest tiếp theo. Trước khi có Ingest
 * nào chạy, mọi thứ vào batch 1 (fallback).
 */
@Entity
@Table(name = "pipeline_run_log")
public class PipelineRunLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String stage;              // ingest/classify/extract/interpret/verify

    @Column(nullable = false)
    private int batchId;

    @Column(nullable = false)
    private Instant startedAt;

    private Instant finishedAt;

    @Column(nullable = false, length = 16)
    private String state = "RUNNING";  // RUNNING/SUCCESS/FAILED

    private int completed;
    private int total;

    @Column(length = 2000)
    private String errorMessage;

    protected PipelineRunLog() {}

    public PipelineRunLog(String stage, int batchId, Instant startedAt) {
        this.stage = stage;
        this.batchId = batchId;
        this.startedAt = startedAt;
    }

    public void finish(String state, Instant finishedAt, int completed, int total, String errorMessage) {
        this.state = state;
        this.finishedAt = finishedAt;
        this.completed = completed;
        this.total = total;
        this.errorMessage = errorMessage == null ? null
                : (errorMessage.length() <= 2000 ? errorMessage : errorMessage.substring(0, 2000) + "…");
    }

    public Long getId() { return id; }
    public String getStage() { return stage; }
    public int getBatchId() { return batchId; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public String getState() { return state; }
    public int getCompleted() { return completed; }
    public int getTotal() { return total; }
    public String getErrorMessage() { return errorMessage; }
}
