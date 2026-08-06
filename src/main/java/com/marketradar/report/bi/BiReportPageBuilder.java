package com.marketradar.report.bi;

import java.util.ArrayList;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
 *  DEEP_DIVE             -> 1 hồ sơ / chủ thể, gồm các câu đã xác minh độc lập; các singleton được
 *                           gom vào trang brief bổ sung để không tạo trang một câu thưa thớt.
 */
public final class BiReportPageBuilder {

    /** Deterministic display rows used by the management dashboard. Counts describe only
     * verified findings in this edition; they are not market share or performance scores. */
    public record CountBar(String key, String labelVi, String labelEn,
                           int count, int percent, String color) {
        public String label(boolean vi) { return vi ? labelVi : labelEn; }
    }

    /** A real calendar month observed in structured evidence dates. */
    public record ActivityMonth(String key, String labelVi, String labelEn) {
        public String label(boolean vi) { return vi ? labelVi : labelEn; }
    }

    /** Per-company verified activity count by observed calendar month. */
    public record ActivityRow(String subject, Map<String, Integer> counts, int total) {
        public int count(String monthKey) { return counts.getOrDefault(monthKey, 0); }
    }

    /** Optional per-competitor accent (matches published brand colors, not fabricated) —
     *  default #C00000 for any subject not in this small, hand-verified list. */
    private static final Map<String, String> COMPETITOR_ACCENTS = Map.of(
            "chubb", "#041A70",
            "manulife", "#01592F",
            "aia", "#D02148",
            "hanwha", "#EF7423",
            "prudential", "#657076");
    private static final String DEFAULT_ACCENT = "#C00000";
    private static final List<String> CHART_COLORS = List.of(
            "#C00000", "#EF7423", "#D69E00", "#5344B5", "#01592F", "#657076");
    private static final Map<String, String> BUCKET_LABEL_VI = Map.of(
            BiFinding.MACRO_ECONOMIC, "Vĩ mô & quy định",
            BiFinding.COMPETITIVE_THEME, "Chủ đề cạnh tranh",
            BiFinding.SCHEDULED_EVENT, "Sự kiện dự kiến",
            BiFinding.COMPANY_EVENT, "Hoạt động doanh nghiệp",
            BiFinding.MARKET_SHARE_OR_AWARD, "Thị phần & giải thưởng",
            BiFinding.TECH_AI_SIGNAL, "Công nghệ & AI",
            BiFinding.STRATEGIC_COMPARISON, "So sánh chiến lược",
            BiFinding.DEEP_DIVE, "Phân tích sâu");
    private static final Map<String, String> BUCKET_LABEL_EN = Map.of(
            BiFinding.MACRO_ECONOMIC, "Macro & regulation",
            BiFinding.COMPETITIVE_THEME, "Competitive themes",
            BiFinding.SCHEDULED_EVENT, "Scheduled events",
            BiFinding.COMPANY_EVENT, "Company activity",
            BiFinding.MARKET_SHARE_OR_AWARD, "Market share & awards",
            BiFinding.TECH_AI_SIGNAL, "Technology & AI",
            BiFinding.STRATEGIC_COMPARISON, "Strategic comparison",
            BiFinding.DEEP_DIVE, "Deep-dive analysis");

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

