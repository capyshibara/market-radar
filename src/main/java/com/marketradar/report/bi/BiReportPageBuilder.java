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
 * không hardcode số trang cố định ("shrink page's content rather than lowering the bar to fill
 * space" — đúng nguyên tắc materiality trong content-plan guidance).
 *
 * Mỗi bucket-page dùng lại ĐÚNG fragment exhibit (fragments/product-report-exhibit.html) — đây
 * chính là "Meridian Review + Exhibit Studio" thật đang chạy trong hệ thống.
 *
 * 7 bucket BiFinding ánh xạ vào các nhóm trang theo layout-guidance:
 *  MACRO_ECONOMIC        -> MACRO (KPI)
 *  COMPETITIVE_THEME     -> THEME (MATRIX) + synthesis callout
 *  SCHEDULED_EVENT       -> PRESS_CALENDAR (TIMELINE)
 *  COMPANY_EVENT         -> EVENTS_TIMELINE (TIMELINE, toàn bộ công ty) VÀ, khi 1 subjectKey có
 *                           đủ material (>=2 finding), 1 trang COMPANY_HIGHLIGHT riêng cho subjectKey đó
 *  MARKET_SHARE_OR_AWARD -> AWARDS_MARKET_SHARE (BAR nếu có metricPercent thật cho MỌI finding,
 *                           nếu không thì tự hạ về MATRIX thay vì bịa % để vẽ bar)
 *  TECH_AI_SIGNAL        -> tách theo severity: null -> AI_SIZING (KPI); có giá trị -> AI_THREATMAP (THREATMAP)
 *  STRATEGIC_COMPARISON  -> COMPARISON (MATRIX 2 cột), 1 trang / cặp subjectKey
 */
public final class BiReportPageBuilder {

    private static final int MIN_HIGHLIGHT_GROUP_SIZE = 2;

    private BiReportPageBuilder() {}

    public static Map<String, Object> toTemplateModel(BiReportContent content, boolean vi) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("vi", vi);
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
        List<BiFinding> pressCalendar = byBucket.getOrDefault(BiFinding.SCHEDULED_EVENT, List.of());
        List<BiFinding> companyEvents = byBucket.getOrDefault(BiFinding.COMPANY_EVENT, List.of());
        List<BiFinding> marketShare = byBucket.getOrDefault(BiFinding.MARKET_SHARE_OR_AWARD, List.of());
        List<BiFinding> techAll = byBucket.getOrDefault(BiFinding.TECH_AI_SIGNAL, List.of());
        List<BiFinding> comparison = byBucket.getOrDefault(BiFinding.STRATEGIC_COMPARISON, List.of());

        List<BiFinding> aiSizing = techAll.stream().filter(f -> f.severity() == null).toList();
        List<BiFinding> aiThreat = techAll.stream().filter(f -> f.severity() != null).toList();

        int[] exhibitNo = {1};
        if (!macro.isEmpty()) {
            model.put("macroExhibit", exhibit(macro, "KPI",
                    vi ? "Chỉ báo vĩ mô liên quan" : "Related macro indicators", exhibitNo[0]++, vi));
        }
        if (!theme.isEmpty()) {
            model.put("themeExhibit", exhibit(theme, "MATRIX",
                    vi ? "Xu hướng lặp lại giữa nhiều đối thủ" : "Patterns spanning multiple competitors", exhibitNo[0]++, vi));
            model.put("themeSynthesis", theme.stream().filter(BiFinding::highlight).findFirst()
                    .or(() -> theme.stream().findFirst()).map(f -> f.text(vi)).orElse(null));
        }
        if (!pressCalendar.isEmpty()) {
            model.put("pressCalendarExhibit", exhibit(pressCalendar, "TIMELINE",
                    vi ? "Lịch công bố / sự kiện sắp tới" : "Press / event calendar", exhibitNo[0]++, vi));
        }
        if (!companyEvents.isEmpty()) {
            model.put("eventsTimelineExhibit", exhibit(companyEvents, "TIMELINE",
                    vi ? "Sự kiện theo mốc thời gian" : "Events by date", exhibitNo[0]++, vi));
        }
        boolean marketShareHasMetrics = !marketShare.isEmpty()
                && marketShare.stream().allMatch(f -> f.metricPercent() != null);
        if (!marketShare.isEmpty()) {
            model.put("marketShareExhibit", marketShareHasMetrics
                    ? exhibitBar(marketShare, vi ? "So sánh số liệu giữa các công ty" : "Comparison across companies", exhibitNo[0]++, vi)
                    : exhibit(marketShare, "MATRIX", vi ? "So sánh số liệu giữa các công ty" : "Comparison across companies", exhibitNo[0]++, vi));
        }
        if (!aiSizing.isEmpty()) {
            model.put("aiSizingExhibit", exhibit(aiSizing, "KPI",
                    vi ? "Định cỡ thị trường AI / Insurtech" : "AI / insurtech market sizing", exhibitNo[0]++, vi));
        }
        if (!aiThreat.isEmpty()) {
            model.put("aiThreatExhibit", exhibitThreatMap(aiThreat, vi ? "Bản đồ rủi ro AI theo công ty" : "AI threat map by company", exhibitNo[0]++));
        }

        Map<String, List<BiFinding>> highlightGroups = companyEvents.stream()
                .filter(f -> f.subjectKey() != null && !f.subjectKey().isBlank())
                .collect(Collectors.groupingBy(BiFinding::subjectKey, LinkedHashMap::new, Collectors.toList()));
        highlightGroups.values().removeIf(list -> list.size() < MIN_HIGHLIGHT_GROUP_SIZE);
        model.put("highlightGroups", highlightGroups);

        Map<String, List<BiFinding>> comparisonPages = comparison.stream()
                .collect(Collectors.groupingBy(
                        f -> f.subjectKey() == null || f.subjectKey().isBlank()
                                ? (vi ? "Không rõ cặp so sánh" : "Unspecified comparison pair") : f.subjectKey(),
                        LinkedHashMap::new, Collectors.toList()));
        model.put("comparisonPages", comparisonPages);

        model.put("hasAnyContent", !all.isEmpty());
        model.put("bucketsCovered", byBucket.size());
        model.put("findingsTotal", all.size());

        List<BiCitation> allCitations = all.stream().flatMap(f -> f.citations().stream())
                .collect(Collectors.toCollection(() -> new java.util.TreeSet<>(
                        java.util.Comparator.comparing(c -> c.label() + "|" + c.url()))))
                .stream().toList();
        List<BiCitation> tierPrimary = allCitations.stream()
                .filter(c -> isPrimaryTier(c.tierNote())).toList();
        List<BiCitation> tierSecondary = allCitations.stream()
                .filter(c -> !isPrimaryTier(c.tierNote())).toList();
        model.put("sourcesPrimary", tierPrimary);
        model.put("sourcesSecondary", tierSecondary);
        model.put("sourcesTotal", allCitations.size());

        model.put("pages", plan(vi, macro, theme, pressCalendar, companyEvents, marketShare,
                aiSizing, aiThreat, highlightGroups, comparisonPages));
        return model;
    }

    /** T1/T2 (registry-verified tier or a named established publisher) read as primary;
     *  everything else (T3/T4, "Tìm kiếm mở"/"Render trình duyệt" acquisition notes, unverified
     *  flags) reads as secondary/desk research — matches the report's own honesty check on
     *  how much it leans on primary vs. desk-research evidence. */
    private static boolean isPrimaryTier(String tierNote) {
        if (tierNote == null) return false;
        String t = tierNote.strip().toUpperCase(java.util.Locale.ROOT);
        return t.equals("T1") || t.equals("T2");
    }

    private static EditorialExhibit exhibit(List<BiFinding> findings, String type, String title, int number, boolean vi) {
        List<ExhibitDatum> data = new ArrayList<>();
        LinkedHashSet<String> citationLabels = new LinkedHashSet<>();
        for (BiFinding f : findings) {
            String subject = f.subjectKey() == null || f.subjectKey().isBlank() ? "—" : f.subjectKey();
            // label = tiêu đề ngắn (chỗ hiển thị nổi bật), detail = văn bản ĐẦY ĐỦ không cắt.
            data.add(new ExhibitDatum(shortLabel(f.text(vi)), subject, "", f.text(vi), 0, "BLUE"));
            f.citations().forEach(c -> citationLabels.add(c.label()));
        }
        String note = citationLabels.isEmpty()
                ? (vi ? "Chưa có nguồn trích dẫn cho mục này." : "No cited source for this item yet.")
                : (vi ? "Nguồn: " : "Source: ") + String.join("; ", citationLabels);
        return new EditorialExhibit(String.format("%02d", number), type, true, title,
                findings.get(0).text(vi), note, "", data);
    }

    /** BAR needs a real 0-100 width — only called when every finding in the bucket carries a
     *  stated metricPercent (see marketShareHasMetrics above); never synthesises a percentage. */
    private static EditorialExhibit exhibitBar(List<BiFinding> findings, String title, int number, boolean vi) {
        List<ExhibitDatum> data = new ArrayList<>();
        LinkedHashSet<String> citationLabels = new LinkedHashSet<>();
        for (BiFinding f : findings) {
            String subject = f.subjectKey() == null || f.subjectKey().isBlank() ? "—" : f.subjectKey();
            data.add(new ExhibitDatum(subject, f.metricPercent() + "%", "", f.text(vi), f.metricPercent(), "BLUE"));
            f.citations().forEach(c -> citationLabels.add(c.label()));
        }
        String note = citationLabels.isEmpty()
                ? (vi ? "Chưa có nguồn trích dẫn cho mục này." : "No cited source for this item yet.")
                : (vi ? "Nguồn: " : "Source: ") + String.join("; ", citationLabels);
        return new EditorialExhibit(String.format("%02d", number), "BAR", true, title,
                findings.get(0).text(vi), note, "", data);
    }

    private static EditorialExhibit exhibitThreatMap(List<BiFinding> findings, String title, int number) {
        List<ExhibitDatum> data = new ArrayList<>();
        LinkedHashSet<String> citationLabels = new LinkedHashSet<>();
        for (BiFinding f : findings) {
            String company = f.subjectKey() == null || f.subjectKey().isBlank() ? "—" : f.subjectKey();
            // value = nhãn mức rủi ro (đúng chuỗi severity, hiển thị trực tiếp trong .threat-badge)
            data.add(new ExhibitDatum(company, f.severity(), f.textVi(), f.textVi(), 0, f.severity()));
            f.citations().forEach(c -> citationLabels.add(c.label()));
        }
        String note = citationLabels.isEmpty() ? "No cited source for this item yet."
                : "Source: " + String.join("; ", citationLabels);
        return new EditorialExhibit(String.format("%02d", number), "THREATMAP", true, title,
                findings.get(0).textVi(), note, "", data);
    }

    /** exhibit-matrix/timeline/kpi coi "value"/"label" là ô ngắn — dữ liệu thật là câu văn dài,
     * nên câu đầy đủ luôn nằm ở "detail"; đây chỉ cắt ngắn để không tràn ô tiêu đề. */
    private static String shortLabel(String text) {
        if (text == null) return "";
        String t = text.strip();
        return t.length() <= 90 ? t : t.substring(0, 90) + "…";
    }

    private static List<BiPage> plan(boolean vi, List<BiFinding> macro, List<BiFinding> theme,
                                     List<BiFinding> pressCalendar, List<BiFinding> companyEvents,
                                     List<BiFinding> marketShare, List<BiFinding> aiSizing, List<BiFinding> aiThreat,
                                     Map<String, List<BiFinding>> highlightGroups, Map<String, List<BiFinding>> comparisonPages) {
        List<BiPage> pages = new ArrayList<>();
        int n = 1;
        pages.add(new BiPage(n++, "COVER", vi ? "Bìa" : "Cover", null));
        pages.add(new BiPage(n++, "TOC", vi ? "Mục lục" : "Contents", null));
        pages.add(new BiPage(n++, "EXEC", vi ? "Tóm tắt điều hành" : "Executive summary", null));
        if (!macro.isEmpty()) pages.add(new BiPage(n++, "MACRO", vi ? "Vĩ mô ngành" : "Macro update", null));
        if (!theme.isEmpty()) pages.add(new BiPage(n++, "THEME", vi ? "Xu hướng cạnh tranh" : "Competitive themes", null));
        if (!pressCalendar.isEmpty()) pages.add(new BiPage(n++, "PRESS_CALENDAR", vi ? "Lịch công bố" : "Press calendar", null));
        if (!companyEvents.isEmpty()) pages.add(new BiPage(n++, "EVENTS_TIMELINE", vi ? "Diễn biến theo mốc thời gian" : "Events timeline", null));
        if (!marketShare.isEmpty()) pages.add(new BiPage(n++, "AWARDS_MARKET_SHARE", vi ? "Thị phần / Giải thưởng" : "Awards / market share", null));
        if (!aiSizing.isEmpty()) pages.add(new BiPage(n++, "AI_SIZING", vi ? "Định cỡ thị trường AI/Insurtech" : "AI / insurtech sizing", null));
        if (!aiThreat.isEmpty()) pages.add(new BiPage(n++, "AI_THREATMAP", vi ? "Bản đồ rủi ro AI" : "AI threat map", null));
        for (String key : highlightGroups.keySet()) {
            pages.add(new BiPage(n++, "COMPANY_HIGHLIGHT", key, key));
        }
        for (String key : comparisonPages.keySet()) {
            pages.add(new BiPage(n++, "COMPARISON", key, key));
        }
        pages.add(new BiPage(n++, "SOURCES", vi ? "Nguồn & Phương pháp" : "Sources & method", null));
        pages.add(new BiPage(n, "BACK", vi ? "Trang cuối" : "Back cover", null));
        return pages;
    }
}
