package com.marketradar.intelligence;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Product-department event taxonomy inferred only from the cited title/summary/span.
 * It does not trust a legacy extractor label without semantic evidence.
 */
public final class ProductEventTaxonomy {

    private ProductEventTaxonomy() {}
    public static final String VERSION = "product-event-taxonomy-v2";

    public enum EventType {
        PRODUCT_LAUNCH,
        PRODUCT_CHANGE,
        BENEFIT_CHANGE,
        PRICING_CHANGE,
        PRODUCT_WITHDRAWAL,
        REGULATORY_CHANGE,
        CUSTOMER_NEED_SIGNAL,
        COMPETITIVE_PERFORMANCE,
        DISTRIBUTION_CHANGE,
        SERVICE_EXPERIENCE_CHANGE,
        MARKETING_PROMOTION,
        CORPORATE_NEWS,
        OTHER
    }

    private static final String[] PROMOTION = {
            "promotion", "promotional", "campaign", "voucher", "giveaway", "khuyến mãi",
            "khuyến mại", "hoàn phí", "chiến dịch", "ưu đãi", "quà tặng"
    };
    private static final String[] WITHDRAWAL = {
            "withdraw", "withdrawal", "suspend", "suspension", "terminate product", "discontinue",
            "tạm ngừng", "ngừng triển khai", "dừng bán", "chấm dứt sản phẩm"
    };
    private static final String[] LAUNCH = {
            "launch", "launched", "launches", "introduce", "introduced", "introduces",
            "unveil", "unveiled", "pioneer", "pioneers", "new insurance plan",
            "new insurance product", "ra mắt", "giới thiệu", "sản phẩm mới"
    };
    private static final String[] PRODUCT = {
            "insurance product", "insurance products", "insurance plan", "insurance plans", "policy", "rider", "coverage", "benefit",
            "shield plan", "life protection", "investment-linked", "sản phẩm bảo hiểm",
            "sản phẩm bảo vệ", "hợp đồng bảo hiểm", "quyền lợi", "bảo hiểm bổ trợ"
    };
    private static final String[] BENEFIT = {
            "benefit", "coverage", "sum assured", "hospital network", "quyền lợi", "phạm vi bảo hiểm",
            "số tiền bảo hiểm", "mạng lưới bệnh viện"
    };
    private static final String[] PRICE = {
            "premium", "fee", "price", "deductible", "commission", "phí bảo hiểm", "mức phí",
            "giá", "khấu trừ", "hoa hồng"
    };
    private static final String[] CHANGE = {
            "increase", "increased", "increases", "decrease", "decreased", "decreases",
            "expand", "expanded", "expands", "change", "changed", "changes", "adjust",
            "adjusted", "adjusts", "double", "doubled", "doubles", "reduce", "reduced", "reduces",
            "revise", "revised", "revises",
            "transfer", "transfers", "partner", "partnership", "integrate", "deploy", "new",
            "latest", "tăng", "giảm", "mở rộng", "thay đổi", "điều chỉnh", "gấp đôi", "sửa đổi",
            "hợp tác", "ký kết"
    };
    private static final String[] REGULATION = {
            "regulation", "regulatory", "law", "decree", "circular", "effective date", "compliance",
            "quy định", "pháp luật", "nghị định", "thông tư", "ngày hiệu lực", "tuân thủ"
    };
    private static final String[] CUSTOMER_NEED = {
            "survey", "study", "research", "protection gap", "unmet need", "index found", "respondents",
            "decumulation", "longevity solutions", "khảo sát", "nghiên cứu", "khoảng trống bảo vệ",
            "nhu cầu chưa được đáp ứng", "người trả lời", "giải pháp trường thọ"
    };
    private static final String[] DISTRIBUTION = {
            "bancassurance", "embedded insurance", "digital distribution", "digital insurance platform",
            "agent network", "advisor network", "partnership", "phân phối", "bảo hiểm nhúng",
            "nền tảng bảo hiểm số", "mạng lưới đại lý", "mạng lưới tư vấn", "hợp tác"
    };
    private static final String[] SERVICE = {
            "app feature", "customer journey", "self-service", "claims service", "concierge",
            "premium reminder", "payment reminder", "tính năng ứng dụng", "hành trình khách hàng",
            "tự phục vụ", "dịch vụ bồi thường", "nhắc phí", "nhắc đóng phí"
    };
    private static final String[] PERFORMANCE = {
            "sales", "revenue", "premium income", "new business", "market share", "profit",
            "doanh số", "doanh thu", "phí bảo hiểm", "khai thác mới", "thị phần", "lợi nhuận"
    };
    private static final String[] CORPORATE = {
            "award", "anniversary", "appointment", "financial results", "csr", "charity",
            "accreditation", "reaccreditation", "disaster relief", "flood relief",
            "giải thưởng", "kỷ niệm", "bổ nhiệm", "kết quả tài chính", "từ thiện",
            "chung tay", "mưa lũ", "khắc phục hậu quả"
    };