        List<CountBar> signalBars = countBars(byBucket.entrySet().stream()
                .filter(e -> !e.getValue().isEmpty())
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().size(),
                        Integer::sum, LinkedHashMap::new)), BUCKET_LABEL_VI, BUCKET_LABEL_EN, 7);
        Map<String, Integer> scopeCounts = new LinkedHashMap<>();
        scopeCounts.put("VN", (int) decisionGrade.stream().filter(BiFinding::isVietnamMarket).count());
        scopeCounts.put("INTL", (int) decisionGrade.stream().filter(f -> !f.isVietnamMarket()).count());
        model.put("signalBars", signalBars);
        model.put("scopeBars", countBars(scopeCounts,
                Map.of("VN", "Việt Nam", "INTL", "Quốc tế / khu vực"),
                Map.of("VN", "Vietnam", "INTL", "International / regional"), 2));

        Map<String, Integer> competitorCounts = companyEvents.stream()
                .filter(f -> f.subjectKey() != null && !f.subjectKey().isBlank())
                .collect(Collectors.groupingBy(BiFinding::subjectKey, LinkedHashMap::new,
                        Collectors.summingInt(ignored -> 1)));
        model.put("competitorBars", countBars(competitorCounts, Map.of(), Map.of(), 8));

        List<ActivityMonth> activityMonths = activityMonths(companyEvents);
        List<ActivityRow> activityRows = activityRows(companyEvents, activityMonths);
        model.put("activityMonths", activityMonths);
        model.put("activityRows", activityRows);
        model.put("hasActivityCalendar", !activityMonths.isEmpty() && !activityRows.isEmpty());

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

        // DEEP_DIVE: every sentence is independently verified, but sentences sharing the
        // same subject belong to one management dossier. Grouping prevents 1-sentence pages
        // while preserving every claim and citation as a separate card.
        List<BiFinding> deepDives = byBucket.getOrDefault(BiFinding.DEEP_DIVE, List.of());
        Map<String, List<BiFinding>> deepDiveGroups = deepDives.stream()
                .collect(Collectors.groupingBy(f -> f.subjectKey() == null || f.subjectKey().isBlank()
                                ? (vi ? "Tổng hợp đa nguồn" : "Cross-source synthesis")
                                : displayLabel(f.subjectKey()),
                        LinkedHashMap::new, Collectors.toList()));
        Map<String, List<List<BiFinding>>> deepDiveRows = new LinkedHashMap<>();
        List<BiFinding> shortBriefs = new ArrayList<>();
        deepDiveGroups.forEach((key, list) -> {
            if (list.size() >= 2) deepDiveRows.put(key, partition(list, 2));
            else shortBriefs.addAll(list);
        });
        if (!shortBriefs.isEmpty()) {
            deepDiveRows.put(vi ? "Các hồ sơ ngắn đã xác minh" : "Additional verified briefs",
                    partition(shortBriefs, 2));
        }
        model.put("deepDiveRows", deepDiveRows);

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
        Map<String, Integer> sourceCounts = new LinkedHashMap<>();
        sourceCounts.put("PRIMARY", tierPrimary.size());
        sourceCounts.put("SECONDARY", tierSecondary.size());
        model.put("sourceBars", countBars(sourceCounts,
                Map.of("PRIMARY", "Nguồn sơ cấp / có thẩm quyền", "SECONDARY", "Nguồn phụ / nghiên cứu bàn giấy"),
                Map.of("PRIMARY", "Primary / authoritative", "SECONDARY", "Secondary / desk research"), 2));

        List<BiPage> pages = plan(vi, macro, theme, pressCalendar, companyEvents, marketShare,
                aiSizing, aiThreat, highlightGroups, comparisonPages, deepDiveRows,
                !activityMonths.isEmpty() && !activityRows.isEmpty(),
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

    private static List<CountBar> countBars(Map<String, Integer> counts,
                                            Map<String, String> labelsVi,
                                            Map<String, String> labelsEn,
                                            int limit) {
        List<Map.Entry<String, Integer>> ranked = counts.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() > 0)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(limit).toList();
        int total = ranked.stream().mapToInt(Map.Entry::getValue).sum();
        List<CountBar> out = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            Map.Entry<String, Integer> entry = ranked.get(i);
            String fallback = displayLabel(entry.getKey());
            int percent = total == 0 ? 0 : Math.max(2,
                    (int) Math.round(entry.getValue() * 100.0 / total));
            out.add(new CountBar(entry.getKey(), labelsVi.getOrDefault(entry.getKey(), fallback),
                    labelsEn.getOrDefault(entry.getKey(), fallback), entry.getValue(), percent,
                    CHART_COLORS.get(i % CHART_COLORS.size())));
        }
        return List.copyOf(out);
    }

    private static List<ActivityMonth> activityMonths(List<BiFinding> companyEvents) {
        List<YearMonth> months = companyEvents.stream().map(BiFinding::eventDateRangeStart)
                .filter(Objects::nonNull).map(YearMonth::from).distinct().sorted().toList();
        if (months.size() > 6) months = months.subList(months.size() - 6, months.size());
        DateTimeFormatter en = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);
        List<ActivityMonth> out = new ArrayList<>();
        for (YearMonth month : months) {
            out.add(new ActivityMonth(month.toString(), String.format("%02d/%d", month.getMonthValue(), month.getYear()),
                    month.format(en)));
        }
        return List.copyOf(out);
    }

    private static List<ActivityRow> activityRows(List<BiFinding> companyEvents,
                                                   List<ActivityMonth> months) {
        if (months.isEmpty()) return List.of();
        var allowed = months.stream().map(ActivityMonth::key).collect(Collectors.toSet());
        Map<String, Map<String, Integer>> grouped = new LinkedHashMap<>();
        for (BiFinding finding : companyEvents) {
            if (finding.subjectKey() == null || finding.subjectKey().isBlank()
                    || finding.eventDateRangeStart() == null) continue;
            String month = YearMonth.from(finding.eventDateRangeStart()).toString();
            if (!allowed.contains(month)) continue;
            grouped.computeIfAbsent(displayLabel(finding.subjectKey()), ignored -> new LinkedHashMap<>())
                    .merge(month, 1, Integer::sum);
        }
        return grouped.entrySet().stream().map(entry -> new ActivityRow(entry.getKey(),
                        Map.copyOf(entry.getValue()), entry.getValue().values().stream().mapToInt(Integer::intValue).sum()))
                .sorted(Comparator.comparingInt(ActivityRow::total).reversed()
                        .thenComparing(ActivityRow::subject))
                .limit(10).toList();
    }

    private static List<BiPage> plan(boolean vi, List<BiFinding> macro, List<BiFinding> theme,
                                     List<BiFinding> pressCalendar, List<BiFinding> companyEvents,
                                     List<BiFinding> marketShare, List<BiFinding> aiSizing, List<BiFinding> aiThreat,
                                     Map<String, List<BiFinding>> highlightGroups, Map<String, List<BiFinding>> comparisonPages,
                                     Map<String, List<List<BiFinding>>> deepDiveRows,
                                     boolean hasActivityCalendar,
                                     List<BiFinding> reviewedAnalysis, List<BiFinding> watch) {
        List<BiPage> pages = new ArrayList<>();
        int n = 1;
        pages.add(new BiPage(n++, "COVER", vi ? "Bìa" : "Cover", null));
        pages.add(new BiPage(n++, "TOC", vi ? "Mục lục" : "Contents", null));
        pages.add(new BiPage(n++, "EXEC", vi ? "Tóm tắt điều hành" : "Executive summary", null));
        pages.add(new BiPage(n++, "SIGNAL_DASHBOARD",
                vi ? "Bản đồ tín hiệu & nguồn" : "Signal & evidence dashboard", null));
        if (!macro.isEmpty()) pages.add(new BiPage(n++, "MACRO", vi ? "Vĩ mô ngành" : "Macro update", null));
        if (!theme.isEmpty()) pages.add(new BiPage(n++, "THEME", vi ? "Xu hướng cạnh tranh" : "Competitive themes", null));
        if (!pressCalendar.isEmpty()) pages.add(new BiPage(n++, "PRESS_CALENDAR", vi ? "Lịch công bố" : "Press calendar", null));
        if (!companyEvents.isEmpty()) pages.add(new BiPage(n++, "EVENTS_TIMELINE", vi ? "Diễn biến theo mốc thời gian" : "Events timeline", null));
        if (hasActivityCalendar) pages.add(new BiPage(n++, "ACTIVITY_CALENDAR",
                vi ? "Lịch hoạt động đối thủ" : "Competitor activity calendar", null));
        if (!marketShare.isEmpty()) pages.add(new BiPage(n++, "AWARDS_MARKET_SHARE", vi ? "Thị phần / Giải thưởng" : "Awards / market share", null));
        if (!aiSizing.isEmpty()) pages.add(new BiPage(n++, "AI_SIZING", vi ? "Định cỡ thị trường AI/Insurtech" : "AI / insurtech sizing", null));
        if (!aiThreat.isEmpty()) pages.add(new BiPage(n++, "AI_THREATMAP", vi ? "Bản đồ rủi ro AI" : "AI threat map", null));
        for (String key : highlightGroups.keySet()) {
            pages.add(new BiPage(n++, "COMPANY_HIGHLIGHT", key, key));
        }
        for (String key : comparisonPages.keySet()) {
            pages.add(new BiPage(n++, "COMPARISON", key, key));
        }
        for (String subject : deepDiveRows.keySet()) {
            pages.add(new BiPage(n++, "DEEP_DIVE", subject, subject));
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
