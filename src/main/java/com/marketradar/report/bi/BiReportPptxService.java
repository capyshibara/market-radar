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
        try (XMLSlideShow ppt = new XMLSlideShow()) {
            ppt.setPageSize(new Dimension((int) PAGE_W, (int) PAGE_H));

            coverSlide(ppt, content);

            List<BiFinding> highlights = content.findings().stream().filter(BiFinding::highlight).toList();
            if (!highlights.isEmpty()) {
                bulletCardSlides(ppt, "TÓM TẮT ĐIỀU HÀNH", "Nhận định chính", RED,
                        highlights.stream().map(BiFinding::textVi).toList(), 14);
            }

            List<BiFinding> background = byBucket(content, BiFinding.MACRO_ECONOMIC, BiFinding.COMPETITIVE_THEME);
            if (!background.isEmpty()) industryBackgroundSlides(ppt, background);

            List<BiFinding> marketShare = byBucket(content, BiFinding.MARKET_SHARE_OR_AWARD);
            if (!marketShare.isEmpty()) marketShareSlides(ppt, marketShare);

            List<BiFinding> aiThreat = byBucket(content, BiFinding.TECH_AI_SIGNAL).stream()
                    .filter(f -> f.severity() != null).toList();
            if (!aiThreat.isEmpty()) threatMapSlides(ppt, aiThreat);

            List<BiFinding> events = byBucket(content, BiFinding.COMPANY_EVENT, BiFinding.SCHEDULED_EVENT);
            if (!events.isEmpty()) eventsSlides(ppt, events);

            List<BiFinding> comparison = byBucket(content, BiFinding.STRATEGIC_COMPARISON);
            if (!comparison.isEmpty()) comparisonSlides(ppt, comparison);

            sourcesSlides(ppt, content);

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

    private void coverSlide(XMLSlideShow ppt, BiReportContent content) {
        XSLFSlide slide = ppt.createSlide();
        fillBackground(slide, RED);
        addText(slide, new Rectangle2D.Double(700, 24, 204, 26), "TECHCOM LIFE",
                12, true, WHITE, TextParagraph.TextAlign.RIGHT);
        addText(slide, new Rectangle2D.Double(56, 220, 848, 180), content.title(),
                30, true, WHITE, TextParagraph.TextAlign.LEFT);
        addText(slide, new Rectangle2D.Double(56, 460, 700, 26),
                content.period() + "  ·  Tạo lúc " + content.generatedAt(),
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
                XSLFTextRun r = p.addNewTextRun();
                r.setText(t);
                r.setFontSize(fontSize);
                r.setFontColor(TEXT_DARK);
            }
        }
    }

    private void industryBackgroundSlides(XMLSlideShow ppt, List<BiFinding> findings) {
        List<List<BiFinding>> pages = paginate(findings,
                f -> (f.subjectKey() != null && !f.subjectKey().isBlank() ? f.subjectKey() + ": " : "") + f.textVi(),
                CARD_BODY_W, 13, CARD_BODY_H);
        for (int i = 0; i < pages.size(); i++) {
            XSLFSlide slide = ppt.createSlide();
            sectionHeader(slide, "VĨ MÔ & XU HƯỚNG CẠNH TRANH" + pageSuffix(i, pages.size()));
            roundedCard(slide, new Rectangle2D.Double(56, 90, 848, 400), GREY_HEADER);
            headerPill(slide, new Rectangle2D.Double(76, 100, 220, 26), "Bối cảnh ngành", GREY_HEADER);
            XSLFTextBox body = slide.createTextBox();
            body.setAnchor(new Rectangle2D.Double(90, 140, CARD_BODY_W, CARD_BODY_H));
            for (BiFinding f : pages.get(i)) {
                XSLFTextParagraph p = body.addNewTextParagraph();
                p.setSpaceBefore(10.0);
                if (f.subjectKey() != null && !f.subjectKey().isBlank()) {
                    XSLFTextRun subj = p.addNewTextRun();
                    subj.setText(f.subjectKey() + ": ");
                    subj.setBold(true);
                    subj.setFontSize(13.0);
                    subj.setFontColor(TEXT_DARK);
                }
                XSLFTextRun r = p.addNewTextRun();
                r.setText(f.textVi());
                r.setFontSize(13.0);
                r.setFontColor(TEXT_DARK);
            }
        }
    }

    private void marketShareSlides(XMLSlideShow ppt, List<BiFinding> findings) {
        List<List<BiFinding>> pages = chunk(findings, MAX_TABLE_ROWS);
        for (int i = 0; i < pages.size(); i++) {
            XSLFSlide slide = ppt.createSlide();
            sectionHeader(slide, "THỊ PHẦN / GIẢI THƯỞNG" + pageSuffix(i, pages.size()));
            XSLFTable table = slide.createTable();
            table.setAnchor(new Rectangle2D.Double(56, 90, 848, 400));
            XSLFTableRow header = table.addRow();
            header.setHeight(28);
            addCell(header, "Chủ thể", true, BLACK, WHITE);
            addCell(header, "Số liệu", true, BLACK, WHITE);
            addCell(header, "Ghi chú", true, BLACK, WHITE);
            for (BiFinding f : pages.get(i)) {
                XSLFTableRow row = table.addRow();
                row.setHeight(30);
                addCell(row, f.subjectKey() != null && !f.subjectKey().isBlank() ? f.subjectKey() : "-", false, WHITE, TEXT_DARK);
                addCell(row, f.metricPercent() != null ? f.metricPercent() + "%" : "—", false, WHITE, TEXT_DARK);
                addCell(row, truncate(f.textVi(), TABLE_NOTE_MAX_CHARS), false, WHITE, TEXT_DARK);
            }
            setColumnWidths(table, 220, 100, 528);
        }
    }

    private void threatMapSlides(XMLSlideShow ppt, List<BiFinding> findings) {
        List<List<BiFinding>> pages = chunk(findings, MAX_TABLE_ROWS);
        for (int i = 0; i < pages.size(); i++) {
            XSLFSlide slide = ppt.createSlide();
            sectionHeader(slide, "BẢN ĐỒ RỦI RO AI THEO ĐỐI THỦ" + pageSuffix(i, pages.size()));
            XSLFTable table = slide.createTable();
            table.setAnchor(new Rectangle2D.Double(56, 90, 848, 400));
            XSLFTableRow header = table.addRow();
            header.setHeight(28);
            addCell(header, "Mức độ", true, BLACK, WHITE);
            addCell(header, "Đối thủ", true, BLACK, WHITE);
            addCell(header, "Vì sao", true, BLACK, WHITE);
            for (BiFinding f : pages.get(i)) {
                XSLFTableRow row = table.addRow();
                row.setHeight(30);
                Color[] badge = badgeColors(f.severity());
                addCell(row, f.severity(), true, badge[0], badge[1]);
                addCell(row, f.subjectKey() != null && !f.subjectKey().isBlank() ? f.subjectKey() : "-", false, WHITE, TEXT_DARK);
                addCell(row, truncate(f.textVi(), TABLE_NOTE_MAX_CHARS), false, WHITE, TEXT_DARK);
            }
            setColumnWidths(table, 120, 160, 568);
        }
    }

    private void eventsSlides(XMLSlideShow ppt, List<BiFinding> findings) {
        List<List<BiFinding>> pages = chunk(findings, MAX_TABLE_ROWS);
        for (int i = 0; i < pages.size(); i++) {
            XSLFSlide slide = ppt.createSlide();
            sectionHeader(slide, "DIỄN BIẾN THEO ĐỐI THỦ & LỊCH SẮP TỚI" + pageSuffix(i, pages.size()));
            XSLFTable table = slide.createTable();
            table.setAnchor(new Rectangle2D.Double(56, 90, 848, 400));
            XSLFTableRow header = table.addRow();
            header.setHeight(28);
            addCell(header, "Đối thủ", true, BLACK, WHITE);
            addCell(header, "Diễn biến", true, BLACK, WHITE);
            addCell(header, "Nguồn", true, BLACK, WHITE);
            for (BiFinding f : pages.get(i)) {
                XSLFTableRow row = table.addRow();
                row.setHeight(30);
                addCell(row, f.subjectKey() != null && !f.subjectKey().isBlank() ? f.subjectKey() : "-", false, WHITE, TEXT_DARK);
                addCell(row, truncate(f.textVi(), TABLE_NOTE_MAX_CHARS), false, WHITE, TEXT_DARK);
                addCell(row, citationLabel(f), false, WHITE, TEXT_DARK);
            }
            setColumnWidths(table, 160, 528, 160);
        }
    }

    private void comparisonSlides(XMLSlideShow ppt, List<BiFinding> findings) {
        Map<String, List<BiFinding>> groups = new LinkedHashMap<>();
        for (BiFinding f : findings) {
            String key = f.subjectKey() == null || f.subjectKey().isBlank()
                    ? "Không rõ cặp so sánh" : BiReportPageBuilder.displayLabel(f.subjectKey());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(f);
        }
        for (var entry : groups.entrySet()) {
            String headerTitle = "SO SÁNH CHIẾN LƯỢC · " + entry.getKey().toUpperCase(Locale.ROOT);
            List<List<BiFinding>> pages = paginate(entry.getValue(), BiFinding::textVi, CARD_BODY_W, 13, CARD_BODY_H);
            for (int i = 0; i < pages.size(); i++) {
                XSLFSlide slide = ppt.createSlide();
                sectionHeader(slide, headerTitle + pageSuffix(i, pages.size()));
                roundedCard(slide, new Rectangle2D.Double(56, 90, 848, 400), RED);
                headerPill(slide, new Rectangle2D.Double(76, 100, 260, 26), "Góc nhìn của chúng tôi", RED);
                XSLFTextBox body = slide.createTextBox();
                body.setAnchor(new Rectangle2D.Double(90, 140, CARD_BODY_W, CARD_BODY_H));
                for (BiFinding f : pages.get(i)) {
                    XSLFTextParagraph p = body.addNewTextParagraph();
                    p.setBullet(true);
                    p.setSpaceBefore(10.0);
                    XSLFTextRun r = p.addNewTextRun();
                    r.setText(f.textVi());
                    r.setFontSize(13.0);
                    r.setFontColor(TEXT_DARK);
                }
            }
        }
    }

    private void sourcesSlides(XMLSlideShow ppt, BiReportContent content) {
        if (content.sourceLines().isEmpty()) {
            XSLFSlide slide = ppt.createSlide();
            sectionHeader(slide, "NGUỒN & PHƯƠNG PHÁP");
            addText(slide, new Rectangle2D.Double(56, 90, 848, 40), "Chưa có nguồn nào trong kỳ này.",
                    12, false, TEXT_DARK, TextParagraph.TextAlign.LEFT);
        } else {
            bulletCardSlides(ppt, "NGUỒN & PHƯƠNG PHÁP", "Nguồn đã dùng", GREY_HEADER,
                    content.sourceLines(), 12);
        }
        if (!content.openGaps().isEmpty()) {
            bulletCardSlides(ppt, "KHOẢNG TRỐNG DỮ LIỆU", "Cần lưu ý", GREY_HEADER, content.openGaps(), 12);
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
