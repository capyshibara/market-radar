package com.marketradar.report;

import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.RawDoc;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Batch 9 (feedback Hanh): mỗi report chỉ hiện tin ĐÚNG khung thời gian của nó —
 * weekly = 7 ngày gần nhất, monthly = tháng dương lịch hiện tại.
 *
 * Ngày của một fact = eventDate (ngày sự kiện ghi trong tài liệu) nếu có;
 * không có thì rơi về ngày của doc = publishedAt ?: fetchedAt. Fact không ngày
 * sự kiện từ feed archive cũ vì thế không lọt vào report hiện tại.
 */
public final class ReportWindow {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private ReportWindow() {}

    public static LocalDate weeklyStart(LocalDate today) { return today.minusDays(6); }

    public static LocalDate monthlyStart(LocalDate today) { return today.withDayOfMonth(1); }

    public static boolean factInWindow(EvidenceFact f, LocalDate start, LocalDate today) {
        LocalDate d = f.getEventDate() != null ? f.getEventDate() : docDate(f.getRawDoc());
        return d != null && !d.isBefore(start) && !d.isAfter(today);
    }

    public static boolean docInWindow(RawDoc doc, LocalDate start, LocalDate today) {
        LocalDate d = docDate(doc);
        return d != null && !d.isBefore(start) && !d.isAfter(today);
    }

    public static LocalDate docDate(RawDoc doc) {
        if (doc == null) return null;
        Instant t = doc.getPublishedAt() != null ? doc.getPublishedAt() : doc.getFetchedAt();
        return t == null ? null : t.atZone(ZONE).toLocalDate();
    }
}
