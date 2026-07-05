package com.marketradar.domain;

/**
 * Taxonomy 5 category cho domain product intelligence (MVP).
 * Đây là ENUM ĐÓNG — nhãn LLM trả về ngoài danh sách này bị schema-reject.
 */
public enum Category {
    PRODUCT_LAUNCH("Ra mắt/phê duyệt sản phẩm mới"),
    FEE_BENEFIT_COMMISSION_CHANGE("Thay đổi phí, quyền lợi, hoa hồng"),
    PRODUCT_REGULATION("Quy định pháp lý về sản phẩm"),
    SALES_DATA("Dữ liệu doanh số/phí bảo hiểm công bố"),
    DISTRIBUTION_CHANNEL("Kênh phân phối (đại lý, banca, digital)");

    private final String moTa;
    Category(String moTa) { this.moTa = moTa; }
    public String getMoTa() { return moTa; }
}
