package com.marketradar.report;

import com.marketradar.product.ProductReportCadence;
import org.springframework.stereotype.Controller;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Locale;
import java.util.Map;

/** Monthly and quarterly surfaces share the exact same Product edition view path. */
@Controller
public class MonthlyReportController {

    private final ProductReportModel productReportModel;
    private final PdfExportService pdfExport;

    public MonthlyReportController(ProductReportModel productReportModel, PdfExportService pdfExport) {
        this.productReportModel = productReportModel;
        this.pdfExport = pdfExport;
    }

    @GetMapping("/report/monthly")
    public String monthly(Model model, Locale locale) {
        model.addAllAttributes(productReportModel.build(ProductReportCadence.MONTHLY, locale));
        return "monthly-report";
    }

    @GetMapping("/report/quarterly")
    public String quarterly(Model model, Locale locale) {
        model.addAllAttributes(productReportModel.build(ProductReportCadence.QUARTERLY, locale));
        return "monthly-report";
    }

    @GetMapping("/report/monthly.pdf")
    public ResponseEntity<byte[]> monthlyPdf(Locale locale) {
        return pdf(ProductReportCadence.MONTHLY, "monthly", locale);
    }

    @GetMapping("/report/quarterly.pdf")
    public ResponseEntity<byte[]> quarterlyPdf(Locale locale) {
        return pdf(ProductReportCadence.QUARTERLY, "quarterly", locale);
    }

    private ResponseEntity<byte[]> pdf(ProductReportCadence cadence, String filename, Locale locale) {
        Map<String, Object> model = productReportModel.build(cadence, locale);
        byte[] rendered = pdfExport.renderProductReportPdf("monthly-report", model, locale);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"market-radar-" + filename + "-report.pdf\"")
                .body(rendered);
    }
}
