package com.marketradar.report;

/**
 * Phase 4 — một "trang" trong BI report. Java build DANH SÁCH này TRƯỚC khi render (Plan),
 * rồi template chỉ lặp qua và chọn fragment theo type (Render) — nhờ vậy số trang/TOC luôn
 * khớp số trang THẬT SỰ được in (không hardcode 19 trang cố định như mockup gốc); bucket nào
 * không đủ dữ liệu thì không có PageEntry nào cho nó, tự động không chiếm trang.
 *
 * subjectKey: dùng cho type lặp (COMPETITOR/COMPARISON) để biết instance này ứng với công ty/
 * cặp so sánh nào — null cho các type chỉ có đúng 1 trang (COVER/TOC/EXEC/...).
 */
public record BiPage(int number, String type, String label, String subjectKey) {}
