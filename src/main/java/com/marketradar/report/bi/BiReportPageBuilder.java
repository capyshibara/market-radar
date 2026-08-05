package com.marketradar.report.bi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Chuyển BiReportContent (dữ liệu thuần) thành model Thymeleaf + danh sách trang thật sự sẽ in.
 * Java quyết định TRƯỚC bucket nào có finding thì mới có trang cho bucket đó (Plan) — template
 * chỉ lặp qua và chọn khối markup theo type (Render). Nhờ vậy số trang/TOC luôn khớp nội dung
 * thật, không hardcode số trang cố định.
 *
 * Template dùng bộ khung Techcomlife Design System (đỏ/trắng, Carlito, xem
 * bi-report.html) — mỗi bucket-page render bằng markup RIÊNG cho type đó (bảng, thẻ, thanh KPI…)
 * thay vì đi qua fragment exhibit chung (MATRIX/KPI/BAR/THREATMAP) như bản Meridian cũ, vì thiết
 * kế mới có layout đặc thù cho từng loại trang mà fragment chung không biểu diễn được.
 *
 * 7 bucket BiFinding ánh xạ vào các nhóm trang:
 *  MACRO_ECONOMIC        -> MACRO (danh sách nhận định, không bịa số KPI vì dữ liệu hiện chưa có
 *                           trường số liệu riêng cho macro)
 *  COMPETITIVE_THEME     -> THEME (bảng: chủ đề [subjectKey] | tín hiệu [text])
 *  SCHEDULED_EVENT       -> PRESS_CALENDAR (bảng: công ty/chủ thể | nội dung) — KHÔNG dựng lưới
 *                           tuần thật vì BiFinding chưa có trường ngày/tuần có cấu trúc
 *  COMPANY_EVENT         -> EVENTS_TIMELINE (bảng, toàn bộ công ty) VÀ, khi 1 subjectKey có đủ
 *                           material (>=2 finding), 1 trang COMPANY_HIGHLIGHT riêng cho subjectKey đó
 *  MARKET_SHARE_OR_AWARD -> AWARDS_MARKET_SHARE (thanh ngang nếu có metricPercent thật cho MỌI
 *                           finding, nếu không thì tự hạ về bảng thay vì bịa % để vẽ thanh)
 *  TECH_AI_SIGNAL        -> tách theo severity: null -> AI_SIZING (danh sách); có giá trị ->
 *                           AI_THREATMAP (bảng với SeverityBadge)
 *  STRATEGIC_COMPARISON  -> COMPARISON (danh sách nhận định), 1 trang / cặp subjectKey
 *  DEEP_DIVE             -> 1 trang / finding (Connector đề xuất chủ thể, Analyst viết narrative
 *                           dài xuyên fact/bucket — xem InterpretationJob#runDeepDiveSynthesis;
 *                           không có subjectKey để nhóm vì bài tự nêu rõ chủ thể trong văn bản)
 */
public final class BiReportPageBuilder {

    /** Optional per-competitor accent (matches published brand colors, not fabricated) —
     *  default #C00000 for any subject not in this small, hand-verified list. */
    private static final Map<String, String> COMPETITOR_ACCENTS = Map.of(
            "chubb", "#041A70",
            "manulife", "#01592F",
            "aia", "#D02148",
            "hanwha", "#EF7423",
            "prudential", "#657076");
    private static final String DEFAULT_ACCENT = "#C00000";

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

        List<BiFinding> all = content.findings();
        List<BiFinding> decisionGrade = all.stream()
                .filter(f -> "DECISION_GRADE".equals(f.evidenceGrade())).toList();
        List<BiFinding> reviewedAnalysis = all.stream()
                .filter(f -> "REVIEWED_ANALYSIS".equals(f.evidenceGrade())).toList();
        List<BiFinding> watch = all.stream()
                .filter(f -> "EDITORIAL_WATCH".equals(f.evidenceGrade())).toList();
        model.put("reviewedAnalysisFindings", reviewedAnalysis);
        model.put("reviewedAnalysisCount", reviewedAnalysis.size());
        model.put("watchFindings", watch);
        model.put("watchCount", watch.size());
        List<BiFinding> keyFindings = decisionGrade.stream().filter(BiFinding::highlight).limit(3).toList();
        if (keyFindings.isEmpty() && !decisionGrade.isEmpty()) {
            keyFindings = decisionGrade.stream().limit(3).toList();
        }
        model.put("keyFindings", keyFindings);

