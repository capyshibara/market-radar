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
 *
 * Phase 5: DejaVu Sans không phủ glyph Hán — fact/tên nguồn tiếng Trung (spanLanguage=zh,
 * VD "国家金融监督管理总局 (NFRA)") in ra thành ô vuông "#" trong PDF (bắt được qua render-to-PNG
 * ở Phase 4, không phải giả định). Thêm WenQuanYi Zen Hei làm fallback thứ 2 trong font-family
 * stack — che phủ Hán tự (Giản thể + Phồn thể), license GPL-2 with Font Embedding Exception (cho
 * phép nhúng vào tài liệu/phần mềm phát sinh mà không kéo cả phần mềm thành GPL). Tách riêng từ
 * file .ttc gốc (font collection, PDFBox/OpenHTMLtoPDF không load .ttc trực tiếp qua useFont())
 * bằng fontTools — chỉ lấy đúng 1 font "WenQuanYi Zen Hei" trong 3 font của collection.
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
    /** Phần dùng chung cho MỌI template PDF — font + reset chung. KHÔNG có @page ở đây: kích thước
     * trang khác nhau hẳn giữa weekly-report (flow dài tự nhiên, A4) và bi-report (canvas cố định
     * 1056x816/trang, kiểu tạp chí) — ép chung 1 @page A4 làm bi-report vỡ số trang thật (đã bắt
     * được qua render-to-PNG: 14 div .report-page bị dồn còn 5 trang PDF vì width 1056px tràn khổ
     * A4 có margin). Mỗi template tự khai @page riêng, nối vào SAU phần này. */
    private static final String PDF_BASE_CSS = """
            body { background:#fff !important; }
            /* WenQuanYi Zen Hei làm fallback sau DejaVu Sans — DejaVu phủ Latin/Việt, WQY phủ Hán.
               OpenHTMLtoPDF hỗ trợ font-family fallback theo glyph (không có trong DejaVu thì tự
               tra font kế tiếp trong danh sách), đã verify qua render-to-PNG với fact tiếng Trung. */
            body, h1, h2, h3, h4, p, li, td, th, span, div, b, i, a, blockquote, summary, em, strong
              { font-family:'DejaVu Sans','WenQuanYi Zen Hei', sans-serif !important; }
            .cite, .cite-pill, .tier-dot, .code, .span-orig .lang, .orig-span .lang, pre, code
              { font-family:'DejaVu Sans Mono','WenQuanYi Zen Hei', monospace !important; }
            .no-print { display:none !important; }
            /* details/summary không có JS trong PDF — luôn hiện toàn bộ, bỏ tam giác disclosure */
            details, details > * { display:block !important; }
            summary { list-style:none !important; }
            summary::marker { content:'' !important; }
            """;

    private static final String WEEKLY_PAGE_CSS = """
            @page { size: A4; margin: 16mm 15mm; }
            .page { box-shadow:none !important; margin:0 !important;
                    max-width:100% !important; padding:0 !important; }
            section { page-break-inside:auto; }
            table { page-break-inside:auto; }
            tr, .fact-block, .ai-block, .article, .card, .tl-item { page-break-inside:avoid; }
            """;

    /** Phase 4 — @page KHỚP ĐÚNG kích thước .report-page (1056x816, margin:0 vì report-page đã
     * tự có padding riêng) — không phải A4. */
    private static final String BI_PAGE_CSS = """
            @page { size: 1056px 816px; margin: 0; }
            .report-page { box-shadow:none !important; margin:0 !important; page-break-after:always;
                            width:1056px !important; height:816px !important; }
            .exhibit, .threat-card, .finding, .metric, .card, .timeline-row { page-break-inside:avoid; }
            """;

    private final SpringTemplateEngine templateEngine;

    public PdfExportService(SpringTemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    /** @param model đúng model của trang HTML — bảo đảm PDF và web luôn khớp nhau */
    public byte[] renderWeeklyReportPdf(Map<String, Object> model) {
        return renderPdf("weekly-report", model);
    }

    /** Phase 4 — cùng cơ chế renderWeeklyReportPdf, khác template. Không nhân đôi logic PDF. */
    public byte[] renderBiReportPdf(Map<String, Object> model) {
        return renderPdf("bi-report", model);
    }

    private byte[] renderPdf(String templateName, Map<String, Object> model) {
        Context ctx = new Context(Locale.forLanguageTag("vi"), model);
        String html = templateEngine.process(templateName, ctx);

        String pageCss = "bi-report".equals(templateName) ? BI_PAGE_CSS : WEEKLY_PAGE_CSS;

        // HTML5 → XHTML well-formed + chèn CSS override cho PDF
        Document jdoc = Jsoup.parse(html);
        // Batch 6: bỏ <link> Google Fonts trước khi render — PDF luôn ép DejaVu Sans/Mono
        // qua override dưới đây nên không cần font ngoài; giữ lại link sẽ khiến
        // OpenHTMLtoPDF cố fetch mạng ngoài không cần thiết (rủi ro treo/lỗi khi offline).
        jdoc.select("head link").remove();
        jdoc.head().appendElement("style").text(PDF_BASE_CSS + pageCss);
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
                    "/fonts/WenQuanYiZenHei.ttf"), "WenQuanYi Zen Hei", 400,
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
