package com.marketradar.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Log MỌI lần gọi LLM — vừa là audit trail, vừa là replay cache
 * (fallback "API lỗi giữa demo": đọc lại response của lần chạy tốt).
 */
@Entity
@Table(name = "llm_call_log", indexes =
    @Index(name = "idx_llm_cache", columnList = "promptSha256, sampleIndex"))
public class LlmCallLog {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)  private String purpose;      // vd CLASSIFY
    @Column(nullable = false, length = 64)  private String model;
    @Column(nullable = false, length = 64)  private String promptSha256;
    @Column(nullable = false)               private int sampleIndex;     // 0..N-1 self-consistency
    @Lob @Column(columnDefinition = "CLOB") private String responseText;
    private Long rawDocId;
    private long latencyMs;
    @Column(nullable = false) private Instant createdAt = Instant.now();

    protected LlmCallLog() {}
    public LlmCallLog(String purpose, String model, String promptSha256,
                      int sampleIndex, String responseText, Long rawDocId, long latencyMs) {
        this.purpose = purpose; this.model = model; this.promptSha256 = promptSha256;
        this.sampleIndex = sampleIndex; this.responseText = responseText;
        this.rawDocId = rawDocId; this.latencyMs = latencyMs;
    }
    public String getResponseText() { return responseText; }
    public int getSampleIndex() { return sampleIndex; }
    public String getModel() { return model; }
}
