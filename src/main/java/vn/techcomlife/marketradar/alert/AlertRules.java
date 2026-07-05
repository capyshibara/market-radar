package vn.techcomlife.marketradar.alert;

/**
 * Logic THUẦN của Hot Alert (Batch 5, bước 8 sequence) — ZERO import ngoài JDK,
 * cùng pattern ReviewRules: (1) Batch5LogicTest.java compile TRỰC TIẾP file này,
 * (2) tách rule khỏi plumbing HTTP/Slack.
 *
 * Nguyên tắc KHÔNG nhượng bộ: alert chỉ bắn khi claim đã *_APPROVED —
 * "sốt dẻo" không được phép đi tắt qua gate (giữ nguyên vòng đời Batch 4).
 */
public final class AlertRules {

    private AlertRules() {}

    /** Tier T0..T4 so sánh được bằng chuỗi (cùng trick sort queue của Batch 4). */
    public static boolean isHotTier(String tier, String minTier) {
        if (tier == null || minTier == null) return false;
        return tier.compareTo(minTier) >= 0;
    }

    /** Chỉ 4 trạng thái xuất bản được của Batch 4 mới đủ điều kiện alert. */
    public static boolean isPublishableStatus(String reviewStatusName) {
        if (reviewStatusName == null) return false;
        return switch (reviewStatusName) {
            case "AUTO_APPROVED", "APPROVED", "EDITED_APPROVED", "FORCE_APPROVED" -> true;
            default -> false;
        };
    }

    /**
     * Quyết định bắn alert. Cả HAI điều kiện đều bắt buộc:
     * tier đủ nóng (>= minTier, mặc định T3) VÀ claim đã qua vòng đời duyệt.
     * Lưu ý trung thực: với rule tier hiện tại (T3+ luôn cần người duyệt),
     * alert trên thực tế chỉ phát sinh từ hành động của REVIEWER — đường
     * AUTO_APPROVED vẫn được hook để rule tier tương lai không phải sửa chỗ gọi.
     */
    public static boolean shouldAlert(String tier, String reviewStatusName, String minTier) {
        return isHotTier(tier, minTier) && isPublishableStatus(reviewStatusName);
    }

    /**
     * Template alert ngắn theo spec handoff: fact + nguồn + "vì sao" + hành động.
     * Text THUẦN (Slack {"text": ...}) — không markdown phức tạp để còn tái dùng cho email.
     *
     * @param claimCode  mã claim (C-00x)
     * @param tier       risk tier
     * @param statusName trạng thái duyệt lúc bắn (audit ngay trong alert)
     * @param claimText  câu diễn giải đã duyệt — chính là phần "vì sao đáng chú ý"
     * @param factLine   fact gốc tóm tắt (summaryVi + mã fact); null nếu không resolve được
     * @param sourceLine "Tên nguồn (tier N) — url"; null với EXEC_SUMMARY
     * @param actionUrl  link hành động (report / review)
     */
    public static String buildAlertText(String claimCode, String tier, String statusName,
                                        String claimText, String factLine,
                                        String sourceLine, String actionUrl) {
        StringBuilder sb = new StringBuilder();
        sb.append("🔥 [HOT ").append(nvl(tier, "T?")).append("] Market Radar — tin nóng đã duyệt (")
          .append(nvl(claimCode, "?")).append(" · ").append(nvl(statusName, "?")).append(")\n");
        sb.append("• Vì sao đáng chú ý: ").append(nvl(claimText, "(trống)")).append('\n');
        if (factLine != null && !factLine.isBlank())
            sb.append("• Fact gốc: ").append(factLine.strip()).append('\n');
        if (sourceLine != null && !sourceLine.isBlank())
            sb.append("• Nguồn: ").append(sourceLine.strip()).append('\n');
        sb.append("• Hành động: ").append(nvl(actionUrl, "/report/weekly"));
        return sb.toString();
    }

    /** Escape tối thiểu đủ cho JSON string body {"text": "..."} gửi Slack. */
    public static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    /**
     * Webhook URL hợp lệ = https tuyệt đối. Trả NULL nếu ok, ngược lại là
     * thông báo lỗi tiếng Việt (fail loud — cùng convention ReviewRules).
     * Rỗng/null KHÔNG phải lỗi — nghĩa là chạy chế độ STUB (chỉ log).
     */
    public static String validateWebhookUrl(String url) {
        if (url == null || url.isBlank()) return null; // stub mode hợp lệ
        if (!url.strip().startsWith("https://"))
            return "Webhook URL phải là https:// (đang: " + url.strip() + ").";
        return null;
    }

    private static String nvl(String s, String def) {
        return (s == null || s.isBlank()) ? def : s.strip();
    }
}
