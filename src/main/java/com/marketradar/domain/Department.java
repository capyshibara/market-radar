package com.marketradar.domain;

/** Phòng ban nhận tin đã route. COMPLIANCE thêm 2026-07-17: dữ liệu corpus có 222
 *  evidence fact loại REGULATION nhưng chưa có bàn nào ngoài PRODUCT nhận chúng.
 *  STRATEGY thêm sau: KHÔNG phải đích routing tin theo category như 3 bàn kia (không quy tắc
 *  routing nào gán STRATEGY — tổng hợp xuyên phòng ban, không phải một chủ đề hẹp) — bàn này
 *  hiển thị 0 tin đã route một cách trung thực; "report" của nó là BI Report/Deep Research/Web
 *  Search (xem DeskController, tự route thẳng thay vì feed rỗng theo khuôn 3 bàn kia). */
public enum Department {
    STRATEGY("Phòng Chiến lược"),
    PRODUCT("Phòng Sản phẩm"),
    SALES("Phòng Kinh doanh"),
    COMPLIANCE("Phòng Pháp chế & Tuân thủ");

    private final String ten;
    Department(String ten) { this.ten = ten; }
    public String getTen() { return ten; }
}
