package com.marketradar.domain;

/**
 * Core insurance-market taxonomy. Department-specific relevance is mapped by
 * RoutingRule; it is not baked into this enum.
 */
public enum Category {
    // Reusable Strategy/market-intelligence dimensions.
    MACRO_ECONOMIC("Vĩ mô và bối cảnh kinh tế"),
    INDUSTRY_REGULATION("Chính sách/quy định toàn ngành"),
    MARKET_STRUCTURE("Thị phần, cạnh tranh và cấu trúc thị trường"),
    COMPANY_FINANCIAL_PERFORMANCE("Kết quả tài chính và chỉ số doanh nghiệp"),
    CORPORATE_ACTION("M&A, đầu tư, hợp tác và thay đổi doanh nghiệp"),
    TECHNOLOGY_AI("Công nghệ, dữ liệu, AI và insurtech"),
    CUSTOMER_EXPERIENCE("Hành vi, nhu cầu và trải nghiệm khách hàng"),
    PEOPLE_TALENT("Lãnh đạo, nhân sự và năng lực tổ chức"),
    BRAND_REPUTATION("Thương hiệu, giải thưởng và uy tín"),
    STRATEGIC_RESEARCH("Nghiên cứu so sánh có giá trị chiến lược"),

    // Product lens retained for compatibility and precise Product routing.
    PRODUCT_LAUNCH("Ra mắt/phê duyệt sản phẩm mới"),
    FEE_BENEFIT_COMMISSION_CHANGE("Thay đổi phí, quyền lợi, hoa hồng"),
    PRODUCT_REGULATION("Quy định pháp lý về sản phẩm"),
    SALES_DATA("Dữ liệu doanh số/phí bảo hiểm công bố"),
    DISTRIBUTION_CHANNEL("Kênh phân phối (đại lý, banca, digital)");

    private final String moTa;
    Category(String moTa) { this.moTa = moTa; }
    public String getMoTa() { return moTa; }
}
