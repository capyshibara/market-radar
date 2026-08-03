package com.marketradar.report.bi;

import com.marketradar.product.ProductReportCadence;
import com.marketradar.repo.RawDocRepository;
import com.marketradar.report.PdfExportService;
import com.marketradar.report.ProductReportAdapter;
import com.marketradar.report.ProductReportModel;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.Map;

/**
 * Bản BI report (Meridian design) của report định kỳ đang có — KHÔNG phải nguồn dữ liệu mới,
 * chỉ là cách trình bày khác của đúng Snapshot report tuần/tháng/quý đang publish (xem
 * PeriodicalBiAdapter). Cùng cadence param với report tuần/tháng hiện có.
 */
@Controller
public class BiReportController {

    private final ProductReportAdapter reports;
    private final PeriodicalBiAdapter biAdapter;
    private final PdfExportService pdfExport;
    private final BiReportDocxService docxExport;
    private final BiReportPptxService pptxExport;
    private final RawDocRepository rawDocs;

    public BiReportController(ProductReportAdapter reports, PeriodicalBiAdapter biAdapter,
                              PdfExportService pdfExport, BiReportDocxService docxExport,
                              BiReportPptxService pptxExport, RawDocRepository rawDocs) {
        this.reports = reports;
        this.biAdapter = biAdapter;
        this.pdfExport = pdfExport;
        this.docxExport = docxExport;
        this.pptxExport = pptxExport;
        this.rawDocs = rawDocs;
    }

    @GetMapping("/report/bi")
    public String biReport(@RequestParam(defaultValue = "weekly") String cadence,
                           @RequestParam(defaultValue = "vi") String lang, Model model) {
        model.addAllAttributes(buildModel(parseCadence(cadence), cadence, isVi(lang)));
        return "bi-report";
    }

    @GetMapping("/report/bi.pdf")
    public ResponseEntity<byte[]> biReportPdf(@RequestParam(defaultValue = "weekly") String cadence,
                                              @RequestParam(defaultValue = "vi") String lang) {
        byte[] pdf = pdfExport.renderBiReportPdf(buildModel(parseCadence(cadence), cadence, isVi(lang)));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"market-radar-bi-report-" + cadence + ".pdf\"")
                .body(pdf);
    }

    @GetMapping("/report/bi.docx")
    public ResponseEntity<byte[]> biReportDocx(@RequestParam(defaultValue = "weekly") String cadence) {
        ProductReportCadence c = parseCadence(cadence);
        var content = biAdapter.adapt("Business Intelligence Report — " + c.label(true),
                c.periodLabel(LocalDate.now(ProductReportModel.REPORT_ZONE), true),
                reports.current(c, LocalDate.now(ProductReportModel.REPORT_ZONE)),
                rawDocs.count());
        byte[] docx = docxExport.render(content);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"market-radar-bi-report-" + cadence + ".docx\"")
                .body(docx);
    }

    @GetMapping("/report/bi.pptx")
    public ResponseEntity<byte[]> biReportPptx(@RequestParam(defaultValue = "weekly") String cadence) {
        ProductReportCadence c = parseCadence(cadence);
        var content = biAdapter.adapt("Business Intelligence Report — " + c.label(true),
                c.periodLabel(LocalDate.now(ProductReportModel.REPORT_ZONE), true),
                reports.current(c, LocalDate.now(ProductReportModel.REPORT_ZONE)),
                rawDocs.count());
        byte[] pptx = pptxExport.render(content);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"market-radar-bi-report-" + cadence + ".pptx\"")
                .body(pptx);
    }

    private Map<String, Object> buildModel(ProductReportCadence cadence, String cadenceParam, boolean vi) {
        LocalDate asOf = LocalDate.now(ProductReportModel.REPORT_ZONE);
        ProductReportAdapter.Snapshot snapshot = reports.current(cadence, asOf);
        var content = biAdapter.adapt("Business Intelligence Report — " + cadence.label(true),
                cadence.periodLabel(asOf, true), snapshot, rawDocs.count());
        Map<String, Object> model = BiReportPageBuilder.toTemplateModel(content, vi);
        String langParam = vi ? "vi" : "en";
        model.put("pdfHref", "/report/bi.pdf?cadence=" + cadenceParam + "&lang=" + langParam);
        model.put("docxHref", "/report/bi.docx?cadence=" + cadenceParam);
        model.put("pptxHref", "/report/bi.pptx?cadence=" + cadenceParam);
        model.put("langHrefVi", "/report/bi?cadence=" + cadenceParam + "&lang=vi");
        model.put("langHrefEn", "/report/bi?cadence=" + cadenceParam + "&lang=en");
        model.put("cadenceParam", cadenceParam);
        model.put("cadenceHrefWeekly", "/report/bi?cadence=weekly&lang=" + langParam);
        model.put("cadenceHrefMonthly", "/report/bi?cadence=monthly&lang=" + langParam);
        model.put("cadenceHrefQuarterly", "/report/bi?cadence=quarterly&lang=" + langParam);
        return model;
    }

    private static boolean isVi(String lang) {
        return !"en".equalsIgnoreCase(lang);
    }

    private static ProductReportCadence parseCadence(String value) {
        return switch (value.toLowerCase()) {
            case "monthly" -> ProductReportCadence.MONTHLY;
            case "quarterly" -> ProductReportCadence.QUARTERLY;
            default -> ProductReportCadence.WEEKLY;
        };
    }
}
