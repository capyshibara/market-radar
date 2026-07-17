package com.marketradar.specialissue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/** Latest human-edited edition for one issue and one display language. */
@Entity
@Table(name = "special_issue_drafts", uniqueConstraints =
        @UniqueConstraint(name = "uk_special_issue_draft_language", columnNames = {"slug", "language"}))
public class SpecialIssueDraft {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 120) private String slug;
    @Column(nullable = false, length = 8) private String language;
    @Lob @Column(nullable = false, columnDefinition = "CLOB") private String contentJson;
    @Column(nullable = false, length = 80) private String editor;
    @Column(nullable = false) private Instant updatedAt = Instant.now();

    protected SpecialIssueDraft() {}

    public SpecialIssueDraft(String slug, String language, String contentJson, String editor) {
        this.slug = slug;
        this.language = language;
        this.contentJson = contentJson;
        this.editor = editor;
    }

    public void replace(String contentJson, String editor) {
        this.contentJson = contentJson;
        this.editor = editor;
        this.updatedAt = Instant.now();
    }

    public String getSlug() { return slug; }
    public String getLanguage() { return language; }
    public String getContentJson() { return contentJson; }
    public String getEditor() { return editor; }
    public Instant getUpdatedAt() { return updatedAt; }
}
