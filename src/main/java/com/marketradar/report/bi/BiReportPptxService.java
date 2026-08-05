package com.marketradar.report.bi;

import org.apache.poi.sl.usermodel.ShapeType;
import org.apache.poi.sl.usermodel.TextParagraph;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFAutoShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 2026-08-03 (feedback: mẫu thật là 1 slide deck ngang kiểu Techcom Life — bảng/thẻ/badge, khác
 * hẳn báo cáo dọc kiểu narrative của bi-report.html): xuất CÙNG {@link BiReportContent} (dùng
 * chung cho cả report định kỳ và Deep Research, y hệt {@link BiReportDocxService}) ra .pptx thật,
 * mở/sửa được trực tiếp trong PowerPoint — không phải PDF/HTML giả dạng slide.
 *
 * Chỉ dựng slide từ những field BiFinding THẬT SỰ có (text/metricPercent/severity/subjectKey) —
 * KHÔNG bịa lịch Gantt theo tháng hay thẻ KPI từ việc scrape số trong văn bản tự do: những phần đó
 * của mẫu cần field cấu trúc mới (vd ngày sự kiện) chưa tồn tại, để dành cho một đợt sau nếu cần.
 *
 * 2026-08-03 (feedback 2, dữ liệu thật tràn khỏi khung 1 slide): mỗi loại slide "thẻ văn bản"
 * (tóm tắt/bối cảnh/so sánh/nguồn) và "bảng" (thị phần/threat map/sự kiện) đều tự CHIA TRANG khi
 * nội dung vượt sức chứa ước tính — không có API "tự co chữ để vừa 1 trang" đáng tin cậy trong
 * POI XSLF, nên cách chắc chắn nhất là ước lượng số dòng cần và tạo thêm slide khi vượt ngưỡng,
 * thay vì nhồi tất cả vào 1 khung cố định rồi để chữ tràn ra ngoài (đúng lỗi thật đã gặp).
 */
@Service
public class BiReportPptxService {

    private static final Color RED = new Color(0xC0, 0x00, 0x00);
    private static final Color BLACK = Color.BLACK;
    private static final Color WHITE = Color.WHITE;
    private static final Color GREY_HEADER = new Color(0x80, 0x80, 0x80);
    private static final Color TEXT_DARK = new Color(0x22, 0x22, 0x22);
    private static final Color BADGE_HIGH_BG = new Color(0xF8, 0xD7, 0xDA);
    private static final Color BADGE_MED_BG = new Color(0xFF, 0xF3, 0xCD);
    private static final Color BADGE_MED_FG = new Color(0x8A, 0x6D, 0x00);
    private static final Color BADGE_LOW_BG = new Color(0xD4, 0xED, 0xDA);
    private static final Color BADGE_LOW_FG = new Color(0x1E, 0x7B, 0x34);

    private static final double PAGE_W = 960;
    private static final double PAGE_H = 540;
    private static final double CARD_BODY_W = 800;   // khung text bên trong thẻ bo góc
    private static final double CARD_BODY_H = 330;   // chiều cao khả dụng trước khi phải sang trang mới
    private static final int MAX_TABLE_ROWS = 10;    // dòng dữ liệu/1 bảng — an toàn với hàng 30pt trong khung 400pt
    private static final int TABLE_NOTE_MAX_CHARS = 90; // giữ cột "ghi chú/vì sao" ở đúng 1 dòng, không wrap

    public byte[] render(BiReportContent content) {
        return render(content, true);
    }

