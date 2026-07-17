package com.marketradar.report;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Base64;
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

    /**
     * CSS CHỈ cho bản PDF — nối thêm vào <head>, không đụng template gốc.
     * Batch 6 (report redesign): mở rộng selector font cho các thẻ mới
     * (blockquote/details/summary/em/strong...) và ép TẤT CẢ <details> hiện toàn bộ
     * nội dung — PDF không có JS/tương tác nên không thể "bấm mở rộng"; ẩn nội dung
     * theo trạng thái collapsed sẽ làm mất dữ liệu (kể cả Phụ lục) trong bản in.
     */
    private static final String PDF_OVERRIDE_CSS = """
            @page { size: letter landscape; margin: 11mm 12mm; }
            body { background:#F8F6F1 !important; }
            body, p, li, td, th, span, div, b, i, a, summary, em, strong
              { font-family:'Work Sans', 'DejaVu Sans', sans-serif !important; }
            h1, h2, h3, h4, .report-deck, .meta-value.accent,
            .section-stat strong, .footer-title
              { font-family:'Libre Caslon Text', serif !important; }
            .locale-vi h1, .locale-vi h2, .locale-vi h3, .locale-vi h4,
            .locale-vi .report-deck, .locale-vi .meta-value.accent,
            .locale-vi .section-stat strong, .locale-vi .footer-title
              { font-family:'Lora', serif !important; }
            /* Batch 7 (i18n): .cite-pill hiển thị TÊN NGUỒN thật (có dấu tiếng Việt) —
             * KHÔNG được ép Mono ở đây (từng gây lỗi hiển thị, xem EmailPngExportService).
             * Chỉ ép Mono cho nội dung chắc chắn ascii: tier-dot (chữ số), mã ngôn ngữ 2 ký tự. */
            .tier-dot, .code, .span-orig .lang, .orig-span .lang, pre, code
              { font-family:'DejaVu Sans Mono', monospace !important; }
            .page { box-shadow:none !important; margin:0 !important;
                    max-width:100% !important; padding:0 !important; }
            .no-print { display:none !important; }
            section { page-break-inside:auto; }
            table { page-break-inside:auto; }
            tr, .fact-block, .ai-block, .article, .card, .tl-item { page-break-inside:avoid; }
            /* details/summary không có JS trong PDF — luôn hiện toàn bộ, bỏ tam giác disclosure */
            details, details > * { display:block !important; }
            summary { list-style:none !important; }
            summary::marker { content:'' !important; }
            /* OpenHTMLtoPDF does not resolve CSS custom properties. Re-state the
             * editorial palette with concrete values for a faithful export. */
            .report-page { background:#F8F6F1 !important; color:#1A1A18 !important; }
            .report-header { border-top:7px solid #0E1B6B !important; }
            .report-header h1, .section-head h2, .editorial-lead h2,
            .editorial-chart h3, .editorial-takeaway h3, .decision-card h3,
            .insight-card h3, .current-news-item h3, .footer-title
              { color:#0E1B6B !important; }
            .eyebrow, .section-kicker, .section-stat strong,
            .reference-code { color:#1E38B6 !important; }
            .report-meta, .meta-cell, .report-section,
            .insight-card, .reference-row { border-color:#E3DFD4 !important; }
            .trust-strip { border:1px solid #CFCABB !important;
                           border-left:4px solid #96600D !important; background:#F7F0DD !important; }
            .trust-strip.watch-mode { border-left-color:#2647E8 !important; background:#EBEEFC !important; }
            .trust-strip.ready-mode { border-left-color:#2F6B3D !important; background:#EAF1EA !important; }
            /* OpenHTMLtoPDF is CSS2-oriented: restate the editorial grids as
               tables so the PDF preserves the browser's multi-column hierarchy. */
            .editorial-lead-grid, .editorial-takeaways, .decision-grid { display:table !important; width:100% !important; table-layout:fixed !important; }
            .editorial-lead, .editorial-chart, .editorial-takeaway, .decision-card { display:table-cell !important; vertical-align:top !important; }
            .editorial-lead { width:64% !important; padding-right:10mm !important; }
            .editorial-chart { width:36% !important; background:#F1EEE5 !important; border-top:3px solid #2647E8 !important; }
            .compare-track { background:#D9D5CA !important; }
            .compare-bar.first { background:#2647E8 !important; }
            .compare-bar.second { background:#00BFA6 !important; }
            .editorial-takeaways { border-top:2px solid #0E1B6B !important; border-bottom:1px solid #E3DFD4 !important; }
            .editorial-takeaway { width:33.333% !important; border-left:1px solid #E3DFD4 !important; padding:5mm !important; }
            .editorial-takeaway:first-child { border-left:0 !important; padding-left:0 !important; }
            .takeaway-rule span { background:#2647E8 !important; color:#ffffff !important; }
            .editorial-takeaway:nth-child(2) .takeaway-rule span { background:#00BFA6 !important; }
            .editorial-takeaway:nth-child(3) .takeaway-rule span { background:#F5A623 !important; color:#0E1B6B !important; }
            .decision-section { background:#0E1B6B !important; color:#F2EFE8 !important; }
            .decision-section h2, .decision-card h3 { color:#ffffff !important; }
            .decision-section .section-kicker, .decision-horizon { color:#8FA6FF !important; }
            .decision-card { width:33.333% !important; border-left:1px solid #52609A !important; padding:0 5mm !important; }
            .decision-card:first-child { border-left:0 !important; padding-left:0 !important; }
            .decision-card p { color:#D8DEF2 !important; }
            .news-ledger, .reference-register { border-top:2px solid #0E1B6B !important; }
            .topic-heading { background:#0E1B6B !important; color:#ffffff !important; }
            .topic-heading h3, .topic-heading p, .topic-heading strong { color:#ffffff !important; }
            .topic-heading { page-break-after:avoid !important; }
            .news-topic { page-break-before:always !important; }
            .news-topic.first-news-topic { page-break-before:auto !important; }
            .current-news-item { padding:5mm 0 !important; }
            .source-evidence { display:none !important; }
            .evidence-quote { border-left:3px solid #2647E8 !important; color:#3E4147 !important; }
            .evidence-quote { font-family:'Lora', serif !important; }
            .meta-chip { background:#EBEEFC !important; color:#1E38B6 !important; }
            .report-methodology { page-break-before:always !important; page-break-inside:avoid !important; }
            .report-footer { background:#0E1B6B !important; color:#D8DEF2 !important; }
            .report-footer .footer-title { color:#ffffff !important; }
            /* Methodology furniture belongs in the interactive reader. In print it
               can orphan onto a nearly blank page after a long source register. */
            .custody-section, .report-footer { display:none !important; }
            """;

    private final SpringTemplateEngine templateEngine;

    public PdfExportService(SpringTemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    /** @param model đúng model của trang HTML — bảo đảm PDF và web luôn khớp nhau
     *  @param locale Batch 7 (i18n): cùng locale đã dùng để build model, để #{...} và
     *                #temporals trong template resolve đúng ngôn ngữ trong PDF */
    public byte[] renderWeeklyReportPdf(Map<String, Object> model, Locale locale) {
        return render("weekly-report", model, locale, PDF_OVERRIDE_CSS);
    }

    public byte[] renderProductReportPdf(String template, Map<String, Object> model, Locale locale) {
        return render(template, model, locale, PDF_OVERRIDE_CSS);
    }

    /**
     * Shared renderer for editorial exports. Callers supply a Thymeleaf template and any
     * print-only CSS; network resources are still stripped and Vietnamese-capable fonts embedded.
     */
    public byte[] render(String template, Map<String, Object> model, Locale locale, String printCss) {
        Context ctx = new Context(locale, model);
        String html = templateEngine.process(template, ctx);
        // Browser templates may declare URL-based @font-face rules. The PDF renderer is
        // intentionally offline and receives the same families below from classpath streams;
        // remove the URL declarations so a failed web-font lookup cannot shadow them.
        html = html.replaceAll("(?s)@font-face\\s*\\{[^}]*}", "");

        // HTML5 → XHTML well-formed + chèn CSS override cho PDF
        Document jdoc = Jsoup.parse(html);
        inlineClasspathImages(jdoc);
        // Batch 6: bỏ <link> Google Fonts trước khi render — PDF luôn ép DejaVu Sans/Mono
        // qua override dưới đây nên không cần font ngoài; giữ lại link sẽ khiến
        // OpenHTMLtoPDF cố fetch mạng ngoài không cần thiết (rủi ro treo/lỗi khi offline).
        jdoc.select("head link").remove();
        jdoc.head().appendElement("style").text(printCss == null ? "" : printCss);
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
            builder.useFont(() -> PdfExportService.class.getResourceAsStream(
                    "/fonts/LibreCaslonText-Regular.ttf"), "Libre Caslon Text", 400,
                    PdfRendererBuilder.FontStyle.NORMAL, true);
            builder.useFont(() -> PdfExportService.class.getResourceAsStream(
                    "/fonts/LibreCaslonText-Bold.ttf"), "Libre Caslon Text", 700,
                    PdfRendererBuilder.FontStyle.NORMAL, true);
            builder.useFont(() -> PdfExportService.class.getResourceAsStream(
                    "/fonts/LibreCaslonText-Italic.ttf"), "Libre Caslon Text", 400,
                    PdfRendererBuilder.FontStyle.ITALIC, true);
            builder.useFont(() -> PdfExportService.class.getResourceAsStream(
                    "/fonts/Lora-Regular.ttf"), "Lora", 400,
                    PdfRendererBuilder.FontStyle.NORMAL, true);
            builder.useFont(() -> PdfExportService.class.getResourceAsStream(
                    "/fonts/Lora-Bold.ttf"), "Lora", 700,
                    PdfRendererBuilder.FontStyle.NORMAL, true);
            builder.useFont(() -> PdfExportService.class.getResourceAsStream(
                    "/fonts/WorkSans-Regular.ttf"), "Work Sans", 400,
                    PdfRendererBuilder.FontStyle.NORMAL, true);
            builder.useFont(() -> PdfExportService.class.getResourceAsStream(
                    "/fonts/WorkSans-SemiBold.ttf"), "Work Sans", 600,
                    PdfRendererBuilder.FontStyle.NORMAL, true);
            builder.useFont(() -> PdfExportService.class.getResourceAsStream(
                    "/fonts/WorkSans-Bold.ttf"), "Work Sans", 700,
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

    /** Browser assets live under static/. Inline them for the offline PDF renderer. */
    private static void inlineClasspathImages(Document document) {
        for (org.jsoup.nodes.Element image : document.select("img[src^='/assets/']")) {
            String src = image.attr("src");
            String resource = "/static" + src;
            try (InputStream input = PdfExportService.class.getResourceAsStream(resource)) {
                if (input == null) continue;
                String mime = src.endsWith(".jpg") || src.endsWith(".jpeg")
                        ? "image/jpeg" : "image/png";
                image.attr("src", "data:" + mime + ";base64,"
                        + Base64.getEncoder().encodeToString(input.readAllBytes()));
            } catch (Exception ignored) {
                // Decorative image failure must not hide evidence text or break export.
            }
        }
    }
}
