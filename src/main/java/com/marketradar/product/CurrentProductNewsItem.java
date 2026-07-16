package com.marketradar.product;

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
        long ageDays) {

    public String getTopicLabelEn() { return topic.getLabelEn(); }
    public String getTopicLabelVi() { return topic.getLabelVi(); }
    public String getReviewQuestionEn() { return topic.getReviewQuestionEn(); }
    public String getReviewQuestionVi() { return topic.getReviewQuestionVi(); }
    public String getValidationStepEn() { return topic.getValidationStepEn(); }
    public String getValidationStepVi() { return topic.getValidationStepVi(); }

    public String getSourceTierLabelEn() { return "Tier " + sourceTier + " source"; }
    public String getSourceTierLabelVi() { return "Nguồn cấp " + sourceTier; }

    public String getFreshnessLabelEn() {
        if (ageDays == 0) return "Published today";
        if (ageDays == 1) return "Published 1 day ago";
        return "Published " + ageDays + " days ago";
    }

    public String getFreshnessLabelVi() {
        if (ageDays == 0) return "Công bố hôm nay";
        return "Công bố " + ageDays + " ngày trước";
    }
}