    public byte[] render(BiReportContent content, boolean vi) {
        try (XMLSlideShow ppt = new XMLSlideShow()) {
            ppt.setPageSize(new Dimension((int) PAGE_W, (int) PAGE_H));

            coverSlide(ppt, content, vi);

            List<BiFinding> highlights = content.findings().stream().filter(BiFinding::highlight).toList();
            if (!highlights.isEmpty()) {
                bulletCardSlides(ppt, vi ? "TÓM TẮT ĐIỀU HÀNH" : "EXECUTIVE SUMMARY",
                        vi ? "Nhận định chính" : "Key findings", RED,
                        highlights.stream().map(f -> f.text(vi)).toList(), 14);
            }

            List<BiFinding> background = byBucket(content, BiFinding.MACRO_ECONOMIC, BiFinding.COMPETITIVE_THEME);
            List<BiFinding> events = byBucket(content, BiFinding.COMPANY_EVENT, BiFinding.SCHEDULED_EVENT);
            List<BiFinding> scheduledWithDate = events.stream()
                    .filter(f -> BiFinding.SCHEDULED_EVENT.equals(f.bucket()) && f.eventDateLabel() != null)
                    .toList();
            if (!background.isEmpty() || !scheduledWithDate.isEmpty()) {
                marketScanSlide(ppt, background, scheduledWithDate, vi);
            }

            List<BiFinding> marketShare = byBucket(content, BiFinding.MARKET_SHARE_OR_AWARD);
            if (!marketShare.isEmpty()) marketShareSlides(ppt, marketShare, vi);

            List<BiFinding> aiThreat = byBucket(content, BiFinding.TECH_AI_SIGNAL).stream()
                    .filter(f -> f.severity() != null).toList();
            if (!aiThreat.isEmpty()) threatMapSlides(ppt, aiThreat, vi);

            // Sự kiện đã lên bảng lịch ở marketScanSlide() rồi thì không lặp lại ở đây nữa.
            List<BiFinding> remainingEvents = events.stream().filter(f -> !scheduledWithDate.contains(f)).toList();
            if (!remainingEvents.isEmpty()) eventsSlides(ppt, remainingEvents, vi);

            List<BiFinding> comparison = byBucket(content, BiFinding.STRATEGIC_COMPARISON);
            if (!comparison.isEmpty()) comparisonSlides(ppt, comparison, vi);

            sourcesSlides(ppt, content, vi);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ppt.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Xuất BI report pptx thất bại: " + e.getMessage(), e);
        }
    }

    private static List<BiFinding> byBucket(BiReportContent content, String... buckets) {
        Set<String> set = Set.of(buckets);
        return content.findings().stream().filter(f -> set.contains(f.bucket())).toList();
    }

    // ---------------------------------------------------------------- slides

    private void coverSlide(XMLSlideShow ppt, BiReportContent content, boolean vi) {
        XSLFSlide slide = ppt.createSlide();
        fillBackground(slide, RED);
        addText(slide, new Rectangle2D.Double(700, 24, 204, 26), "TECHCOM LIFE",
                12, true, WHITE, TextParagraph.TextAlign.RIGHT);
        addText(slide, new Rectangle2D.Double(56, 220, 848, 180), content.title(),
                30, true, WHITE, TextParagraph.TextAlign.LEFT);
        addText(slide, new Rectangle2D.Double(56, 460, 700, 26),
                content.period() + (vi ? "  ·  Tạo lúc " : "  ·  Generated ") + content.generatedAt(),
                12, false, WHITE, TextParagraph.TextAlign.LEFT);
    }

    /** Dùng chung cho mọi slide "1 thẻ bo góc + danh sách bullet" (tóm tắt, so sánh, nguồn,
     *  khoảng trống) — tự chia trang theo {@link #paginate}, không cần lặp lại logic layout. */
    private void bulletCardSlides(XMLSlideShow ppt, String headerTitle, String pillLabel, Color accent,
                                  List<String> texts, double fontSize) {
        List<List<String>> pages = paginate(texts, Function.identity(), CARD_BODY_W, fontSize, CARD_BODY_H);
        for (int i = 0; i < pages.size(); i++) {
            XSLFSlide slide = ppt.createSlide();
            sectionHeader(slide, headerTitle + pageSuffix(i, pages.size()));
            roundedCard(slide, new Rectangle2D.Double(56, 90, 848, 400), accent);
            headerPill(slide, new Rectangle2D.Double(76, 100, 240, 26), pillLabel, accent);
            XSLFTextBox body = slide.createTextBox();
            body.setAnchor(new Rectangle2D.Double(90, 140, CARD_BODY_W, CARD_BODY_H));
            for (String t : pages.get(i)) {
                XSLFTextParagraph p = body.addNewTextParagraph();
                p.setBullet(true);
                p.setSpaceBefore(10.0);
                addMarkdownRuns(p, t, fontSize, TEXT_DARK);
            }
        }
    }

