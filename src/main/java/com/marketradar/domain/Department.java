package com.marketradar.domain;

/** Audience lens. Reusable intelligence topics route deterministically to one or
 * more departments; each department can then apply its own curation priority. */
public enum Department {
    STRATEGY("Phòng Chiến lược"),
    PRODUCT("Phòng Sản phẩm"),
    SALES("Phòng Kinh doanh"),
    COMPLIANCE("Phòng Pháp chế & Tuân thủ");

    private final String ten;
    Department(String ten) { this.ten = ten; }
    public String getTen() { return ten; }
}
