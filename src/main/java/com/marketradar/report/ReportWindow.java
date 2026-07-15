package com.marketradar.report;

import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.RawDoc;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Batch 9 (feedback Hanh): mỗi report chỉ hiện tin ĐÚNG khung thời gian của nó —
 * weekly = 7 ngày gần nhất, monthly = 30 ngày gần nhất.
 *
 * Fix 2026-07-14 (feedback Hanh: "tất cả tin đều CŨ"): khung thời gian CHỈ được
 * quyết bằng ngày THẬT của tin — eventDate (ngày sự kiện trong tài liệu) hoặc,
 * nếu không có, publishedAt (ngày nguồn công bố). TUYỆT ĐỐI không rơi về fetchedAt
 * (thời điểm crawl) nữa: fetchedAt luôn ~ "hôm nay", nên tin 2024 không rõ ngày
 * vẫn lọt qua bộ lọc "7 ngày" — chính là lỗi khiến mọi tin cũ hiện lên như tin mới
 * (xác nhận trên DB: 17/17 fact trong report tuần lọt qua nhờ fallback fetchedAt,
 * 0 nhờ ngày thật). Tin KHÔNG có ngày thật nào → coi như KHÔNG kiểm chứng được độ
 * mới → loại khỏi report có khung thời gian (fail closed, không đoán là "mới").
 *
 * Fix 2026-07-14 (monthly handoff item #4): monthlyStart used to be
 * today.withDayOfMonth(1) — "calendar month-to-date" — which on any run early
 * in the month (e.g. day 14) only gives a 14-day window, so most content from
 * a one-shot backlog ingest falls outside it before it even reaches the
 * per-section .limit(6). Switched to a rolling 30-day window (matches the
 * weekly pattern of "last N days" instead of "current calendar period"),
 * which is what a demo run over a backfilled corpus actually needs.
 */
public final class ReportWindow {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private ReportWindow() {}

    public static LocalDate weeklyStart(LocalDate today) { return today.minusDays(6); }

    // 2026-07-15 (quyết định với Hanh): 3 nhịp report — weekly (7d) / monthly (30d) /
    // quarterly (90d). Quarterly là bản "dày" (cửa sổ rộng → nội dung phong phú), monthly
    // là bản "hiện tại" (cửa sổ hẹp). Xếp hạng theo độ mới + độ tác động trong controller.
    public static LocalDate monthlyStart(LocalDate today) { return today.minusDays(29); }

    public static LocalDate quarterlyStart(LocalDate today) { return today.minusDays(89); }

    /** Cửa sổ tổng hợp NARRATIVE (2026-07-15): rộng hơn cửa sổ hiển thị (12 tháng) để các
     * chương phân tích đủ DÀY — bắt được động thái thực chất của đối thủ (ra mắt SP, hợp tác
     * phân phối) vốn có thể cũ hơn 90 ngày. Exec summary/exhibit vẫn theo cửa sổ nhịp báo cáo. */
    public static LocalDate narrativeStart(LocalDate today) { return today.minusDays(364); }

    public static boolean factInWindow(EvidenceFact f, LocalDate start, LocalDate today) {
        LocalDate d = factDisplayDate(f);
        return d != null && !d.isBefore(start) && !d.isAfter(today);
    }

    public static boolean docInWindow(RawDoc doc, LocalDate start, LocalDate today) {
        LocalDate d = publishedDate(doc);
        return d != null && !d.isBefore(start) && !d.isAfter(today);
    }

    /**
     * Ngày THẬT của một fact để lọc VÀ để hiển thị: eventDate nếu có, không thì
     * publishedAt của doc. KHÔNG dùng fetchedAt — xem lý do ở javadoc lớp.
     * null = không rõ ngày → gọi bên lọc sẽ loại; template hiện "—".
     */
    public static LocalDate factDisplayDate(EvidenceFact f) {
        if (f == null) return null;
        if (f.getEventDate() != null) return f.getEventDate();
        return publishedDate(f.getRawDoc());
    }

    /** Ngày công bố của doc (publishedAt) hoặc null — KHÔNG fallback fetchedAt. */
    public static LocalDate publishedDate(RawDoc doc) {
        if (doc == null || doc.getPublishedAt() == null) return null;
        return doc.getPublishedAt().atZone(ZONE).toLocalDate();
    }
}