    /**
     * Tái tạo bám sát slide "MARKET SCAN" của mẫu Techcom Life thật (2 cột: tổng quan thị trường
     * bên trái, lịch sự kiện dự kiến theo đối thủ bên phải) — khác phong cách thẻ bo góc/pill màu
     * của các slide khác trong file này vì mẫu THẬT dùng thanh tiêu đề đen phẳng cho đúng slide
     * này. KHÔNG tái tạo bảng "Competitive Themes & Strategic Signals" của mẫu (góc dưới-trái) —
     * bảng đó cần 1 taxonomy chủ đề + đánh giá "độ mạnh tín hiệu" mà hệ thống chưa có cách tổng
     * hợp đáng tin cậy (xem trao đổi 2026-08-03), cố làm sẽ phải bịa nhãn.
     */
    private void marketScanSlide(XMLSlideShow ppt, List<BiFinding> background,
                                 List<BiFinding> scheduledEvents, boolean vi) {
        XSLFSlide slide = ppt.createSlide();
        marketScanHeader(slide, "MARKET SCAN");

        blackBar(slide, new Rectangle2D.Double(56, 60, 428, 26),
                vi ? "TỔNG QUAN THỊ TRƯỜNG" : "MARKET OVERVIEW");
        plainBox(slide, new Rectangle2D.Double(56, 90, 428, 340));
        XSLFTextBox overviewBox = slide.createTextBox();
        overviewBox.setAnchor(new Rectangle2D.Double(68, 100, 404, 320));
        BiFinding overview = background.stream().filter(BiFinding::highlight).findFirst()
                .orElse(background.isEmpty() ? null : background.get(0));
        if (overview != null) {
            XSLFTextParagraph p = overviewBox.addNewTextParagraph();
            addMarkdownRuns(p, truncate(overview.text(vi), 700), 12.0, TEXT_DARK);
        } else {
            XSLFTextParagraph p = overviewBox.addNewTextParagraph();
            XSLFTextRun r = p.addNewTextRun();
            r.setText(vi ? "Chưa có dữ liệu tổng quan thị trường trong kỳ này."
                    : "No market-overview evidence is available for this period.");
            r.setItalic(true);
            r.setFontSize(12.0);
            r.setFontColor(TEXT_DARK);
        }

        blackBar(slide, new Rectangle2D.Double(492, 60, 412, 26),
                vi ? "LỊCH SỰ KIỆN DỰ KIẾN THEO ĐỐI THỦ" : "COMPETITOR DISCLOSURE CALENDAR");
        if (scheduledEvents.isEmpty()) {
            addText(slide, new Rectangle2D.Double(492, 100, 412, 40),
                    vi ? "Chưa có mốc thời gian cụ thể nào trong kỳ này."
                            : "No specific scheduled date is available for this period.",
                    11, false, TEXT_DARK, TextParagraph.TextAlign.LEFT);
        } else {
            XSLFTable table = slide.createTable();
            table.setAnchor(new Rectangle2D.Double(492, 90, 412, 340));
            XSLFTableRow header = table.addRow();
            header.setHeight(26);
            addCell(header, vi ? "Đối thủ" : "Competitor", true, BLACK, WHITE);
            addCell(header, vi ? "Dự kiến" : "Expected", true, BLACK, WHITE);
            addCell(header, vi ? "Sự kiện" : "Event", true, BLACK, WHITE);
            for (BiFinding f : chunk(scheduledEvents, MAX_TABLE_ROWS).get(0)) {
                XSLFTableRow row = table.addRow();
                row.setHeight(30);
                addCell(row, f.subjectKey() != null && !f.subjectKey().isBlank() ? f.subjectKey() : "-", false, WHITE, TEXT_DARK);
                addCell(row, f.eventDateLabel(), true, WHITE, RED);
                addCell(row, truncate(f.text(vi), 70), false, WHITE, TEXT_DARK);
            }
            setColumnWidths(table, 120, 90, 202);
        }
    }

