package com.marketradar.product;

import com.marketradar.domain.SourceAuthority;

import java.time.LocalDate;

/** Source-backed item plus deterministic Product reading metadata. */
public record CurrentProductNewsItem(
        String factCode,
        long rawDocId,
        String title,
        String sourceCode,
        String sourceName,
        int sourceTier,
        String sourceUrl,
        LocalDate publishedDate,
        String factType,
        String verbatimEvidenceSpan,
        CurrentProductNewsTopic topic,
        long ageDays,
        String displaySummaryVi,
        String displaySummaryEn,
        String evidenceLanguage,
        boolean manuallySupplied,
        ProductMarketScope marketScope,
        String geography,
        String sourceAuthority) {

    public CurrentProductNewsItem {
        marketScope = marketScope == null ? ProductMarketScope.INTERNATIONAL : marketScope;
        geography = geography == null || geography.isBlank() ? "Global / regional" : geography.strip();
        sourceAuthority = normalizedAuthority(sourceAuthority, sourceTier).name();
    }

    /** Compatibility constructor for the previous immutable read-model shape. */
    public CurrentProductNewsItem(String factCode, long rawDocId, String title,
                                  String sourceCode, String sourceName, int sourceTier,
                                  String sourceUrl, LocalDate publishedDate, String factType,
                                  String verbatimEvidenceSpan, CurrentProductNewsTopic topic,
                                  long ageDays, String displaySummaryVi, String displaySummaryEn,
                                  String evidenceLanguage, boolean manuallySupplied,
                                  ProductMarketScope marketScope, String geography) {
        this(factCode, rawDocId, title, sourceCode, sourceName, sourceTier, sourceUrl,
                publishedDate, factType, verbatimEvidenceSpan, topic, ageDays,
                displaySummaryVi, displaySummaryEn, evidenceLanguage, manuallySupplied,
                marketScope, geography, normalizedAuthority(null, sourceTier).name());
    }

    public CurrentProductNewsItem(String factCode, long rawDocId, String title,
                                  String sourceCode, String sourceName, int sourceTier,
                                  String sourceUrl, LocalDate publishedDate, String factType,
                                  String verbatimEvidenceSpan, CurrentProductNewsTopic topic,
                                  long ageDays, String displaySummaryVi, String displaySummaryEn,
                                  String evidenceLanguage) {
        this(factCode, rawDocId, title, sourceCode, sourceName, sourceTier, sourceUrl,
                publishedDate, factType, verbatimEvidenceSpan, topic, ageDays,
                displaySummaryVi, displaySummaryEn, evidenceLanguage, false,
                ProductMarketScope.INTERNATIONAL, "Global / regional");
    }

    public CurrentProductNewsItem(String factCode, long rawDocId, String title,
                                  String sourceCode, String sourceName, int sourceTier,
                                  String sourceUrl, LocalDate publishedDate, String factType,
                                  String verbatimEvidenceSpan, CurrentProductNewsTopic topic,
                                  long ageDays, String displaySummaryVi, String displaySummaryEn,
                                  String evidenceLanguage, boolean manuallySupplied) {
        this(factCode, rawDocId, title, sourceCode, sourceName, sourceTier, sourceUrl,
                publishedDate, factType, verbatimEvidenceSpan, topic, ageDays,
                displaySummaryVi, displaySummaryEn, evidenceLanguage, manuallySupplied,
                ProductMarketScope.INTERNATIONAL, "Global / regional");
    }

    /**
     * Compatibility constructor for callers that only have the immutable source
     * record.  It deliberately leaves the display summaries empty rather than
     * pretending that an original-language title or quotation was translated.
     */
    public CurrentProductNewsItem(String factCode, long rawDocId, String title,
                                  String sourceCode, String sourceName, int sourceTier,
                                  String sourceUrl, LocalDate publishedDate, String factType,
                                  String verbatimEvidenceSpan, CurrentProductNewsTopic topic,
                                  long ageDays) {
        this(factCode, rawDocId, title, sourceCode, sourceName, sourceTier, sourceUrl,
                publishedDate, factType, verbatimEvidenceSpan, topic, ageDays, null, null, null, false);
    }

    public String getTopicLabelEn() { return topic.getLabelEn(); }
    public String getTopicLabelVi() { return topic.getLabelVi(); }
    public String getReviewQuestionEn() { return topic.getReviewQuestionEn(); }
    public String getReviewQuestionVi() { return topic.getReviewQuestionVi(); }
    public String getValidationStepEn() { return topic.getValidationStepEn(); }
    public String getValidationStepVi() { return topic.getValidationStepVi(); }

    /** The document title is an immutable source label, never a generated translation. */
    public String getOriginalTitle() { return title; }
    /** Exact stored span; it must remain in its source language for audit. */
    public String getOriginalEvidence() { return verbatimEvidenceSpan; }

    /**
     * A language-specific, extractor-provided display summary.  An empty value
     * is intentional: the UI should then say that only original-language
     * evidence is available, rather than mislabelling the quote as translated.
     */
    public String getDisplaySummaryVi() { return BilingualTextPolicy.safeDisplaySummary(displaySummaryVi, true); }
    public String getDisplaySummaryEn() { return BilingualTextPolicy.safeDisplaySummary(displaySummaryEn, false); }
    public String getDisplaySummary(boolean vi) {
        return vi ? getDisplaySummaryVi() : getDisplaySummaryEn();
    }

    public String getEvidenceLanguage() { return evidenceLanguage == null || evidenceLanguage.isBlank() ? "unknown" : evidenceLanguage; }
    public boolean isManuallySupplied() { return manuallySupplied; }
    public boolean isVietnamMarket() { return marketScope == ProductMarketScope.VIETNAM; }
    public String getMarketScopeLabelEn() {
        return isVietnamMarket() ? "Vietnam" : "International";
    }
    public String getMarketScopeLabelVi() {
        return isVietnamMarket() ? "Việt Nam" : "Quốc tế";
    }
    public String getGeographyLabelEn() { return geography; }
    public String getGeographyLabelVi() {
        return switch (geography) {
            case "Vietnam" -> "Việt Nam";
            case "South Korea" -> "Hàn Quốc";
            case "Japan" -> "Nhật Bản";
            case "China" -> "Trung Quốc";
            case "Global / regional" -> "Toàn cầu / khu vực";
            default -> geography;
        };
    }
    public boolean hasExternalSourceLink() {
        return sourceUrl != null && (sourceUrl.startsWith("https://") || sourceUrl.startsWith("http://"));
    }
    /** JavaBean boolean accessor used by Thymeleaf's property resolver. */
    public boolean isExternalSourceLink() { return hasExternalSourceLink(); }

    /** Legacy template aliases now return the independent authority dimension. */
    public String getSourceTierLabelEn() { return getSourceAuthorityLabelEn(); }
    public String getSourceTierLabelVi() { return getSourceAuthorityLabelVi(); }
    public String getSourceAuthorityLabelEn() { return sourceAuthority.replace('_', ' '); }
    public String getSourceAuthorityLabelVi() {
        return switch (authority()) {
            case REGULATOR -> "Cơ quan quản lý";
            case STATUTORY_DISCLOSURE -> "Công bố pháp định";
            case OFFICIAL_COMPANY -> "Nguồn chính thức doanh nghiệp";
            case INDUSTRY_BODY -> "Hiệp hội ngành";
            case SPECIALIST_RESEARCH -> "Nghiên cứu chuyên ngành";
            case ESTABLISHED_MEDIA -> "Báo chí uy tín";
            case PROFESSIONAL_SERVICES -> "Tổ chức tư vấn chuyên môn";
            case OTHER_PUBLISHER -> "Nhà xuất bản khác";
            case SOCIAL_OR_BLOG -> "Mạng xã hội/blog";
            case UNKNOWN -> "Chưa xác định";
        };
    }
    public int getSourceAuthorityScore() { return authority().credibilityScore(); }
    public boolean isPrimaryAuthority() { return authority().isPrimaryEvidence(); }

    public String getFreshnessLabelEn() {
        if (ageDays == 0) return "Published today";
        if (ageDays == 1) return "Published 1 day ago";
        return "Published " + ageDays + " days ago";
    }

    public String getFreshnessLabelVi() {
        if (ageDays == 0) return "Công bố hôm nay";
        return "Công bố " + ageDays + " ngày trước";
    }

    private SourceAuthority authority() {
        return normalizedAuthority(sourceAuthority, sourceTier);
    }

    private static SourceAuthority normalizedAuthority(String value, int tier) {
        if (value != null && !value.isBlank()) {
            try {
                return SourceAuthority.valueOf(value.strip().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return SourceAuthority.UNKNOWN;
            }
        }
        return switch (tier) {
            case 1 -> SourceAuthority.OFFICIAL_COMPANY;
            case 2 -> SourceAuthority.ESTABLISHED_MEDIA;
            case 3 -> SourceAuthority.OTHER_PUBLISHER;
            default -> SourceAuthority.UNKNOWN;
        };
    }
}
