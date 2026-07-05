package vn.techcomlife.marketradar.domain;

/** 2 phòng ban demo theo scope MVP. */
public enum Department {
    PRODUCT("Phòng Sản phẩm"),
    SALES("Phòng Kinh doanh");

    private final String ten;
    Department(String ten) { this.ten = ten; }
    public String getTen() { return ten; }
}