    private void marketShareSlides(XMLSlideShow ppt, List<BiFinding> findings, boolean vi) {
        List<List<BiFinding>> pages = chunk(findings, MAX_TABLE_ROWS);
        for (int i = 0; i < pages.size(); i++) {
            XSLFSlide slide = ppt.createSlide();
            sectionHeader(slide, (vi ? "THỊ PHẦN / GIẢI THƯỞNG" : "MARKET SHARE / AWARDS")
                    + pageSuffix(i, pages.size()));
            XSLFTable table = slide.createTable();
            table.setAnchor(new Rectangle2D.Double(56, 90, 848, 400));
            XSLFTableRow header = table.addRow();
            header.setHeight(28);
            addCell(header, vi ? "Chủ thể" : "Subject", true, BLACK, WHITE);
            addCell(header, vi ? "Số liệu" : "Metric", true, BLACK, WHITE);
            addCell(header, vi ? "Ghi chú" : "Evidence note", true, BLACK, WHITE);
            for (BiFinding f : pages.get(i)) {
                XSLFTableRow row = table.addRow();
                row.setHeight(30);
                addCell(row, f.subjectKey() != null && !f.subjectKey().isBlank() ? f.subjectKey() : "-", false, WHITE, TEXT_DARK);
                addCell(row, f.metricPercent() != null ? f.metricPercent() + "%" : "—", false, WHITE, TEXT_DARK);
                addCell(row, truncate(f.text(vi), TABLE_NOTE_MAX_CHARS), false, WHITE, TEXT_DARK);
            }
            setColumnWidths(table, 220, 100, 528);
        }
    }

    private void threatMapSlides(XMLSlideShow ppt, List<BiFinding> findings, boolean vi) {
        List<List<BiFinding>> pages = chunk(findings, MAX_TABLE_ROWS);
        for (int i = 0; i < pages.size(); i++) {
            XSLFSlide slide = ppt.createSlide();
            sectionHeader(slide, (vi ? "BẢN ĐỒ RỦI RO AI THEO ĐỐI THỦ" : "COMPETITOR AI RISK MAP")
                    + pageSuffix(i, pages.size()));
            XSLFTable table = slide.createTable();
            table.setAnchor(new Rectangle2D.Double(56, 90, 848, 400));
            XSLFTableRow header = table.addRow();
            header.setHeight(28);
            addCell(header, vi ? "Mức độ" : "Severity", true, BLACK, WHITE);
            addCell(header, vi ? "Đối thủ" : "Competitor", true, BLACK, WHITE);
            addCell(header, vi ? "Vì sao" : "Rationale", true, BLACK, WHITE);
            for (BiFinding f : pages.get(i)) {
                XSLFTableRow row = table.addRow();
                row.setHeight(30);
                Color[] badge = badgeColors(f.severity());
                addCell(row, f.severity(), true, badge[0], badge[1]);
                addCell(row, f.subjectKey() != null && !f.subjectKey().isBlank() ? f.subjectKey() : "-", false, WHITE, TEXT_DARK);
                addCell(row, truncate(f.text(vi), TABLE_NOTE_MAX_CHARS), false, WHITE, TEXT_DARK);
            }
            setColumnWidths(table, 120, 160, 568);
        }
    }

    private void eventsSlides(XMLSlideShow ppt, List<BiFinding> findings, boolean vi) {
        List<List<BiFinding>> pages = chunk(findings, MAX_TABLE_ROWS);
        for (int i = 0; i < pages.size(); i++) {
            XSLFSlide slide = ppt.createSlide();
            sectionHeader(slide, (vi ? "DIỄN BIẾN THEO ĐỐI THỦ & LỊCH SẮP TỚI"
                    : "COMPETITOR DEVELOPMENTS & UPCOMING EVENTS") + pageSuffix(i, pages.size()));
            XSLFTable table = slide.createTable();
            table.setAnchor(new Rectangle2D.Double(56, 90, 848, 400));
            XSLFTableRow header = table.addRow();
            header.setHeight(28);
            addCell(header, vi ? "Đối thủ" : "Competitor", true, BLACK, WHITE);
            addCell(header, vi ? "Diễn biến" : "Development", true, BLACK, WHITE);
            addCell(header, vi ? "Nguồn" : "Source", true, BLACK, WHITE);
            for (BiFinding f : pages.get(i)) {
                XSLFTableRow row = table.addRow();
                row.setHeight(30);
                addCell(row, f.subjectKey() != null && !f.subjectKey().isBlank() ? f.subjectKey() : "-", false, WHITE, TEXT_DARK);
                addCell(row, truncate(f.text(vi), TABLE_NOTE_MAX_CHARS), false, WHITE, TEXT_DARK);
                addCell(row, citationLabel(f), false, WHITE, TEXT_DARK);
            }
            setColumnWidths(table, 160, 528, 160);
        }
    }

