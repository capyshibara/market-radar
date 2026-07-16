package com.marketradar.product;

import java.util.Locale;
import java.util.Set;

/**
 * Deterministic Product reading lenses for current, source-backed facts.
 *
 * <p>The copy deliberately asks a review question and proposes a validation
 * step. It never asserts that the cited item changes our portfolio or proves a
 * market trend.</p>
 */
public enum CurrentProductNewsTopic {
    REGULATION(
            "Regulation & compliance", "Quy định & tuân thủ",
            "Could this rule affect product design, filing, disclosure, wording, or implementation timing?",
            "Quy định này có thể ảnh hưởng đến thiết kế, hồ sơ, công bố, điều khoản hoặc thời điểm triển khai sản phẩm không?",
            "Confirm applicability, scope, and effective date with Legal or Compliance before changing a product.",
            "Xác nhận phạm vi áp dụng và ngày hiệu lực với Pháp chế hoặc Tuân thủ trước khi thay đổi sản phẩm."),
    PRODUCT_AND_BENEFITS(
            "Products, benefits & pricing", "Sản phẩm, quyền lợi & định phí",
            "What product proposition, benefit, eligibility, fee, or pricing detail should Product compare?",
            "Product nên so sánh chi tiết nào về định vị sản phẩm, quyền lợi, điều kiện tham gia, phí hoặc định phí?",
            "Compare only the cited detail with our current proposition; verify market, segment, and effective date first.",
            "Chỉ so sánh chi tiết được trích dẫn với sản phẩm hiện tại; trước tiên cần xác minh thị trường, phân khúc và ngày hiệu lực."),
    MARKET_METRICS(
            "Market & portfolio metrics", "Chỉ số thị trường & danh mục",
            "Which product, customer segment, period, and denominator does this metric actually cover?",
            "Chỉ số này thực sự bao phủ sản phẩm, phân khúc khách hàng, giai đoạn và mẫu số nào?",
            "Validate the measurement basis and comparability before using the number as a benchmark.",
            "Xác minh cơ sở đo lường và khả năng so sánh trước khi dùng con số làm chuẩn đối chiếu."),
    DISTRIBUTION(
            "Distribution & customer access", "Phân phối & tiếp cận khách hàng",
            "Could this channel development affect how a product is packaged, explained, sold, or supported?",
            "Diễn biến kênh này có thể ảnh hưởng đến cách đóng gói, giải thích, bán hoặc hỗ trợ sản phẩm không?",
            "Validate the channel, customer segment, and operating model before proposing a Product response.",
            "Xác minh kênh, phân khúc khách hàng và mô hình vận hành trước khi đề xuất phản ứng của Product."),
    OTHER_PRODUCT_SIGNAL(
            "Other Product signals", "Tín hiệu Product khác",
            "Which Product assumption could this source help the team validate or challenge?",
            "Nguồn này có thể giúp đội ngũ kiểm chứng hoặc phản biện giả định Product nào?",
            "Open the source, identify the exact Product decision in scope, and seek corroboration before acting.",
            "Mở nguồn, xác định chính xác quyết định Product liên quan và tìm nguồn đối chứng trước khi hành động.");

    private final String labelEn;
    private final String labelVi;
    private final String reviewQuestionEn;
    private final String reviewQuestionVi;
    private final String validationStepEn;
    private final String validationStepVi;

    CurrentProductNewsTopic(String labelEn, String labelVi,
                            String reviewQuestionEn, String reviewQuestionVi,
                            String validationStepEn, String validationStepVi) {
        this.labelEn = labelEn;
        this.labelVi = labelVi;
        this.reviewQuestionEn = reviewQuestionEn;
        this.reviewQuestionVi = reviewQuestionVi;
        this.validationStepEn = validationStepEn;
        this.validationStepVi = validationStepVi;
    }

    public String getLabelEn() { return labelEn; }
    public String getLabelVi() { return labelVi; }
    public String getReviewQuestionEn() { return reviewQuestionEn; }
    public String getReviewQuestionVi() { return reviewQuestionVi; }
    public String getValidationStepEn() { return validationStepEn; }
    public String getValidationStepVi() { return validationStepVi; }

    public String label(boolean vi) { return vi ? labelVi : labelEn; }
    public String reviewQuestion(boolean vi) { return vi ? reviewQuestionVi : reviewQuestionEn; }
    public String validationStep(boolean vi) { return vi ? validationStepVi : validationStepEn; }

    /** Closed classifier labels are the primary routing evidence; fact type is a safe fallback. */
    public static CurrentProductNewsTopic from(Set<String> labels, String factType) {
        Set<String> normalized = labels == null ? Set.of() : labels.stream()
                .filter(label -> label != null && !label.isBlank())
                .map(label -> label.strip().toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (normalized.contains("PRODUCT_REGULATION") || "REGULATION".equalsIgnoreCase(factType)) {
            return REGULATION;
        }
        if (normalized.contains("FEE_BENEFIT_COMMISSION_CHANGE")
                || normalized.contains("PRODUCT_LAUNCH")
                || "FEE_CHANGE".equalsIgnoreCase(factType)
                || "PRODUCT_LAUNCH".equalsIgnoreCase(factType)) {
            return PRODUCT_AND_BENEFITS;
        }
        if (normalized.contains("SALES_DATA") || "METRIC".equalsIgnoreCase(factType)) {
            return MARKET_METRICS;
        }
        if (normalized.contains("DISTRIBUTION_CHANNEL")) return DISTRIBUTION;
        return OTHER_PRODUCT_SIGNAL;
    }
}
