package vn.techcomlife.marketradar.report;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.io.ByteArrayOutputStream;
import java.util.Locale;
import java.util.Map;

/**
 * PDF export (Batch 5, bước 10 — polish): render CHÍNH template weekly-report
 * (một nguồn sự thật cho nội dung report) → chuẩn hoá XHTML bằng jsoup W3CDom
 * (Thymeleaf xuất HTML5, OpenHTMLtoPDF đòi XML well-formed) → PDF.
 *
 * Font: PDF base-14 (Helvetica…) KHÔNG phủ dấu tiếng Việt → embed DejaVu Sans
 * (bundle trong resources/fonts, license Bitstream Vera — dùng tự do) và ép
 * font-family qua CSS override chỉ áp cho bản PDF. Bản HTML giữ nguyên style cũ.
 */
@Service
public class PdfExportService {

    /** CSS CHỈ cho bản PDF — nối thêm vào <head>, không đụng template gốc. */
    private static final String PDF_OVERRIDE_CSS = """
            @page { size: A4; margin: 16mm 15mm; }
            body { background:#fff !important; }
            body, h1, h2, h3, h4, p, li, td, th, span, div, b, i, a
              { font-family:'DejaVu Sans', sans-serif !important; }
            .cite, .code, .span-orig .lang, pre, code
              { font-family:'DejaVu Sans Mono', monospace !important; }
            .page { box-shadow:none !important; margin:0 !important;
                    max-width:100% !important; padding:0 !important; }
            .no-print { display:none !important; }
            section { page-break-inside:auto; }
            table { page-break-inside:auto; }
            tr, .fact-block, .ai-block { page-break-inside:avoid; }
            """;

    private final SpringTemplateEngine templateEngine;

    public PdfExportService(SpringTemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    /** @param model đúng model của trang HTML — bảo đảm PDF và web luôn khớp nhau */
    public byte[] renderWeeklyReportPdf(Map<String, Object> model) {
        Context ctx = new Context(Locale.forLanguageTag("vi"), model);
        String html = templateEngine.process("weekly-report", ctx);

        // HTML5 → XHTML well-formed + chèn CSS override cho PDF
        Document jdoc = Jsoup.parse(html);
        jdoc.head().appendElement("style").text(PDF_OVERRIDE_CSS);
        jdoc.outputSettings().syntax(Document.OutputSettings.Syntax.xml);
        org.w3c.dom.Document w3c = new W3CDom().fromJsoup(jdoc);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.useFont(() -> PdfExportService.class.getResourceAsStream(
                    "/fonts/DejaVuSans.ttf"), "DejaVu Sans", 400,
                    PdfRendererBuilder.FontStyle.NORMAL, true);
            builder.useFont(() -> PdfExportService.class.getResourceAsStream(
                    "/fonts/DejaVuSans-Bold.ttf"), "DejaVu Sans", 700,
                    PdfRendererBuilder.FontStyle.NORMAL, true);
            builder.useFont(() -> PdfExportService.class.getResourceAsStream(
                    "/fonts/DejaVuSansMono.ttf"), "DejaVu Sans Mono", 400,
                    PdfRendererBuilder.FontStyle.NORMAL, true);
            builder.withW3cDocument(w3c, "/");
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (Exception e) {
            // Fail loud: lỗi render PDF phải hiện rõ, không trả file rỗng
            throw new IllegalStateException("Render PDF thất bại: " + e.getMessage(), e);
        }
    }
}
