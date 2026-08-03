package com.marketradar.report.bi;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.marketradar.product.ProductMarketScope;

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
 * @param textVi     nội dung nhận định, tiếng Việt
 * @param textEn     bản tiếng Anh — null khi nguồn gốc chưa có bản dịch (fallback về textVi khi
 *                   render bản EN, xem {@link #text(boolean)}); không bao giờ tự dịch máy ở tầng
 *                   trình bày, chỉ dùng bản đã có sẵn từ nguồn dữ liệu (insight/LLM synthesis)
 * @param highlight  true nếu đủ quan trọng để lên trang Tóm tắt điều hành (EXEC)
 * @param severity      HIGH/MEDIUM/LOW — CHỈ áp dụng cho TECH_AI_SIGNAL: có giá trị nghĩa là đây
 *                      là 1 dòng AI Threat Map (đánh giá rủi ro theo công ty, trang riêng); null
 *                      nghĩa là đây là 1 số liệu định cỡ thị trường AI/insurtech (trang KPI
 *                      riêng). Null với mọi bucket khác.
 * @param metricPercent 0-100, CHỈ áp dụng cho MARKET_SHARE_OR_AWARD khi có số liệu thật để vẽ
 *                      thanh bar (vd thị phần APE %) — null thì trang đó tự chuyển sang trình bày
 *                      dạng bảng (MATRIX) thay vì bịa % để vẽ bar.
 * @param scope      VIETNAM hay INTERNATIONAL — suy ra DETERMINISTIC từ nguồn của bằng chứng
 *                   (ProductMarketScopeClassifier, KHÔNG phải LLM đoán). Hiển thị công khai trên
 *                   từng finding trong report thay vì ẩn/lọc cứng nguồn quốc tế — đúng tinh thần
 *                   CFO nêu (đừng để một tin quốc tế bị đọc nhầm thành đối thủ tại Việt Nam).
 * @param geography  nhãn địa lý cụ thể hơn khi INTERNATIONAL (vd "Japan", "Hong Kong"); "Vietnam"
 *                   khi VIETNAM; "Global / regional" khi không xác định được quốc gia cụ thể.
 */
public record BiFinding(String bucket, String subjectKey, String textVi, String textEn,
                        boolean highlight, List<BiCitation> citations, String severity,
                        Integer metricPercent, ProductMarketScope scope, String geography) {

    public BiFinding {
        citations = citations == null ? List.of() : List.copyOf(citations);
        if (severity != null) {
            String normalized = severity.strip().toUpperCase(java.util.Locale.ROOT);
            severity = switch (normalized) {
                case "HIGH", "MEDIUM", "LOW" -> normalized;
                default -> null;
            };
        }
        if (metricPercent != null) metricPercent = Math.max(0, Math.min(100, metricPercent));
        scope = scope == null ? ProductMarketScope.INTERNATIONAL : scope;
        geography = geography == null || geography.isBlank() ? "Global / regional" : geography.strip();
    }

    /** Convenience constructor for the common case of no severity/metric. */
    public BiFinding(String bucket, String subjectKey, String textVi, String textEn,
                     boolean highlight, List<BiCitation> citations,
                     ProductMarketScope scope, String geography) {
        this(bucket, subjectKey, textVi, textEn, highlight, citations, null, null, scope, geography);
    }

    /** Convenience constructor for VI-only content (no English translation available yet). */
    public BiFinding(String bucket, String subjectKey, String textVi,
                     boolean highlight, List<BiCitation> citations,
                     ProductMarketScope scope, String geography) {
        this(bucket, subjectKey, textVi, null, highlight, citations, null, null, scope, geography);
    }

    /** The finding text in the requested language — falls back to Vietnamese rather than
     *  rendering blank when no English variant exists yet. */
    public String text(boolean vi) {
        if (vi) return textVi;
        return textEn != null && !textEn.isBlank() ? textEn : textVi;
    }

    // 2026-08-03: @JsonIgnore BẮT BUỘC — Jackson tự coi mọi isXxx() là 1 thuộc tính JSON
    // ("vietnamMarket") dù không phải field thật của record. Ghi thì vô hại (dư 1 trường), nhưng
    // đọc lại bằng ObjectMapper mặc định (FAIL_ON_UNKNOWN_PROPERTIES=true) thì vỡ ngay vì record
    // không có tham số "vietnamMarket" nào để nhận — đây chính là nguyên nhân lỗi
    // "Không đọc được nội dung đã lưu" của Deep Research (contentJson ghi thành công, đọc lại vỡ).
    @JsonIgnore
    public boolean isVietnamMarket() { return scope == ProductMarketScope.VIETNAM; }

    /** Short badge label for the report — "Việt Nam"/"Vietnam" or the specific international
     *  geography (falls back to "Global / regional" when no country could be determined). */
    public String marketLabel(boolean vi) {
        if (isVietnamMarket()) return vi ? "Việt Nam" : "Vietnam";
        if (!vi) return geography;
        return switch (geography) {
            case "Hong Kong" -> "Hồng Kông";
            case "Singapore" -> "Singapore";
            case "Taiwan" -> "Đài Loan";
            case "South Korea" -> "Hàn Quốc";
            case "Japan" -> "Nhật Bản";
            case "China" -> "Trung Quốc";
            case "Indonesia" -> "Indonesia";
            case "Malaysia" -> "Malaysia";
            case "Philippines" -> "Philippines";
            case "Thailand" -> "Thái Lan";
            case "Global / regional" -> "Toàn cầu / khu vực";
            default -> geography;
        };
    }

    public static final String MACRO_ECONOMIC = "MACRO_ECONOMIC";
    public static final String COMPETITIVE_THEME = "COMPETITIVE_THEME";
    public static final String SCHEDULED_EVENT = "SCHEDULED_EVENT";
    public static final String COMPANY_EVENT = "COMPANY_EVENT";
    public static final String MARKET_SHARE_OR_AWARD = "MARKET_SHARE_OR_AWARD";
    public static final String TECH_AI_SIGNAL = "TECH_AI_SIGNAL";
    public static final String STRATEGIC_COMPARISON = "STRATEGIC_COMPARISON";
}
