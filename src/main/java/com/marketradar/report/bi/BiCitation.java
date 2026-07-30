package com.marketradar.report.bi;

/**
 * Một trích dẫn cho 1 BiFinding — đủ chung để phục vụ cả 2 nguồn:
 * report định kỳ (nguồn đã có Source/tier thật trong registry) và Deep Research
 * (nguồn là URL vừa tìm/đọc được, chưa qua registry/tier nào).
 *
 * @param label     tên hiển thị (tên nguồn đã đăng ký, hoặc domain của URL vừa đọc)
 * @param tierNote  "T1".."T3" nếu có tier thật; ghi chú tự do (vd "chưa kiểm chứng") nếu không
 * @param url       link để bấm mở; null nếu không có (vd trích dẫn nội bộ /claims)
 */
public record BiCitation(String label, String tierNote, String url) {}