        Map<String, List<BiFinding>> byBucket = decisionGrade.stream()
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

        model.put("macroFindings", macro);
        model.put("themeFindings", theme);
        model.put("pressCalendarFindings", pressCalendar);
        model.put("eventsTimelineFindings", companyEvents);
        model.put("marketShareFindings", marketShare);
        boolean marketShareHasMetrics = !marketShare.isEmpty()
                && marketShare.stream().allMatch(f -> f.metricPercent() != null);
        model.put("marketShareHasMetrics", marketShareHasMetrics);
        model.put("aiSizingFindings", aiSizing);
        model.put("aiThreatFindings", aiThreat);

        // 2026-08-03: gộp qua Connector (dùng chung, test độc lập được) thay vì tự groupingBy ẩn
        // ở đây — cùng logic (gộp theo subjectKey trong 1 bucket, cần >= MIN_HIGHLIGHT_GROUP_SIZE
        // finding mới đủ material cho 1 trang riêng).
        Map<String, List<BiFinding>> highlightGroups = new LinkedHashMap<>();
        for (Connector.Group g : Connector.groupByBucketAndSubject(decisionGrade, BiFinding.COMPANY_EVENT)) {
            if (g.bigEnoughForOwnPage()) highlightGroups.put(g.subjectKey(), g.members());
        }
        // Row-paired (2 per row), not the classic even/odd-split trick — OpenHTMLtoPDF has no
        // flexbox/grid, only CSS2.1 display:table, so a >2-card group needs real row grouping,
        // not two same-width table-cells sized for exactly 2.
        Map<String, List<List<BiFinding>>> highlightRows = new LinkedHashMap<>();
        highlightGroups.forEach((key, list) -> highlightRows.put(key, partition(list, 2)));
        model.put("highlightRows", highlightRows);
        Map<String, String> highlightAccents = new LinkedHashMap<>();
        highlightGroups.keySet().forEach(key -> highlightAccents.put(key, accentFor(key)));
        model.put("highlightAccents", highlightAccents);

        Map<String, List<BiFinding>> comparisonPages = comparison.stream()
                .collect(Collectors.groupingBy(
                        f -> f.subjectKey() == null || f.subjectKey().isBlank()
                                ? (vi ? "Không rõ cặp so sánh" : "Unspecified comparison pair")
                                : displayLabel(f.subjectKey()),
                        LinkedHashMap::new, Collectors.toList()));
        model.put("comparisonPages", comparisonPages);

        // DEEP_DIVE: mỗi finding = 1 trang riêng (subjectKey null theo thiết kế — bài Deep Dive
        // tự nêu rõ chủ thể ngay trong văn bản, xem InterpretationJob#runDeepDiveSynthesis) —
        // khoá tổng hợp bằng index vì không có subjectKey để nhóm.
        List<BiFinding> deepDives = byBucket.getOrDefault(BiFinding.DEEP_DIVE, List.of());
        Map<String, BiFinding> deepDiveByKey = new LinkedHashMap<>();
        for (int i = 0; i < deepDives.size(); i++) deepDiveByKey.put("DEEP_DIVE_" + i, deepDives.get(i));
        model.put("deepDiveByKey", deepDiveByKey);

        model.put("hasAnyContent", !decisionGrade.isEmpty());
        model.put("bucketsCovered", byBucket.size());
        model.put("findingsTotal", decisionGrade.size());

        List<BiCitation> allCitations = all.stream().flatMap(f -> f.citations().stream())
                .collect(Collectors.toCollection(() -> new java.util.TreeSet<>(
                        java.util.Comparator.comparing(c -> c.label() + "|" + c.url()))))
                .stream().toList();
        List<BiCitation> tierPrimary = allCitations.stream()
                .filter(BiReportPageBuilder::isPrimaryAuthority).toList();
        List<BiCitation> tierSecondary = allCitations.stream()
                .filter(c -> !isPrimaryAuthority(c)).toList();
        model.put("sourcesPrimary", tierPrimary);
        model.put("sourcesSecondary", tierSecondary);
        model.put("sourcesTotal", allCitations.size());