    private void comparisonSlides(XMLSlideShow ppt, List<BiFinding> findings, boolean vi) {
        Map<String, List<BiFinding>> groups = new LinkedHashMap<>();
        for (BiFinding f : findings) {
            String key = f.subjectKey() == null || f.subjectKey().isBlank()
                    ? (vi ? "Không rõ cặp so sánh" : "Unspecified comparison pair")
                    : BiReportPageBuilder.displayLabel(f.subjectKey());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(f);
        }
        for (var entry : groups.entrySet()) {
            String headerTitle = (vi ? "SO SÁNH CHIẾN LƯỢC · " : "STRATEGIC COMPARISON · ")
                    + entry.getKey().toUpperCase(Locale.ROOT);
            List<List<BiFinding>> pages = paginate(entry.getValue(), f -> f.text(vi), CARD_BODY_W, 13, CARD_BODY_H);
            for (int i = 0; i < pages.size(); i++) {
                XSLFSlide slide = ppt.createSlide();
                sectionHeader(slide, headerTitle + pageSuffix(i, pages.size()));
                roundedCard(slide, new Rectangle2D.Double(56, 90, 848, 400), RED);
                headerPill(slide, new Rectangle2D.Double(76, 100, 260, 26),
                        vi ? "Góc nhìn của chúng tôi" : "Our perspective", RED);
                XSLFTextBox body = slide.createTextBox();
                body.setAnchor(new Rectangle2D.Double(90, 140, CARD_BODY_W, CARD_BODY_H));
                for (BiFinding f : pages.get(i)) {
                    XSLFTextParagraph p = body.addNewTextParagraph();
                    p.setBullet(true);
                    p.setSpaceBefore(10.0);
                    addMarkdownRuns(p, f.text(vi), 13.0, TEXT_DARK);
                }
            }
        }
    }

    private void sourcesSlides(XMLSlideShow ppt, BiReportContent content, boolean vi) {
        if (content.sourceLines().isEmpty()) {
            XSLFSlide slide = ppt.createSlide();
            sectionHeader(slide, vi ? "NGUỒN & PHƯƠNG PHÁP" : "SOURCES & METHOD");
            addText(slide, new Rectangle2D.Double(56, 90, 848, 40),
                    vi ? "Chưa có nguồn nào trong kỳ này." : "No source is available for this period.",
                    12, false, TEXT_DARK, TextParagraph.TextAlign.LEFT);
        } else {
            bulletCardSlides(ppt, vi ? "NGUỒN & PHƯƠNG PHÁP" : "SOURCES & METHOD",
                    vi ? "Nguồn đã dùng" : "Sources used", GREY_HEADER,
                    content.sourceLines(), 12);
        }
        if (!content.openGaps().isEmpty()) {
            bulletCardSlides(ppt, vi ? "KHOẢNG TRỐNG DỮ LIỆU" : "EVIDENCE GAPS",
                    vi ? "Cần lưu ý" : "Attention required", GREY_HEADER, content.openGaps(), 12);
        }
    }

    // ---------------------------------------------------------------- pagination

    private static String pageSuffix(int index, int total) {
        return total <= 1 ? "" : " (" + (index + 1) + "/" + total + ")";
    }

    private static <T> List<List<T>> chunk(List<T> items, int size) {
        List<List<T>> chunks = new ArrayList<>();
        for (int i = 0; i < items.size(); i += size) {
            chunks.add(items.subList(i, Math.min(items.size(), i + size)));
        }
        return chunks.isEmpty() ? List.of(List.of()) : chunks;
    }

    /** Ước lượng thô số dòng 1 đoạn text sẽ chiếm khi wrap trong khung rộng boxWidthPt ở cỡ chữ
     *  fontSizePt — không có API đo chữ chính xác nào rẻ trong POI, nhưng ước lượng theo độ rộng
     *  ký tự trung bình đủ để tránh tràn nghiêm trọng (mục tiêu: KHÔNG BAO GIỜ tràn, chấp nhận
     *  đôi khi chia trang sớm hơn cần thiết một chút). */
    private static int estimateLines(String text, double boxWidthPt, double fontSizePt) {
        if (text == null || text.isBlank()) return 1;
        double avgCharWidth = fontSizePt * 0.52;
        int charsPerLine = Math.max(20, (int) (boxWidthPt / avgCharWidth));
        return Math.max(1, (int) Math.ceil((double) text.length() / charsPerLine));
    }

