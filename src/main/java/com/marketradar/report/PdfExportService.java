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
            """;

    private final SpringTemplateEngine templateEngine;

    public PdfExportService(SpringTemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    /** @param model đúng model của trang HTML — bảo đảm PDF và web luôn khớp nhau
     *  @param locale Batch 7 (i18n): cùng locale đã dùng để build model, để #{...} và
     *                #temporals trong template resolve đúng ngôn ngữ trong PDF */
    public byte[] renderWeeklyReportPdf(Map<String, Object> model, Locale locale) {
        Context ctx = new Context(locale, model);
        String html = templateEngine.process("weekly-report", ctx);

        // HTML5 → XHTML well-formed + chèn CSS override cho PDF
        Document jdoc = Jsoup.parse(html);
        // Batch 6: bỏ <link> Google Fonts trước khi render — PDF luôn ép DejaVu Sans/Mono
        // qua override dưới đây nên không cần font ngoài; giữ lại link sẽ khiến
        // OpenHTMLtoPDF cố fetch mạng ngoài không cần thiết (rủi ro treo/lỗi khi offline).
        jdoc.select("head link").remove();
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