        List<BiPage> pages = plan(vi, macro, theme, pressCalendar, companyEvents, marketShare,
                aiSizing, aiThreat, highlightGroups, comparisonPages, deepDiveByKey,
                reviewedAnalysis, watch);
        model.put("pages", pages);
        List<BiPage> tocEntries = pages.stream()
                .filter(pg -> !pg.type().equals("COVER") && !pg.type().equals("TOC") && !pg.type().equals("BACK"))
                .toList();
        model.put("tocRows", partition(tocEntries, 2));
        return model;
    }

    /** Splits a list into fixed-size row groups (last row may be shorter) — used to drive
     *  CSS2.1 display:table 2-column layouts (TOC, company-highlight cards) with a REAL row
     *  per pair, not a fixed-width-cell trick that breaks once a group has more than 2 items. */
    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> rows = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            rows.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return rows;
    }

    /** Primary evidence is defined by publisher authority, independent from geography. */
    private static boolean isPrimaryAuthority(BiCitation citation) {
        if (citation == null || citation.authority() == null) return false;
        try {
            return com.marketradar.domain.SourceAuthority.valueOf(citation.authority()).isPrimaryEvidence();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /** Real, published brand colors for a small hand-verified set of competitors — never a
     *  guessed or generated color. Matches by substring so "AIA Vietnam" still hits "aia". */
    private static String accentFor(String subjectKey) {
        String key = subjectKey.toLowerCase(java.util.Locale.ROOT);
        for (Map.Entry<String, String> e : COMPETITOR_ACCENTS.entrySet()) {
            if (key.contains(e.getKey())) return e.getValue();
        }
        return DEFAULT_ACCENT;
    }

    /** Deep Research để LLM tự đặt subject_key tự do — đôi khi nó trả 1 slug kiểu lập trình (vd
     *  "our_read_foreign_scale_advantage") thay vì tên cặp so sánh dễ đọc, không nên hiện nguyên
     *  trạng làm tiêu đề trang cho CFO. Chỉ prettify khi rõ ràng LÀ slug (toàn chữ thường/số/gạch
     *  dưới) — key đã là tên hiển thị tự nhiên (có hoa/khoảng trắng/dấu) thì giữ nguyên. */
    static String displayLabel(String key) {
        if (!key.matches("[a-z0-9_]+")) return key;
        StringBuilder sb = new StringBuilder();
        for (String w : key.split("_")) {
            if (w.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.isEmpty() ? key : sb.toString();
    }

    private static List<BiPage> plan(boolean vi, List<BiFinding> macro, List<BiFinding> theme,
                                     List<BiFinding> pressCalendar, List<BiFinding> companyEvents,
                                     List<BiFinding> marketShare, List<BiFinding> aiSizing, List<BiFinding> aiThreat,
                                     Map<String, List<BiFinding>> highlightGroups, Map<String, List<BiFinding>> comparisonPages,
                                     Map<String, BiFinding> deepDiveByKey,
                                     List<BiFinding> reviewedAnalysis, List<BiFinding> watch) {
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
        for (Map.Entry<String, BiFinding> entry : deepDiveByKey.entrySet()) {
            String text = entry.getValue().text(vi);
            String label = text.length() <= 50 ? text : text.substring(0, 50) + "…";
            pages.add(new BiPage(n++, "DEEP_DIVE", label, entry.getKey()));
        }
        if (!reviewedAnalysis.isEmpty()) {
            pages.add(new BiPage(n++, "REVIEWED_ANALYSIS",
                    vi ? "Phân tích đã được biên tập" : "Human-reviewed analysis", null));
        }
        if (!watch.isEmpty()) {
            pages.add(new BiPage(n++, "EDITORIAL_WATCH",
                    vi ? "Tín hiệu cần theo dõi" : "Editorial watch", null));
        }
        pages.add(new BiPage(n++, "SOURCES", vi ? "Nguồn & Phương pháp" : "Sources & method", null));
        pages.add(new BiPage(n, "BACK", vi ? "Trang cuối" : "Back cover", null));
        return pages;
    }
}
