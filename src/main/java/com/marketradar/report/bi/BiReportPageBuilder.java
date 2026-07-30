package com.marketradar.report.bi;

import com.marketradar.report.ProductReportEditorialService.EditorialExhibit;
import com.marketradar.report.ProductReportEditorialService.ExhibitDatum;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Chuyển BiReportContent (dữ liệu thuần) thành model Thymeleaf + danh sách trang thật sự sẽ in.
 * Java quyết định TRƯỚC bucket nào có finding thì mới có trang cho bucket đó (Plan) — template
 * chỉ lặp qua và chọn fragment theo type (Render). Nhờ vậy số trang/TOC luôn khớp nội dung thật,
 * không hardcode số trang cố định — đúng yêu cầu "linh hoạt theo dữ liệu" đã chốt.
 *
 * Mỗi bucket-page dùng lại ĐÚNG fragment exhibit (fragments/product-report-exhibit.html) và CSS
 * đã có sẵn cho report tuần/tháng (EditorialExhibit/ExhibitDatum) — đây chính là "Meridian Review
 * design system" thật đang chạy trong hệ thống, thay vì tự dựng bộ khung riêng cho BI report.
 */
public final class BiReportPageBuilder {

    private BiReportPageBuilder() {}

    public static Map<String, Object> toTemplateModel(BiReportContent content) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("reportTitle", content.title());
        model.put("reportPeriod", content.period());
        model.put("homeCompany", content.homeCompany());
        model.put("generatedAt", content.generatedAt());
        model.put("docCount", content.docCount());
        model.put("openGaps", content.openGaps());
        model.put("sourceLines", content.sourceLines());

        List<BiFinding> all = content.findings();
        List<BiFinding> keyFindings = all.stream().filter(BiFinding::highlight).limit(3).toList();
        if (keyFindings.isEmpty() && !all.isEmpty()) {
            keyFindings = all.stream().limit(3).toList();
        }
        model.put("keyFindings", keyFindings);

        Map<String, List<BiFinding>> byBucket = all.stream()
                .collect(Collectors.groupingBy(BiFinding::bucket, LinkedHashMap::new, Collectors.toList()));

        List<BiFinding> macro = byBucket.getOrDefault(BiFinding.MACRO_ECONOMIC, List.of());
        List<BiFinding> theme = byBucket.getOrDefault(BiFinding.COMPETITIVE_THEME, List.of());
        List<BiFinding> scheduled = byBucket.getOrDefault(BiFinding.SCHEDULED_EVENT, List.of());
        List<BiFinding> companyEvent = byBucket.getOrDefault(BiFinding.COMPANY_EVENT, List.of());
        List<BiFinding> marketShare = byBucket.getOrDefault(BiFinding.MARKET_SHARE_OR_AWARD, List.of());
        List<BiFinding> techSignal = byBucket.getOrDefault(BiFinding.TECH_AI_SIGNAL, List.of());
        List<BiFinding> comparison = byBucket.getOrDefault(BiFinding.STRATEGIC_COMPARISON, List.of());

        model.put("macroFindings", macro);

        int[] exhibitNo = {1};
        if (!theme.isEmpty()) model.put("themeExhibit", exhibit(theme, "MATRIX", "Xu hướng cạnh tranh", exhibitNo[0]++));
        if (!scheduled.isEmpty()) model.put("scheduledExhibit", exhibit(scheduled, "TIMELINE", "Lịch sắp tới", exhibitNo[0]++));
        if (!companyEvent.isEmpty()) model.put("companyEventExhibit", exhibit(companyEvent, "TIMELINE", "Diễn biến theo công ty", exhibitNo[0]++));
        if (!marketShare.isEmpty()) model.put("marketShareExhibit", exhibit(marketShare, "MATRIX", "Thị phần / Giải thưởng", exhibitNo[0]++));
        if (!techSignal.isEmpty()) model.put("techSignalExhibit", exhibit(techSignal, "KPI", "Tín hiệu Tech/AI", exhibitNo[0]++));

