package com.marketradar.report;

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

    /**
     * CSS CHỈ cho bản PDF — nối thêm vào <head>, không đụng template gốc.
     * Batch 6 (report redesign): mở rộng selector font cho các thẻ mới
     * (blockquote/details/summary/em/strong...) và ép TẤT CẢ <details> hiện toàn bộ
     * nội dung — PDF không có JS/tương tác nên không thể "bấm mở rộng"; ẩn nội dung
     * theo trạng thái collapsed sẽ làm mất dữ liệu (kể cả Phụ lục) trong bản in.
     */
    private static final String PDF_OVERRIDE_CSS = """
            @page { size: A4; margin: 16mm 15mm; }
            body { background:#fff !important; }
            body, h1, h2, h3, h4, p, li, td, th, span, div, b, i, a, blockquote, summary, em, strong
              { font-family:'DejaVu Sans', sans-serif !important; }
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
            .report-page { background:#fffdfa !important; color:#172033 !important; }
            .report-header { border-top:6px solid #d7192d !important; }
            .report-header h1, .section-head h2, .priority-card h3,
            .insight-card h3, .current-news-item h3, .footer-title
              { color:#172b4d !important; }
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

    /**
     * Shared renderer for editorial exports. Callers supply a Thymeleaf template and any
     * print-only CSS; network resources are still stripped and Vietnamese-capable fonts embedded.
     */
    public byte[] render(String template, Map<String, Object> model, Locale locale, String printCss) {
        Context ctx = new Context(locale, model);
        String html = templateEngine.process(template, ctx);

        // HTML5 → XHTML well-formed + chèn CSS override cho PDF
        Document jdoc = Jsoup.parse(html);
        // Batch 6: bỏ <link> Google Fonts trước khi render — PDF luôn ép DejaVu Sans/Mono
        // qua override dưới đây nên không cần font ngoài; giữ lại link sẽ khiến
        // OpenHTMLtoPDF cố fetch mạng ngoài không cần thiết (rủi ro treo/lỗi khi offline).
        jdoc.select("head link").remove();
        jdoc.head().appendElement("style").text(PDF_OVERRIDE_CSS + "\n" + (printCss == null ? "" : printCss));
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