    /** Chia items thành nhiều trang sao cho tổng số dòng ước lượng của mỗi trang không vượt
     *  maxHeightPt — dùng chung cho mọi slide "thẻ + bullet" (tóm tắt, bối cảnh, so sánh, nguồn). */
    private static <T> List<List<T>> paginate(List<T> items, Function<T, String> textOf,
                                              double boxWidthPt, double fontSizePt, double maxHeightPt) {
        double lineHeight = fontSizePt * 1.35;
        int maxLines = Math.max(1, (int) (maxHeightPt / lineHeight));
        List<List<T>> pages = new ArrayList<>();
        List<T> current = new ArrayList<>();
        int lines = 0;
        for (T item : items) {
            int itemLines = estimateLines(textOf.apply(item), boxWidthPt, fontSizePt) + 1; // +1: khoảng cách trước đoạn
            if (!current.isEmpty() && lines + itemLines > maxLines) {
                pages.add(current);
                current = new ArrayList<>();
                lines = 0;
            }
            current.add(item);
            lines += itemLines;
        }
        if (!current.isEmpty()) pages.add(current);
        return pages.isEmpty() ? List.of(List.of()) : pages;
    }

    // ---------------------------------------------------------------- primitives

    private static void fillBackground(XSLFSlide slide, Color color) {
        XSLFAutoShape bg = slide.createAutoShape();
        bg.setShapeType(ShapeType.RECT);
        bg.setAnchor(new Rectangle2D.Double(0, 0, PAGE_W, PAGE_H));
        bg.setFillColor(color);
        bg.setLineColor(color);
    }

    private static void sectionHeader(XSLFSlide slide, String title) {
        addText(slide, new Rectangle2D.Double(56, 12, 800, 32), title, 22, true, RED, TextParagraph.TextAlign.LEFT);
        XSLFAutoShape rule = slide.createAutoShape();
        rule.setShapeType(ShapeType.RECT);
        rule.setAnchor(new Rectangle2D.Double(56, 46, 848, 2));
        rule.setFillColor(RED);
        rule.setLineColor(RED);
    }

    private static void headerPill(XSLFSlide slide, Rectangle2D anchor, String text, Color color) {
        XSLFAutoShape pill = slide.createAutoShape();
        pill.setShapeType(ShapeType.ROUND_RECT);
        pill.setAnchor(anchor);
        pill.setFillColor(color);
        pill.setLineColor(color);
        XSLFTextParagraph p = pill.addNewTextParagraph();
        p.setTextAlign(TextParagraph.TextAlign.CENTER);
        XSLFTextRun r = p.addNewTextRun();
        r.setText(text);
        r.setFontSize(12.0);
        r.setBold(true);
        r.setFontColor(WHITE);
    }

    /** Tiêu đề riêng cho slide "MARKET SCAN" — chữ đen + ô vuông đỏ nhỏ bên trái, khác
     *  {@link #sectionHeader} (chữ đỏ + gạch chân) để bám sát đúng mẫu thật cho slide này. */
    private static void marketScanHeader(XSLFSlide slide, String title) {
        XSLFAutoShape icon = slide.createAutoShape();
        icon.setShapeType(ShapeType.RECT);
        icon.setAnchor(new Rectangle2D.Double(56, 14, 26, 26));
        icon.setFillColor(RED);
        icon.setLineColor(RED);
        addText(slide, new Rectangle2D.Double(92, 10, 500, 34), title, 24, true, BLACK, TextParagraph.TextAlign.LEFT);
        addText(slide, new Rectangle2D.Double(700, 20, 204, 26), "TECHCOM LIFE", 12, true, RED, TextParagraph.TextAlign.RIGHT);
    }

