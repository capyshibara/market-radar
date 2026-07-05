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

    private static final String PDF_OVERRIDE_CSS = """
            @page { size: %fmm %fmm; margin: 0; }
            body, h1, h2, h3, h4, p, li, td, th, span, div, b, i, a, strong
              { font-family:'DejaVu Sans', sans-serif !important; }
            .kicker, .stat-label, .src
              { font-family:'DejaVu Sans Mono', monospace !important; }
            """.formatted(PAGE_WIDTH_MM, PAGE_HEIGHT_MM);

    private final SpringTemplateEngine templateEngine;

    public EmailPngExportService(SpringTemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    /** @param model đúng model của trang HTML/PDF — email PNG là bản tóm tắt của cùng dữ liệu */
    public byte[] renderWeeklySummaryPng(Map<String, Object> model) {
        Context ctx = new Context(Locale.forLanguageTag("vi"), model);
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
