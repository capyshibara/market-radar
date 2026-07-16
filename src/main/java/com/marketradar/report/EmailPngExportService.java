package com.marketradar.report;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Locale;
import java.util.Map;

/**
 * Email PNG export: renders the compact "email-summary" template through the
 * same Thymeleaf -> jsoup/W3CDom -> OpenHTMLtoPDF pipeline as PdfExportService,
 * then rasterizes the single resulting page to PNG with PDFBox's PDFRenderer
 * (already a transitive dep of pdfbox-core, no new library needed). PNG instead
 * of live HTML because target is embedding in an email body, where client CSS
 * support is unreliable.
 *
 * Content height varies with the number/length of exec-summary claims, so the
 * PDF page is rendered tall (with margin to spare) and the PNG is then cropped
 * to the actual content bottom, rather than guessing a fixed page height.
 */
@Service
public class EmailPngExportService {

    private static final float PAGE_WIDTH_MM = 169.33f;  // 640px @ 96dpi
    private static final float PAGE_HEIGHT_MM = 400f;     // generous ceiling; real content is auto-cropped
    private static final float RENDER_DPI = 192f;         // 2x for a crisp embed
    private static final int CROP_BOTTOM_PADDING_PX = 0;  // content already ends with its own legal-line padding

    /** Batch 7 (i18n): kicker/stat-label/src đều có thể mang text tiếng Việt có dấu tuỳ
     * locale (tên nguồn, nhãn) — KHÔNG ép Mono cho các lớp này (DejaVu Sans Mono thiếu
     * glyph một số dấu tiếng Việt); chỉ toàn bộ dùng DejaVu Sans để bảo đảm hiển thị đúng. */
    private static final String PDF_OVERRIDE_CSS = """
            @page { size: %fmm %fmm; margin: 0; }
            body, h1, h2, h3, h4, p, li, td, th, span, div, b, i, a, strong
              { font-family:'DejaVu Sans', sans-serif !important; }
            .report-page { background:#fffdfa !important; color:#172033 !important; }
            .report-header { border-top:6px solid #d7192d !important; }
            .report-header h1, .section-head h2, .priority-card h3,
            .insight-card h3, .current-news-item h3 { color:#172b4d !important; }
            .eyebrow, .section-kicker, .priority-number, .section-stat strong,
            .reference-code { color:#a40f20 !important; }
            .report-meta, .meta-cell, .report-section, .priority-card,
            .insight-card, .reference-row { border-color:#dfe3e8 !important; }
            .trust-strip { border:1px solid #c8ced7 !important;
                           border-left:4px solid #96600d !important; background:#fff6df !important; }
            .trust-strip.watch-mode { border-left-color:#315f91 !important; background:#edf4fa !important; }
            .trust-strip.ready-mode { border-left-color:#176447 !important; background:#eaf6f0 !important; }
            .priority-card { border:1px solid #dfe3e8 !important; background:#ffffff !important; }
            .action-horizons, .news-ledger, .reference-register { border-top:2px solid #172033 !important; }
            .topic-heading { background:#172b4d !important; color:#ffffff !important; }
            .topic-heading h3, .topic-heading p, .topic-heading strong { color:#ffffff !important; }
            .evidence-quote { border-left:3px solid #315f91 !important; color:#3f4a5f !important; }
            .meta-chip { background:#edf4fa !important; color:#315f91 !important; }
            .report-footer { background:#172b4d !important; color:#dfe7f2 !important; }
            .report-footer .footer-title { color:#ffffff !important; }
            """.formatted(PAGE_WIDTH_MM, PAGE_HEIGHT_MM);

    private final SpringTemplateEngine templateEngine;

    public EmailPngExportService(SpringTemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    /** @param model đúng model của trang HTML/PDF — email PNG là bản tóm tắt của cùng dữ liệu
     *  @param locale Batch 7 (i18n): cùng locale đã dùng để build model */
    public byte[] renderWeeklySummaryPng(Map<String, Object> model, Locale locale) {
        Context ctx = new Context(locale, model);
        String html = templateEngine.process("email-summary", ctx);

        Document jdoc = Jsoup.parse(html);
        jdoc.select("head link").remove();
        jdoc.head().appendElement("style").text(PDF_OVERRIDE_CSS);
        jdoc.outputSettings().syntax(Document.OutputSettings.Syntax.xml);
        org.w3c.dom.Document w3c = new W3CDom().fromJsoup(jdoc);

        try (ByteArrayOutputStream pdfOut = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.useFont(() -> EmailPngExportService.class.getResourceAsStream(
                    "/fonts/DejaVuSans.ttf"), "DejaVu Sans", 400,
                    PdfRendererBuilder.FontStyle.NORMAL, true);
            builder.useFont(() -> EmailPngExportService.class.getResourceAsStream(
                    "/fonts/DejaVuSans-Bold.ttf"), "DejaVu Sans", 700,
                    PdfRendererBuilder.FontStyle.NORMAL, true);
            builder.useFont(() -> EmailPngExportService.class.getResourceAsStream(
                    "/fonts/DejaVuSansMono.ttf"), "DejaVu Sans Mono", 400,
                    PdfRendererBuilder.FontStyle.NORMAL, true);
            builder.withW3cDocument(w3c, "/");
            builder.toStream(pdfOut);
            builder.run();

            try (PDDocument pdf = PDDocument.load(pdfOut.toByteArray())) {
                PDFRenderer renderer = new PDFRenderer(pdf);
                BufferedImage image = renderer.renderImageWithDPI(0, RENDER_DPI, ImageType.RGB);
                BufferedImage cropped = cropToContent(image);
                try (ByteArrayOutputStream pngOut = new ByteArrayOutputStream()) {
                    ImageIO.write(cropped, "png", pngOut);
                    return pngOut.toByteArray();
                }
            }
        } catch (Exception e) {
            // Fail loud: lỗi render PNG phải hiện rõ, không trả file rỗng
            throw new IllegalStateException("Render Email PNG thất bại: " + e.getMessage(), e);
        }
    }

    /** Trims the generous-height render down to the last non-white pixel row (the template's own white body/margin). */
    private static BufferedImage cropToContent(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int lastNonWhiteRow = 0;
        outer:
        for (int y = height - 1; y >= 0; y--) {
            for (int x = 0; x < width; x++) {
                if ((image.getRGB(x, y) & 0xFFFFFF) != 0xFFFFFF) {
                    lastNonWhiteRow = y;
                    break outer;
                }
            }
        }
        int cropHeight = Math.min(height, lastNonWhiteRow + 1 + CROP_BOTTOM_PADDING_PX);
        return image.getSubimage(0, 0, width, cropHeight);
    }
}
