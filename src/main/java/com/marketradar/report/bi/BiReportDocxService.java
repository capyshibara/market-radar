package com.marketradar.report.bi;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Xuất BiReportContent ra .docx — dùng chung cho cả 2 nguồn (report định kỳ bản BI, và Deep
 * Research). Bố cục đơn giản hơn PDF (không cần layout "tạp chí" 1056x816px): tiêu đề bucket +
 * từng finding + trích dẫn, đọc tuyến tính từ trên xuống — hợp với việc dán vào tài liệu khác.
 */
@Service
public class BiReportDocxService {

    private static final Map<String, String> BUCKET_LABEL_VI = Map.of(
            BiFinding.MACRO_ECONOMIC, "Vĩ mô ngành",
            BiFinding.COMPETITIVE_THEME, "Xu hướng cạnh tranh",
            BiFinding.SCHEDULED_EVENT, "Lịch sắp tới",
            BiFinding.COMPANY_EVENT, "Diễn biến theo công ty",
            BiFinding.MARKET_SHARE_OR_AWARD, "Thị phần / Giải thưởng",
            BiFinding.TECH_AI_SIGNAL, "Tín hiệu Tech/AI",
            BiFinding.STRATEGIC_COMPARISON, "So sánh chiến lược");
    private static final Map<String, String> BUCKET_LABEL_EN = Map.of(
            BiFinding.MACRO_ECONOMIC, "Macro update",
            BiFinding.COMPETITIVE_THEME, "Competitive themes",
            BiFinding.SCHEDULED_EVENT, "Upcoming calendar",
            BiFinding.COMPANY_EVENT, "Company developments",
            BiFinding.MARKET_SHARE_OR_AWARD, "Market share / Awards",
            BiFinding.TECH_AI_SIGNAL, "Technology / AI signals",
            BiFinding.STRATEGIC_COMPARISON, "Strategic comparisons");

    public byte[] render(BiReportContent content) {
        return render(content, true);
    }

    public byte[] render(BiReportContent content, boolean vi) {
        try (XWPFDocument doc = new XWPFDocument()) {
            titleRun(doc, content.title(), 20, true, "0E1B6B");

            XWPFRun metaRun = doc.createParagraph().createRun();
            metaRun.setText((vi ? "Kỳ: " : "Period: ") + content.period()
                    + (vi ? "  ·  Tạo lúc " : "  ·  Generated ") + content.generatedAt()
                    + (content.homeCompany() != null && !content.homeCompany().isBlank()
                        ? (vi ? "  ·  Chuẩn bị cho: " : "  ·  Prepared for: ") + content.homeCompany() : ""));
            metaRun.setFontSize(10);
            metaRun.setColor("4A4A45");
            doc.createParagraph();

            if (content.findings().isEmpty()) {
                XWPFRun emptyRun = doc.createParagraph().createRun();
                emptyRun.setText(vi ? "Chưa có nhận định nào đủ căn cứ trong kỳ này."
                        : "No sufficiently grounded finding is available for this period.");
                emptyRun.setItalic(true);
            }

            Map<String, List<BiFinding>> byBucket = new LinkedHashMap<>();
            Map<String, String> labels = vi ? BUCKET_LABEL_VI : BUCKET_LABEL_EN;
            for (String bucket : labels.keySet()) {
                List<BiFinding> matched = content.findings().stream()
                        .filter(f -> f.bucket().equals(bucket)).toList();
                if (!matched.isEmpty()) byBucket.put(bucket, matched);
            }

            for (var entry : byBucket.entrySet()) {
                titleRun(doc, labels.getOrDefault(entry.getKey(), entry.getKey()), 15, true, "2647E8");
                for (BiFinding f : entry.getValue()) {
                    XWPFParagraph p = doc.createParagraph();
                    p.setSpacingBefore(120);
                    XWPFRun body = p.createRun();
                    if (f.subjectKey() != null && !f.subjectKey().isBlank()) {
                        body.setText(f.subjectKey() + " — ");
                        body.setBold(true);
                    }
                    XWPFRun textRun = p.createRun();
                    textRun.setText(f.text(vi));

                    if (!f.citations().isEmpty()) {
                        XWPFRun citeRun = doc.createParagraph().createRun();
                        StringBuilder cites = new StringBuilder(vi ? "Nguồn: " : "Sources: ");
                        for (int i = 0; i < f.citations().size(); i++) {
                            BiCitation c = f.citations().get(i);
                            if (i > 0) cites.append("; ");
                            cites.append(c.label());
                            if (c.tierNote() != null && !c.tierNote().isBlank()) {
                                cites.append(" (").append(c.tierNote()).append(')');
                            }
                        }
                        citeRun.setText(cites.toString());
                        citeRun.setFontSize(9);
                        citeRun.setColor("8A8878");
                        citeRun.setItalic(true);
                    }
                }
                doc.createParagraph();
            }

            if (!content.openGaps().isEmpty()) {
                titleRun(doc, vi ? "Khoảng trống dữ liệu đã ghi nhận" : "Recorded evidence gaps",
                        13, true, "8A8878");
                for (String gap : content.openGaps()) {
                    XWPFRun r = doc.createParagraph().createRun();
                    r.setText("- " + gap);
                    r.setFontSize(10);
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Xuất BI report docx thất bại: " + e.getMessage(), e);
        }
    }

    private static void titleRun(XWPFDocument doc, String text, int size, boolean bold, String color) {
        XWPFRun run = doc.createParagraph().createRun();
        run.setText(text);
        run.setBold(bold);
        run.setFontSize(size);
        run.setColor(color);
    }
}