    private static void blackBar(XSLFSlide slide, Rectangle2D anchor, String label) {
        XSLFAutoShape bar = slide.createAutoShape();
        bar.setShapeType(ShapeType.RECT);
        bar.setAnchor(anchor);
        bar.setFillColor(BLACK);
        bar.setLineColor(BLACK);
        XSLFTextParagraph p = bar.addNewTextParagraph();
        XSLFTextRun r = p.addNewTextRun();
        r.setText(label);
        r.setBold(true);
        r.setFontSize(12.0);
        r.setFontColor(WHITE);
    }

    private static void plainBox(XSLFSlide slide, Rectangle2D anchor) {
        XSLFAutoShape box = slide.createAutoShape();
        box.setShapeType(ShapeType.RECT);
        box.setAnchor(anchor);
        box.setFillColor(WHITE);
        box.setLineColor(GREY_HEADER);
        box.setLineWidth(1.0);
    }

    /** Parse **bold** kiểu markdown (LLM Deep Research được dặn bọc tối đa 2 cụm/finding) thành
     *  nhiều run trong 1 đoạn CÓ SẴN — dùng chung cho mọi nơi hiện textVi (tóm tắt, market scan,
     *  so sánh chiến lược) để "**" không bao giờ lộ nguyên trạng ra slide dù finding nào chứa nó;
     *  không có cú pháp thì render y hệt 1 run thường, không vỡ. */
    private static void addMarkdownRuns(XSLFTextParagraph p, String text, double fontSize, Color color) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\*\\*(.+?)\\*\\*").matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                XSLFTextRun plain = p.addNewTextRun();
                plain.setText(text.substring(last, m.start()));
                plain.setFontSize(fontSize);
                plain.setFontColor(color);
            }
            XSLFTextRun bold = p.addNewTextRun();
            bold.setText(m.group(1));
            bold.setBold(true);
            bold.setFontSize(fontSize);
            bold.setFontColor(color);
            last = m.end();
        }
        if (last < text.length()) {
            XSLFTextRun tail = p.addNewTextRun();
            tail.setText(text.substring(last));
            tail.setFontSize(fontSize);
            tail.setFontColor(color);
        }
    }

    private static void roundedCard(XSLFSlide slide, Rectangle2D anchor, Color borderColor) {
        XSLFAutoShape card = slide.createAutoShape();
        card.setShapeType(ShapeType.ROUND_RECT);
        card.setAnchor(anchor);
        card.setFillColor(WHITE);
        card.setLineColor(borderColor);
        card.setLineWidth(1.5);
    }

    private static void addText(XSLFSlide slide, Rectangle2D anchor, String text, double fontSize,
                                boolean bold, Color color, TextParagraph.TextAlign align) {
        XSLFTextBox tb = slide.createTextBox();
        tb.setAnchor(anchor);
        XSLFTextParagraph p = tb.addNewTextParagraph();
        p.setTextAlign(align);
        XSLFTextRun r = p.addNewTextRun();
        r.setText(text);
        r.setFontSize(fontSize);
        r.setBold(bold);
        r.setFontColor(color);
    }

    private static void addCell(XSLFTableRow row, String text, boolean bold, Color bg, Color fg) {
        XSLFTableCell cell = row.addCell();
        cell.setFillColor(bg);
        XSLFTextParagraph p = cell.addNewTextParagraph();
        XSLFTextRun r = p.addNewTextRun();
        r.setText(text == null ? "" : text);
        r.setBold(bold);
        r.setFontSize(11.0);
        r.setFontColor(fg);
    }

    private static void setColumnWidths(XSLFTable table, double... widths) {
        for (int i = 0; i < widths.length; i++) table.setColumnWidth(i, widths[i]);
    }

    private static Color[] badgeColors(String severity) {
        if ("HIGH".equals(severity)) return new Color[]{BADGE_HIGH_BG, RED};
        if ("MEDIUM".equals(severity)) return new Color[]{BADGE_MED_BG, BADGE_MED_FG};
        return new Color[]{BADGE_LOW_BG, BADGE_LOW_FG};
    }

    private static String citationLabel(BiFinding f) {
        if (f.citations().isEmpty()) return "—";
        BiCitation c = f.citations().get(0);
        return c.label() + (c.tierNote() != null && !c.tierNote().isBlank() ? " (" + c.tierNote() + ")" : "");
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        String t = text.strip();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
