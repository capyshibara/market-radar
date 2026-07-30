package com.marketradar.report.bi;

/**
 * Một "trang" trong BI report. Java build DANH SÁCH này TRƯỚC khi render (Plan), rồi template
 * chỉ lặp qua và chọn fragment theo type (Render) — nhờ vậy số trang/TOC luôn khớp số trang
 * THẬT SỰ in ra (không hardcode số trang cố định); bucket nào không có finding thì không có
 * BiPage nào cho nó, tự động không chiếm trang.
 *
 * subjectKey: dùng cho type lặp theo chủ thể (STRATEGIC_COMPARISON) — null cho type chỉ có
 * đúng 1 trang.
 */
public record BiPage(int number, String type, String label, String subjectKey) {}
