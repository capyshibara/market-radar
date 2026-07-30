package com.marketradar.report.bi;

import java.util.List;

/**
 * Một nhận định đã tổng hợp, gắn với đúng 1 trong 7 bucket của Business Intelligence Report.
 * Là điểm chung giữa 2 nguồn dữ liệu khác hẳn nhau (report định kỳ đọc từ kho evidence đã
 * duyệt; Deep Research tổng hợp tức thời từ kết quả agent vừa tìm/đọc) — BiReportPageBuilder
 * và template chỉ biết đến BiFinding, không quan tâm nó tới từ đâu.
 *
 * @param bucket     một trong Bucket hằng số bên dưới
 * @param subjectKey khoá nhóm cho bucket cần gộp theo chủ thể (STRATEGIC_COMPARISON: tên cặp
 *                   so sánh; SCHEDULED_EVENT/COMPANY_EVENT: tên công ty/mốc) — null nếu bucket
 *                   không cần nhóm (MACRO_ECONOMIC, TECH_AI_SIGNAL dùng ngay 1 finding/thẻ)
 * @param textVi     nội dung nhận định, tiếng Việt (pipeline hiện tại 100% tiếng Việt)
 * @param highlight  true nếu đủ quan trọng để lên trang Tóm tắt điều hành (EXEC)
 */
public record BiFinding(String bucket, String subjectKey, String textVi,
                        boolean highlight, List<BiCitation> citations) {

    public BiFinding {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    public static final String MACRO_ECONOMIC = "MACRO_ECONOMIC";
    public static final String COMPETITIVE_THEME = "COMPETITIVE_THEME";
    public static final String SCHEDULED_EVENT = "SCHEDULED_EVENT";
    public static final String COMPANY_EVENT = "COMPANY_EVENT";
    public static final String MARKET_SHARE_OR_AWARD = "MARKET_SHARE_OR_AWARD";
    public static final String TECH_AI_SIGNAL = "TECH_AI_SIGNAL";
    public static final String STRATEGIC_COMPARISON = "STRATEGIC_COMPARISON";
}
