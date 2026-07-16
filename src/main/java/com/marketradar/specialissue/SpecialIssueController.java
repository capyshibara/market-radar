package com.marketradar.specialissue;

import com.marketradar.report.PdfExportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Locale;
import java.util.Map;

/** Product Academy library, editorial reader and reliable offline PDF export. */
@Controller
public class SpecialIssueController {

    private static final String PDF_CSS = """
            @page { size: A4; margin: 0; }
            .issue-topbar, .no-print { display:none !important; }
            .issue-shell { max-width:none !important; margin:0 !important; }
            .issue-cover { min-height:250mm !important; page-break-after:always; border-radius:0 !important; }
            .issue-cover { background:#2948d8 !important; color:#ffffff !important; }
            .issue-cover h1 { color:#ffffff !important; }
            .issue-cover .series { color:#95f3e8 !important; }
            .issue-cover .deck, .issue-cover .cover-meta { color:#dbe2ff !important; }
            .circle-a { border-color:#61c6bd !important; }
            .circle-b { border-color:#efb345 !important; }
            .circle-c { background:#ef6748 !important; }
            .beam { background:#ffffff !important; box-shadow:0 59px #ffffff,0 118px #ffffff,0 177px #ffffff !important; }
            .issue-section, .source-register { border-color:#d8d2c6 !important; }
            .issue-section h2, .source-register h2, .page-intro { color:#162442 !important; }
            .issue-section p, .issue-section li { color:#34425d !important; }
            .issue-section { page-break-inside:avoid; }
            .issue-source { page-break-inside:avoid; }
            .issue-cover-art { opacity:1 !important; }
            """;

    private final SpecialIssueService issues;
    private final PdfExportService pdfExport;

    public SpecialIssueController(SpecialIssueService issues, PdfExportService pdfExport) {
        this.issues = issues;
        this.pdfExport = pdfExport;
    }

    @GetMapping("/product/special-issues")
    public String library(Model model) {
        model.addAttribute("candidates", issues.candidates());
        model.addAttribute("featured", issues.wellnessIssue(Locale.ENGLISH));
        return "special-issues";
    }

    @PostMapping("/product/special-issues/topic-lab/commission")
    public String commission(@RequestParam String slug, RedirectAttributes redirect) {
        try {
            issues.commission(slug);
            redirect.addFlashAttribute("commissioned", "Special Issue commissioned and ready for editorial review.");
        } catch (SpecialIssueService.IssueNotReadyException notReady) {
            redirect.addFlashAttribute("commissionError", notReady.getMessage());
        }
        return "redirect:/product/special-issues";
    }

    @GetMapping("/product/special-issues/{slug}")
    public String reader(@PathVariable String slug, Model model, Locale locale) {
        model.addAttribute("issue", issues.issue(slug, locale));
        return "special-issue";
    }

    @GetMapping("/product/special-issues/{slug}.pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable String slug, Locale locale) {
        SpecialIssueService.Issue issue = issues.issue(slug, locale);
        byte[] rendered = pdfExport.render("special-issue", Map.of("issue", issue), locale, PDF_CSS);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"product-academy-" + issue.slug() + ".pdf\"")
                .body(rendered);
    }

    @ExceptionHandler(SpecialIssueService.UnknownIssueException.class)
    public ResponseEntity<String> issueMissing(SpecialIssueService.UnknownIssueException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error.getMessage());
    }
}
