package com.marketradar.review;

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

    // ---------- Claim consequence tier (not source quality) ----------

    /**
     * @param isDemoInject  claim demo → luôn vào review
     * @param isExecSummary câu cấp report (rawDoc null) → consequence cao
     * @param highConsequenceSource true for regulatory/statutory material whose
     *                              misreading can change a management decision
     * @return risk tier T1/T3 (rule tối thiểu — xem RiskTierRouter javadoc)
     */
    public static String assignTier(boolean isDemoInject, boolean isExecSummary,
                                    boolean highConsequenceSource) {
        if (isDemoInject) return "T3";
        if (isExecSummary) return "T3";
        return highConsequenceSource ? "T3" : "T1";
    }

    /** Legacy standalone-test seam; source tier is not used by production routing. */
    @Deprecated(forRemoval = false)
    public static String assignTier(boolean isDemoInject, boolean isExecSummary, int legacySourceTier) {
        return assignTier(isDemoInject, isExecSummary, legacySourceTier == 1);
    }

    // ---------- Quyết định route sau Gate L2 ----------

    /**
     * @param verdictName tên verdict Gate L2 (ENTAILED/CONTRADICTED/NEUTRAL/VERIFIER_ERROR)
     * @return true = tự xuất bản (AUTO_APPROVED); false = vào review.
     *         2026-08-02 (feedback Hanh): bỏ điều kiện risk tier khỏi quyết định này — claim
     *         đã qua Gate L1 (exact-match) rồi tới Gate L2 (verifier độc lập, khác họ model
     *         với writer) là đã qua 2 lớp kiểm nghiêm ngặt; gắn thêm điều kiện "nguồn Tier 1
     *         luôn cần người" phía trên 2 lớp gate đó không còn hợp lý — Verifier ENTAILED là
     *         đủ để tự xuất bản, bất kể risk tier. Risk tier (RiskTierRouter) vẫn được tính và
     *         lưu để hiển thị/sắp ưu tiên trong Reviewer Queue, chỉ không còn ép buộc review nữa.
     *         Mọi verdict khác ENTAILED — kể cả VERIFIER_ERROR — vẫn luôn route review (không
     *         bao giờ quy lỗi kỹ thuật của verifier thành pass).
     */
    public static boolean autoPublishable(String verdictName) {
        return "ENTAILED".equals(verdictName);
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
