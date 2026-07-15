package com.marketradar.prompt;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Batch 12: bản ghi đè prompt do ops nhập tại /prompts. Không có bản ghi → dùng prompt
 * mặc định (đăng ký trong code). Có bản ghi → dùng body này (áp dụng runtime).
 */
@Entity
@Table(name = "prompt_overrides")
public class PromptOverride {

    @Id
    @Column(length = 40)
    private String promptKey;   // PromptKey.name()

    @Lob @Column(columnDefinition = "CLOB", nullable = false)
    private String body;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(length = 80)
    private String updatedBy;

    protected PromptOverride() {}

    public PromptOverride(String promptKey, String body, String updatedBy) {
        this.promptKey = promptKey;
        this.body = body;
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
    }

    public String getPromptKey() { return promptKey; }
    public String getBody() { return body; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }

    public void update(String body, String updatedBy) {
        this.body = body;
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
    }
}