    public static EventType classify(String title, String summary, String evidenceSpan,
                                     String legacyFactType) {
        String text = normalize(join(title, summary, evidenceSpan));
        if (containsAny(text, PROMOTION)) return EventType.MARKETING_PROMOTION;
        if (containsAny(text, WITHDRAWAL) && containsAny(text, PRODUCT)) return EventType.PRODUCT_WITHDRAWAL;
        if (containsAny(text, CUSTOMER_NEED)) return EventType.CUSTOMER_NEED_SIGNAL;
        if (containsAny(text, REGULATION)) return EventType.REGULATORY_CHANGE;
        if ("METRIC".equalsIgnoreCase(legacyFactType) && containsAny(text, PERFORMANCE)) {
            return EventType.COMPETITIVE_PERFORMANCE;
        }
        // Service operations may mention premiums or benefits incidentally (for example
        // changing the timing of a premium reminder). Route the customer-process change
        // before pricing/benefit keyword rules.
        if (containsAny(text, SERVICE) && (containsAny(text, CHANGE) || containsAny(text, LAUNCH))) {
            return EventType.SERVICE_EXPERIENCE_CHANGE;
        }
        if (containsAny(text, BENEFIT) && containsAny(text, CHANGE)) return EventType.BENEFIT_CHANGE;
        if (containsAny(text, PRICE) && containsAny(text, CHANGE)) return EventType.PRICING_CHANGE;
        if (containsAny(text, LAUNCH) && containsAny(text, PRODUCT)) return EventType.PRODUCT_LAUNCH;
        if (containsAny(text, DISTRIBUTION) && (containsAny(text, CHANGE) || containsAny(text, LAUNCH))) {
            return EventType.DISTRIBUTION_CHANGE;
        }
        if (containsAny(text, PRODUCT) && containsAny(text, CHANGE)) return EventType.PRODUCT_CHANGE;
        if (containsAny(text, PERFORMANCE) || "METRIC".equalsIgnoreCase(legacyFactType)) {
            return EventType.COMPETITIVE_PERFORMANCE;
        }
        if (containsAny(text, CORPORATE)) return EventType.CORPORATE_NEWS;
        return EventType.OTHER;
    }

    public static boolean isProductOffer(EventType type) {
        return type == EventType.PRODUCT_LAUNCH || type == EventType.PRODUCT_CHANGE
                || type == EventType.BENEFIT_CHANGE || type == EventType.PRICING_CHANGE
                || type == EventType.PRODUCT_WITHDRAWAL;
    }

    private static boolean containsAny(String text, String[] terms) {
        for (String term : terms) {
            String regex = "(?<![\\p{L}\\p{N}])" + Pattern.quote(term) + "(?![\\p{L}\\p{N}])";
            if (Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private static String join(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) if (value != null && !value.isBlank()) result.append(' ').append(value.strip());
        return result.toString();
    }

    private static String normalize(String value) { return value.toLowerCase(Locale.ROOT); }
}
