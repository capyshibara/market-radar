package com.marketradar.domain;

/** Phòng ban nhận tin đã route. COMPLIANCE thêm 2026-07-17: dữ liệu corpus có 222
 *  evidence fact loại REGULATION nhưng chưa có bàn nào ngoài PRODUCT nhận chúng. */
public enum Department {
    PRODUCT("Phòng Sản phẩm"),
    SALES("Phòng Kinh doanh"),
    COMPLIANCE("Phòng Pháp chế & Tuân thủ");

    private final String ten;
    Department(String ten) { this.ten = ten; }
    public String getTen() { return ten; }
}
