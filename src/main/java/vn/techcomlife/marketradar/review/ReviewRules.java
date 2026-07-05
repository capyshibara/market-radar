package vn.techcomlife.marketradar.review;

/**
 * Logic THUẦN của luồng review — ZERO import ngoài JDK, để:
 *  (1) test được trên JRE trần không cần Spring/Maven (Batch4LogicTest.java
 *      compile TRỰC TIẾP file này — test đúng code chạy thật, không phải bản port);
 *  (2) tách bạch rule khỏi plumbing web.
 *
 * Mọi hàm validate trả về NULL nếu hợp lệ, ngược lại trả THÔNG BÁO LỖI tiếng Việt
 * (fail loud — lỗi luôn có lý do đọc được).
 */
public final class ReviewRules {

    private ReviewRules() {}

    public static final int MIN_FORCE_REASON = 10;
    public static final int MIN_REJECT_REASON = 5;

    // ---------- Tier (PLACEHOLDER — Impact Scorer thật ở bước 9 thay thế) ----------

    /**
     * @param isDemoInject  claim demo → luôn vào review
     * @param isExecSummary câu cấp report (rawDoc null) → consequence cao
     * @param sourceTier    tier NGUỒN (1=chính phủ/regulator … 4=blog)
     * @return risk tier T1/T3 (rule tối thiểu — xem RiskTierRouter javadoc)
     */
    public static String assignTier(boolean isDemoInject, boolean isExecSummary, int sourceTier) {
        if (isDemoInject) return "T3";
        if (isExecSummary) return "T3";
        // Hard-override kiến trúc đầy đủ: văn bản pháp lý chính thức → tối thiểu T3
        return sourceTier == 1 ? "T3" : "T1";
    }

    /** T0/T1 = diện sample theo E1 (ENTAILED → tự xuất bản); T2+ = bắt buộc người. */
    public static boolean requiresHumanReview(String tier) {
        return !("T0".equals(tier) || "T1".equals(tier));
    }

    // ---------- Quyết định route sau Gate L2 ----------

    /**
     * @param verdictName tên verdict Gate L2 (ENTAILED/CONTRADICTED/NEUTRAL/VERIFIER_ERROR)
     * @return true = tự xuất bản (AUTO_APPROVED); false = vào review.
     *         CHỈ ENTAILED + tier diện sample mới auto — mọi verdict khác,
     *         kể cả VERIFIER_ERROR, đều route review (không bao giờ quy lỗi về pass).
     */
    public static boolean autoPublishable(String verdictName, String tier) {
        return "ENTAILED".equals(verdictName) && !requiresHumanReview(tier);
    }

    // ---------- Precondition từng hành động review ----------

    public static String validateApprove(boolean evidenceViewed) {
        if (!evidenceViewed) return "Từ chối approve: chưa mở evidence (chống rubber-stamp).";
        return null;
    }

    public static String validateEdit(boolean evidenceViewed, String newText) {
        if (!evidenceViewed) return "Từ chối edit-approve: chưa mở evidence (chống rubber-stamp).";
        if (newText == null || newText.isBlank()) return "Text sửa không được rỗng.";
        return null;
    }

    public static String validateForceApprove(boolean evidenceViewed, String reason,
                                              String factCodesCsv) {
        if (!evidenceViewed) return "Từ chối force-approve: chưa mở evidence (chống rubber-stamp).";
        if (reason == null || reason.strip().length() < MIN_FORCE_REASON)
            return "Force-approve bắt buộc lý do (tối thiểu " + MIN_FORCE_REASON
                    + " ký tự) — override có giá.";
        if (factCodesCsv == null || factCodesCsv.isBlank())
            return "Không thể force-approve claim KHÔNG có citation nào "
                    + "(Invariant #1: zero claim không nguồn — không nhượng bộ).";
        return null;
    }

    public static String validateReject(String reason) {
        if (reason == null || reason.strip().length() < MIN_REJECT_REASON)
            return "Reject bắt buộc lý do (tối thiểu " + MIN_REJECT_REASON
                    + " ký tự) — lý do chính là nhãn.";
        return null;
    }

    // ---------- Chuẩn hoá verdict từ output verifier ----------

    /** Gỡ code-fence + lấy verdict; verdict lạ → VERIFIER_ERROR (không đoán). */
    public static String normalizeVerdict(String rawVerdictField) {
        if (rawVerdictField == null) return "VERIFIER_ERROR";
        String v = rawVerdictField.strip().toUpperCase(java.util.Locale.ROOT);
        return switch (v) {
            case "ENTAILED", "CONTRADICTED", "NEUTRAL" -> v;
            default -> "VERIFIER_ERROR";
        };
    }

    public static String stripCodeFences(String raw) {
        if (raw == null) return "";
        return raw.strip()
                .replaceAll("(?s)^```(?:json)?", "")
                .replaceAll("(?s)```$", "")
                .strip();
    }
}
