package com.marketradar.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/** Latest human-curated narrative for one Product cadence and display language. */
@Entity
@Table(name = "product_report_editorial_drafts", uniqueConstraints =
        @UniqueConstraint(name = "uk_product_report_editorial_language",
                columnNames = {"cadence", "language"}))
public class ProductReportEditorialDraft {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ProductReportCadence cadence;

    @Column(nullable = false, length = 8)
    private String language;

    @Lob @Column(nullable = false, columnDefinition = "CLOB")
    private String contentJson;

    @Column(nullable = false, length = 100)
    private String editor;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    protected ProductReportEditorialDraft() {}

    public ProductReportEditorialDraft(ProductReportCadence cadence, String language,
                                       String contentJson, String editor) {
        this.cadence = cadence;
        this.language = language;
        this.contentJson = contentJson;
        this.editor = editor;
    }

    public void replace(String contentJson, String editor) {
        this.contentJson = contentJson;
        this.editor = editor;
        this.updatedAt = Instant.now();
    }

    public ProductReportCadence getCadence() { return cadence; }
    public String getLanguage() { return language; }
    public String getContentJson() { return contentJson; }
    public String getEditor() { return editor; }
    public Instant getUpdatedAt() { return updatedAt; }
}
