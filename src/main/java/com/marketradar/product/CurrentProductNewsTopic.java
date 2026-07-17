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
            "Bộ phận Sản phẩm nên so sánh chi tiết nào về định vị sản phẩm, quyền lợi, điều kiện tham gia, phí hoặc định phí?",
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
            "Xác minh kênh, phân khúc khách hàng và mô hình vận hành trước khi đề xuất phản ứng của Bộ phận Sản phẩm."),
    OTHER_PRODUCT_SIGNAL(
            "Other Product signals", "Tín hiệu Sản phẩm khác",
            "Which Product assumption could this source help the team validate or challenge?",
            "Nguồn này có thể giúp đội ngũ kiểm chứng hoặc phản biện giả định nào của Sản phẩm?",
            "Open the source, identify the exact Product decision in scope, and seek corroboration before acting.",
            "Mở nguồn, xác định chính xác quyết định Sản phẩm liên quan và tìm nguồn đối chứng trước khi hành động.");

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

    /** Background a non-Product reader needs before interpreting this kind of source. */
    public String readerContext(boolean vi) {
        if (vi) {
            return switch (this) {
                case REGULATION -> "Một bài về quy định có thể đồng thời nhắc đến dự thảo, tham vấn, ngày ban hành và ngày hiệu lực. Product cần tách rõ các mốc này, vì một quy định được công bố chưa chắc đã áp dụng ngay hoặc áp dụng cho mọi sản phẩm.";
                case PRODUCT_AND_BENEFITS -> "Thông báo sản phẩm thường kết hợp lời hứa với khách hàng và cơ chế thực hiện. Tiêu đề cho biết định vị; giá trị thực lại nằm trong quyền lợi, điều kiện tham gia, loại trừ, phí, kênh bán và cách phục vụ.";
                case MARKET_METRICS -> "Mọi tỷ lệ đều có tử số, mẫu số, nhóm đối tượng và giai đoạn đo. Nếu thiếu các phần đó, con số có thể hữu ích như một tín hiệu định hướng nhưng rất dễ trở thành chuẩn đối chiếu sai.";
                case DISTRIBUTION -> "Thay đổi kênh không chỉ là câu chuyện bán hàng. Nó có thể thay đổi người giải thích sản phẩm, dữ liệu được thu thập, cách kiểm tra phù hợp, cách phục vụ sau bán và người xử lý ngoại lệ.";
                case OTHER_PRODUCT_SIGNAL -> "Hồ sơ này không nằm gọn trong một lăng kính Product tiêu chuẩn. Hãy coi nó là điểm bắt đầu để xác định giả định nào cần kiểm tra, không phải là một kết luận sẵn có.";
            };
        }
        return switch (this) {
            case REGULATION -> "A regulatory article may mention a proposal, consultation, publication date and effective date at once. Product must separate them: an announced rule may not apply immediately, or to every product.";
            case PRODUCT_AND_BENEFITS -> "Product announcements combine a customer promise with operating mechanics. The headline conveys positioning; the real proposition sits in benefits, eligibility, exclusions, fees, channel and servicing.";
            case MARKET_METRICS -> "Every percentage has a numerator, denominator, population and measurement period. Without them, a number can be directionally useful yet become a misleading benchmark.";
            case DISTRIBUTION -> "A channel change is not only a sales story. It can change who explains the product, what data is collected, how suitability is checked, how the policy is serviced and who handles exceptions.";
            case OTHER_PRODUCT_SIGNAL -> "This record does not fit neatly into a standard Product lens. Treat it as a starting point for identifying an assumption to test, not as a ready-made conclusion.";
        };
    }

    /** Plain-language reason this source type can affect a Product decision. */
    public String productMeaning(boolean vi) {
        if (vi) {
            return switch (this) {
                case REGULATION -> "Nếu thực sự áp dụng, thay đổi này có thể tác động đến điều khoản, định phí, hồ sơ phê duyệt, tài liệu bán hàng, đào tạo kênh, logic hệ thống và lịch ra mắt. Việc đầu tiên là xác minh phạm vi, chưa phải thiết kế lại ngay.";
                case PRODUCT_AND_BENEFITS -> "Các cơ chế này cho thấy nhu cầu khách hàng mà sản phẩm đang cố giải quyết và gánh nặng vận hành đi kèm. Điều nên so sánh là từng thành phần và điều kiện, không phải câu quảng cáo hoặc thương hiệu.";
                case MARKET_METRICS -> "Product có thể dùng chỉ số để chọn phân khúc, định giá hoặc điều chỉnh danh mục. Một phép so sánh không tương đương có thể khiến đội ngũ ưu tiên sai nhu cầu hoặc đặt mục tiêu không thực tế.";
                case DISTRIBUTION -> "Thiết kế sản phẩm chưa hoàn chỉnh nếu hành trình kênh không thể giải thích, bán và phục vụ sản phẩm một cách an toàn. Product vì vậy phải thiết kế cả trải nghiệm và quyền sở hữu vận hành.";
                case OTHER_PRODUCT_SIGNAL -> "Nguồn này có thể giúp phát hiện một giả định về khách hàng, danh mục hoặc mô hình vận hành. Giá trị của nó đến từ câu hỏi kiểm chứng tiếp theo, không phải từ việc xuất hiện trong báo cáo.";
            };
        }
        return switch (this) {
            case REGULATION -> "If applicable, the change may affect wording, pricing, filing, sales material, channel training, system logic and launch timing. The first task is to confirm scope—not to redesign immediately.";
            case PRODUCT_AND_BENEFITS -> "These mechanics reveal the customer need being addressed and the operating burden that travels with it. Compare components and conditions, not advertising language or brand names.";
            case MARKET_METRICS -> "Product may use metrics to choose segments, price or reshape the portfolio. A non-comparable benchmark can send the team toward the wrong need or an unrealistic target.";
            case DISTRIBUTION -> "A product is incomplete if the channel journey cannot explain, sell and service it safely. Product therefore has to design both the customer experience and operating ownership.";
            case OTHER_PRODUCT_SIGNAL -> "The source may expose an assumption about customers, portfolio or operating model. Its value comes from the next validation question, not merely from appearing in the report.";
        };
    }

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