        Map<String, List<BiFinding>> comparisonPages = comparison.stream()
                .collect(Collectors.groupingBy(
                        f -> f.subjectKey() == null || f.subjectKey().isBlank() ? "Không rõ cặp so sánh" : f.subjectKey(),
                        LinkedHashMap::new, Collectors.toList()));
        model.put("comparisonPages", comparisonPages);

        model.put("hasAnyContent", !all.isEmpty());
        model.put("bucketsCovered", byBucket.size());
        model.put("findingsTotal", all.size());
        model.put("pages", plan(macro, theme, scheduled, companyEvent, marketShare, techSignal, comparisonPages));
        return model;
    }

    private static EditorialExhibit exhibit(List<BiFinding> findings, String type, String title, int number) {
        List<ExhibitDatum> data = new ArrayList<>();
        LinkedHashSet<String> citationLabels = new LinkedHashSet<>();
        for (BiFinding f : findings) {
            String subject = f.subjectKey() == null || f.subjectKey().isBlank() ? "—" : f.subjectKey();
            // label = tiêu đề ngắn (chỗ hiển thị nổi bật), detail = văn bản ĐẦY ĐỦ không cắt —
            // exhibit MATRIX/TIMELINE/KPI đều có 1 ô "đọc quyết định"/paragraph riêng cho việc này.
            data.add(new ExhibitDatum(shortLabel(f.textVi()), subject, "", f.textVi(), 0, "BLUE"));
            f.citations().forEach(c -> citationLabels.add(c.label()));
        }
        String note = citationLabels.isEmpty() ? "Chưa có nguồn trích dẫn cho mục này."
                : "Nguồn: " + String.join("; ", citationLabels);
        return new EditorialExhibit(String.format("%02d", number), type, true, title,
                findings.get(0).textVi(), note, "", data);
    }

    /** exhibit-matrix/timeline/kpi coi "value"/"label" là ô ngắn — dữ liệu thật là câu văn dài,
     * nên câu đầy đủ luôn nằm ở "detail"; đây chỉ cắt ngắn để không tràn ô tiêu đề. */
    private static String shortLabel(String textVi) {
        if (textVi == null) return "";
        String t = textVi.strip();
        return t.length() <= 90 ? t : t.substring(0, 90) + "…";
    }

    private static List<BiPage> plan(List<BiFinding> macro, List<BiFinding> theme, List<BiFinding> scheduled,
                                     List<BiFinding> companyEvent, List<BiFinding> marketShare,
                                     List<BiFinding> techSignal, Map<String, List<BiFinding>> comparisonPages) {
        List<BiPage> pages = new ArrayList<>();
        int n = 1;
        pages.add(new BiPage(n++, "COVER", "Bìa", null));
        pages.add(new BiPage(n++, "TOC", "Mục lục", null));
        pages.add(new BiPage(n++, "EXEC", "Tóm tắt điều hành", null));
        if (!macro.isEmpty()) pages.add(new BiPage(n++, "MACRO", "Vĩ mô ngành", null));
        if (!theme.isEmpty()) pages.add(new BiPage(n++, "THEME", "Xu hướng cạnh tranh", null));
        if (!scheduled.isEmpty()) pages.add(new BiPage(n++, "SCHEDULED", "Lịch sắp tới", null));
        if (!companyEvent.isEmpty()) pages.add(new BiPage(n++, "COMPANY_TIMELINE", "Diễn biến theo công ty", null));
        if (!marketShare.isEmpty()) pages.add(new BiPage(n++, "MARKET_SHARE", "Thị phần / Giải thưởng", null));
        if (!techSignal.isEmpty()) pages.add(new BiPage(n++, "THREATMAP", "Tín hiệu Tech/AI", null));
        for (String key : comparisonPages.keySet()) {
            pages.add(new BiPage(n++, "COMPARISON", key, key));
        }
        pages.add(new BiPage(n++, "SOURCES", "Nguồn & Phương pháp", null));
        pages.add(new BiPage(n, "BACK", "Trang cuối", null));
        return pages;
    }
}
