package com.marketradar.report.bi;

/**
 * Một trích dẫn cho 1 BiFinding — đủ chung để phục vụ cả 2 nguồn:
 * report định kỳ và Deep Research. Authority, market and intake method are independent axes.
 *
 * @param label     tên hiển thị (tên nguồn đã đăng ký, hoặc domain của URL vừa đọc)
 * @param tierNote  compatibility display note; now contains authority + acquisition lineage
 * @param url       link để bấm mở; null nếu không có (vd trích dẫn nội bộ /claims)
 */
public record BiCitation(String label, String tierNote, String url,
                         String authority, String marketCode, String intakeMethod) {
    public BiCitation(String label, String tierNote, String url) {
        this(label, tierNote, url, "UNKNOWN", null, null);
    }
}
