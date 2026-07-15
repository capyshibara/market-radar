package com.marketradar.report;

import com.marketradar.pipeline.IngestionJob;
import com.marketradar.product.ProductReportCadence;
import com.marketradar.repo.RawDocRepository;
import com.marketradar.repo.SourceRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Locale;
import java.util.Map;

/** Weekly Product report plus source/ingestion operational endpoints. */
@Controller
public class ReportController {

    private final SourceRepository sources;
    private final RawDocRepository rawDocs;
    private final IngestionJob ingestionJob;
    private final PdfExportService pdfExport;
    private final EmailPngExportService emailPngExport;
    private final ProductReportModel productReportModel;

    public ReportController(SourceRepository sources, RawDocRepository rawDocs,
                            IngestionJob ingestionJob, PdfExportService pdfExport,
                            EmailPngExportService emailPngExport,
                            ProductReportModel productReportModel) {
        this.sources = sources;
        this.rawDocs = rawDocs;
        this.ingestionJob = ingestionJob;
        this.pdfExport = pdfExport;
        this.emailPngExport = emailPngExport;
        this.productReportModel = productReportModel;
    }

    @GetMapping("/report/weekly")
    public String weeklyReport(Model model, Locale locale) {
        model.addAllAttributes(buildWeeklyModel(locale));
        return "weekly-report";
    }

    @GetMapping("/report/weekly.pdf")
    public ResponseEntity<byte[]> weeklyReportPdf(Locale locale) {
        byte[] pdf = pdfExport.renderWeeklyReportPdf(buildWeeklyModel(locale), locale);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"market-radar-weekly-report.pdf\"")
                .body(pdf);
    }

    @GetMapping("/report/weekly/email.png")
    public ResponseEntity<byte[]> weeklyReportEmailPng(Locale locale) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                .body(emailPngExport.renderWeeklySummaryPng(buildWeeklyModel(locale), locale));
    }

    private Map<String, Object> buildWeeklyModel(Locale locale) {
        Map<String, Object> model = productReportModel.build(ProductReportCadence.WEEKLY, locale);
        model.put("sources", sources.findAllByOrderByTierAsc());
        model.put("docCount", rawDocs.count());
        return model;
    }

    @GetMapping("/sources")
    public String sourceRegistry(Model model) {
        model.addAttribute("sources", sources.findAllByOrderByTierAsc());
        return "sources";
    }

    @PostMapping("/ingest/run")
    @ResponseBody
    public String runIngest() {
        return "Kết quả ingest:\n" + ingestionJob.runOnce();
    }
}
