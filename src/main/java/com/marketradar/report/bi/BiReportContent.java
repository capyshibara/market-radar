package com.marketradar.report.bi;

import java.util.List;

/**
 * Toàn bộ nội dung đã sẵn sàng để build 1 BI report — nguồn gốc dữ liệu (report định kỳ hay
 * Deep Research) đã được giải quyết trước khi tới đây; BiReportPageBuilder/template/docx đều
 * chỉ cần record này.
 *
 * @param findings   TẤT CẢ finding của report này (kể cả highlight=true) — BiReportPageBuilder
 *                   tự nhóm theo bucket, không cần truyền keyFindings riêng
 * @param openGaps   khai báo rõ mục nào KHÔNG đủ dữ liệu, thay vì âm thầm bỏ qua (giữ nguyên
 *                   tinh thần minh bạch của report tuần/tháng hiện có)
 */
public record BiReportContent(
        String title,
        String period,
        String homeCompany,
        String generatedAt,
        long docCount,
        List<BiFinding> findings,
        List<String> sourceLines,
        List<String> openGaps) {

    public BiReportContent {
        findings = findings == null ? List.of() : List.copyOf(findings);
        sourceLines = sourceLines == null ? List.of() : List.copyOf(sourceLines);
        openGaps = openGaps == null ? List.of() : List.copyOf(openGaps);
    }
}
