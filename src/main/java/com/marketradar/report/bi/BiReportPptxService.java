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

/**
 * 2026-08-03 (feedback: mẫu thật là 1 slide deck ngang kiểu Techcom Life — bảng/thẻ/badge, khác
 * hẳn báo cáo dọc kiểu narrative của bi-report.html): xuất CÙNG {@link BiReportContent} (dùng
 * chung cho cả report định kỳ và Deep Research, y hệt {@link BiReportDocxService}) ra .pptx thật,
 * mở/sửa được trực tiếp trong PowerPoint — không phải PDF/HTML giả dạng slide.
 *
 * Chỉ dựng slide từ những field BiFinding THẬT SỰ có (text/metricPercent/severity/subjectKey) —
 * KHÔNG bịa lịch Gantt theo tháng hay thẻ KPI từ việc scrape số trong văn bản tự do: những phần đó
 * của mẫu cần field cấu trúc mới (vd ngày sự kiện) chưa tồn tại, để dành cho một đợt sau nếu cần.
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

    public byte[] render(BiReportContent content) {
        try (XMLSlideShow ppt = new XMLSlideShow()) {
            ppt.setPageSize(new Dimension((int) PAGE_W, (int) PAGE_H));

            coverSlide(ppt, content);

            List<BiFinding> highlights = content.findings().stream().filter(BiFinding::highlight).toList();
            if (!highlights.isEmpty()) executiveTakeawaySlide(ppt, highlights);

            List<BiFinding> background = byBucket(content, BiFinding.MACRO_ECONOMIC, BiFinding.COMPETITIVE_THEME);
            if (!background.isEmpty()) industryBackgroundSlide(ppt, background);

            List<BiFinding> marketShare = byBucket(content, BiFinding.MARKET_SHARE_OR_AWARD);
            if (!marketShare.isEmpty()) marketShareSlide(ppt, marketShare);

            List<BiFinding> aiThreat = byBucket(content, BiFinding.TECH_AI_SIGNAL).stream()
                    .filter(f -> f.severity() != null).toList();
            if (!aiThreat.isEmpty()) threatMapSlide(ppt, aiThreat);

            List<BiFinding> events = byBucket(content, BiFinding.COMPANY_EVENT, BiFinding.SCHEDULED_EVENT);
            if (!events.isEmpty()) eventsSlide(ppt, events);

            List<BiFinding> comparison = byBucket(content, BiFinding.STRATEGIC_COMPARISON);
            if (!comparison.isEmpty()) comparisonSlides(ppt, comparison);

            sourcesSlide(ppt, content);

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

    private void executiveTakeawaySlide(XMLSlideShow ppt, List<BiFinding> highlights) {
        XSLFSlide slide = ppt.createSlide();
        sectionHeader(slide, "TÓM TẮT ĐIỀU HÀNH");
        roundedCard(slide, new Rectangle2D.Double(56, 90, 848, 400), RED);
        headerPill(slide, new Rectangle2D.Double(76, 100, 220, 26), "Nhận định chính", RED);
        XSLFTextBox body = slide.createTextBox();
        body.setAnchor(new Rectangle2D.Double(90, 140, 800, 330));
        for (BiFinding f : highlights) {
            XSLFTextParagraph p = body.addNewTextParagraph();
            p.setBullet(true);
            p.setSpaceBefore(10.0);
            XSLFTextRun r = p.addNewTextRun();
            r.setText(f.textVi());
            r.setFontSize(14.0);
            r.setFontColor(TEXT_DARK);
        }
    }

    private void industryBackgroundSlide(XMLSlideShow ppt, List<BiFinding> findings) {
        XSLFSlide slide = ppt.createSlide();
        sectionHeader(slide, "VĨ MÔ & XU HƯỚNG CẠNH TRANH");
        roundedCard(slide, new Rectangle2D.Double(56, 90, 848, 400), GREY_HEADER);
        headerPill(slide, new Rectangle2D.Double(76, 100, 220, 26), "Bối cảnh ngành", GREY_HEADER);
        XSLFTextBox body = slide.createTextBox();
        body.setAnchor(new Rectangle2D.Double(90, 140, 800, 330));
        for (BiFinding f : findings) {
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

    private void marketShareSlide(XMLSlideShow ppt, List<BiFinding> findings) {
        XSLFSlide slide = ppt.createSlide();
        sectionHeader(slide, "THỊ PHẦN / GIẢI THƯỞNG");
        XSLFTable table = slide.createTable();
        table.setAnchor(new Rectangle2D.Double(56, 90, 848, 400));
        XSLFTableRow header = table.addRow();
        header.setHeight(28);
        addCell(header, "Chủ thể", true, BLACK, WHITE);
        addCell(header, "Số liệu", true, BLACK, WHITE);
        addCell(header, "Ghi chú", true, BLACK, WHITE);
        for (BiFinding f : findings) {
            XSLFTableRow row = table.addRow();
            row.setHeight(30);
            addCell(row, f.subjectKey() != null && !f.subjectKey().isBlank() ? f.subjectKey() : "-", false, WHITE, TEXT_DARK);
            addCell(row, f.metricPercent() != null ? f.metricPercent() + "%" : "—", false, WHITE, TEXT_DARK);
            addCell(row, truncate(f.textVi(), 160), false, WHITE, TEXT_DARK);
        }
        setColumnWidths(table, 220, 100, 528);
    }

    private void threatMapSlide(XMLSlideShow ppt, List<BiFinding> findings) {
        XSLFSlide slide = ppt.createSlide();
        sectionHeader(slide, "BẢN ĐỒ RỦI RO AI THEO ĐỐI THỦ");
        XSLFTable table = slide.createTable();
        table.setAnchor(new Rectangle2D.Double(56, 90, 848, 400));
        XSLFTableRow header = table.addRow();
        header.setHeight(28);
        addCell(header, "Mức độ", true, BLACK, WHITE);
        addCell(header, "Đối thủ", true, BLACK, WHITE);
        addCell(header, "Vì sao", true, BLACK, WHITE);
        for (BiFinding f : findings) {
            XSLFTableRow row = table.addRow();
            row.setHeight(30);
            Color[] badge = badgeColors(f.severity());
            addCell(row, f.severity(), true, badge[0], badge[1]);
            addCell(row, f.subjectKey() != null && !f.subjectKey().isBlank() ? f.subjectKey() : "-", false, WHITE, TEXT_DARK);
            addCell(row, truncate(f.textVi(), 160), false, WHITE, TEXT_DARK);
        }
        setColumnWidths(table, 120, 160, 568);
    }

    private void eventsSlide(XMLSlideShow ppt, List<BiFinding> findings) {
        XSLFSlide slide = ppt.createSlide();
        sectionHeader(slide, "DIỄN BIẾN THEO ĐỐI THỦ & LỊCH SẮP TỚI");
        XSLFTable table = slide.createTable();
        table.setAnchor(new Rectangle2D.Double(56, 90, 848, 400));
        XSLFTableRow header = table.addRow();
        header.setHeight(28);
        addCell(header, "Đối thủ", true, BLACK, WHITE);
        addCell(header, "Diễn biến", true, BLACK, WHITE);
        addCell(header, "Nguồn", true, BLACK, WHITE);
        for (BiFinding f : findings) {
            XSLFTableRow row = table.addRow();
            row.setHeight(30);
            addCell(row, f.subjectKey() != null && !f.subjectKey().isBlank() ? f.subjectKey() : "-", false, WHITE, TEXT_DARK);
            addCell(row, truncate(f.textVi(), 160), false, WHITE, TEXT_DARK);
            addCell(row, citationLabel(f), false, WHITE, TEXT_DARK);
        }
        setColumnWidths(table, 160, 528, 160);
    }

    private void comparisonSlides(XMLSlideShow ppt, List<BiFinding> findings) {
        Map<String, List<BiFinding>> groups = new LinkedHashMap<>();
        for (BiFinding f : findings) {
            String key = f.subjectKey() == null || f.subjectKey().isBlank()
                    ? "Không rõ cặp so sánh" : BiReportPageBuilder.displayLabel(f.subjectKey());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(f);
        }
        for (var entry : groups.entrySet()) {
            XSLFSlide slide = ppt.createSlide();
            sectionHeader(slide, "SO SÁNH CHIẾN LƯỢC · " + entry.getKey().toUpperCase(Locale.ROOT));
            roundedCard(slide, new Rectangle2D.Double(56, 90, 848, 400), RED);
            headerPill(slide, new Rectangle2D.Double(76, 100, 260, 26), "Góc nhìn của chúng tôi", RED);
            XSLFTextBox body = slide.createTextBox();
            body.setAnchor(new Rectangle2D.Double(90, 140, 800, 330));
            for (BiFinding f : entry.getValue()) {
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

    private void sourcesSlide(XMLSlideShow ppt, BiReportContent content) {
        XSLFSlide slide = ppt.createSlide();
        sectionHeader(slide, "NGUỒN & PHƯƠNG PHÁP");
        XSLFTextBox body = slide.createTextBox();
        body.setAnchor(new Rectangle2D.Double(56, 90, 848, 300));
        if (content.sourceLines().isEmpty()) {
            XSLFTextParagraph p = body.addNewTextParagraph();
            XSLFTextRun r = p.addNewTextRun();
            r.setText("Chưa có nguồn nào trong kỳ này.");
            r.setItalic(true);
            r.setFontSize(12.0);
            r.setFontColor(TEXT_DARK);
        } else {
            for (String line : content.sourceLines()) {
                XSLFTextParagraph p = body.addNewTextParagraph();
                p.setBullet(true);
                XSLFTextRun r = p.addNewTextRun();
                r.setText(line);
                r.setFontSize(12.0);
                r.setFontColor(TEXT_DARK);
            }
        }
        if (!content.openGaps().isEmpty()) {
            XSLFTextBox gaps = slide.createTextBox();
            gaps.setAnchor(new Rectangle2D.Double(56, 410, 848, 110));
            XSLFTextParagraph title = gaps.addNewTextParagraph();
            XSLFTextRun titleRun = title.addNewTextRun();
            titleRun.setText("Khoảng trống dữ liệu:");
            titleRun.setBold(true);
            titleRun.setFontSize(12.0);
            titleRun.setFontColor(GREY_HEADER);
            for (String gap : content.openGaps()) {
                XSLFTextParagraph p = gaps.addNewTextParagraph();
                XSLFTextRun r = p.addNewTextRun();
                r.setText("- " + truncate(gap, 220));
                r.setFontSize(10.0);
                r.setFontColor(GREY_HEADER);
            }
        }
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
