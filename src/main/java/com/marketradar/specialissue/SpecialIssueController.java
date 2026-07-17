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
import org.springframework.util.MultiValueMap;

import java.util.Locale;
import java.util.Map;

/** Product Academy library, editorial reader and reliable offline PDF export. */
@Controller
public class SpecialIssueController {

    private static final String PDF_CSS = """
            @page { size: 11in 8.5in; margin: 0; background:#F8F6F1; }
            html, body { margin:0 !important; padding:0 !important; background:#F8F6F1 !important; }
            .report-toolbar { display:none !important; }
            .report-canvas { display:block !important; padding:0 !important; background:#F8F6F1 !important; }
            .report-page { width:11in !important; height:8.5in !important; margin:0 !important;
                           box-shadow:none !important; page-break-after:always; overflow:hidden !important; }
            .report-page:last-child { page-break-after:auto; }
            body, p, li, td, th, span, div, a { font-family:'Work Sans', sans-serif !important; }
            .display, .cover h1, .summary-box, .finding-no, .finding h3, .toc-no, .toc-text b,
            .toc-content h2, .lede, .system-layer h3, .architecture-visual .arch-circle,
            .arch-card b, .callout, .formula b, .big-stat strong, .cap-number, .cycle-title,
            .risk-col h3, .balance-side b, .evidence-tier b, .caslon
              { font-family:'Libre Caslon Text', serif !important; }
            .source-item a { font-family:'Lora', serif !important; }
            .display span, .cover h1 span { font-family:'Libre Caslon Text', serif !important; }
            .locale-vi .display, .locale-vi .display span, .locale-vi .cover h1,
            .locale-vi .cover h1 span, .locale-vi .summary-box, .locale-vi .finding-no,
            .locale-vi .finding h3, .locale-vi .toc-no, .locale-vi .toc-text b,
            .locale-vi .lede, .locale-vi .system-layer h3, .locale-vi .callout,
            .locale-vi .formula b, .locale-vi .big-stat strong, .locale-vi .risk-col h3,
            .locale-vi .source-item a, .locale-vi .condition b, .locale-vi .toc-content h2,
            .locale-vi .architecture-visual .arch-circle, .locale-vi .arch-card b,
            .locale-vi .cap-number, .locale-vi .cycle-title, .locale-vi .balance-side b,
            .locale-vi .evidence-tier b { font-family:'Lora', serif !important; }
            .report-page { background:#F8F6F1 !important; color:#33322E !important; }
            .report-page.page-dark { background:#0E1B6B !important; color:#F2EFE8 !important; }
            .cover h1 { color:#F2EFE8 !important; }
            .cover .deck { color:#F2EFE8 !important; }
            .kicker, .folio, .source-line, .metric span, .toc-text span, .source-item p { color:#8A8878 !important; }
            .page-dark .kicker { color:#9AA5D9 !important; }
            .display, .toc-no, .toc-text b, .metric b, .finding-no, .chapter-no,
            .source-code, .source-item a, .role-table th, .decision-table td:first-child { color:#2647E8 !important; }
            .page-rule, .score-fill { background:#2647E8 !important; }
            .summary-box, .big-stat, .decision-table th { background:#0E1B6B !important; color:#F2EFE8 !important; }
            .summary-box, .callout { color:#F2EFE8 !important; }
            .callout { background:#152A8C !important; }
            .formula, .linkage, .method-box { background:#EBEEFC !important; }
            .formula { border-color:#2647E8 !important; }
            .formula b { color:#2647E8 !important; }
            .big-stat strong { color:#F5A623 !important; }
            .step-dot { background:#2647E8 !important; color:#FFFFFF !important; }
            .journey-step:after { background:#B9C6F4 !important; }
            .system-layer { border-color:#2647E8 !important; }
            .system-layer:nth-child(2) { border-color:#4F9B90 !important; }
            .system-layer:nth-child(3) { border-color:#8477B0 !important; }
            .go { background:#DFF4ED !important; color:#176447 !important; }
            .hold { background:#FFF0D8 !important; color:#8A5A08 !important; }
            /* Meridian Review visual system: the web reader uses the supplied inline SVG;
               the offline PDF uses deterministic CSS geometry so artwork never disappears. */
            .toc-art-stage svg { display:none !important; }
            .toc-visual, .toc-content, .cap-exhibit, .balance-core { background:#0E1B6B !important; }
            .toc-content h2, .toc-entry .toc-no, .toc-entry .toc-text b { color:#F2EFE8 !important; }
            .toc-content h2 span { color:#8FA6FF !important; }
            .metric-dots i { background:#2647E8 !important; }
            .metric:nth-child(2) .metric-dots i { background:#4F9B90 !important; }
            .metric:nth-child(3) .metric-dots i { background:#8477B0 !important; }
            .architecture-visual .policy { background:#9AA5D9 !important; }
            .architecture-visual .programme { background:#4F9B90 !important; }
            .architecture-visual .link { background:#C9A15A !important; }
            .architecture-visual .arch-core, .cycle-node b, .pilot-marker
              { background:#F8F6F1 !important; color:#0E1B6B !important; }
            .arch-card, .cycle-exhibit, .balance-visual { border-color:#E1DDD2 !important; }
            .cap-number { color:#F5A623 !important; }
            .cap-caption { color:#F2EFE8 !important; }
            .cap-scale { color:#C7C3B8 !important; }
            .cap-track { background:#E9E5D9 !important; }
            .cap-fill { background:#F5A623 !important; }
            .cap-rest { background:#B9C6F4 !important; }
            .cycle-node:after, .pilot-phase:after { background:#B9C6F4 !important; }
            .owner-chain { border-color:#2647E8 !important; }
            .balance-rule { background:#4F9B90 !important; }
            .balance-side:last-child .balance-rule { background:#8477B0 !important; }
            .pilot-phase:first-child .pilot-marker { background:#2647E8 !important; color:#FFFFFF !important; }
            .evidence-tier { background:#EBEEFC !important; border-color:#2647E8 !important; }
            .evidence-tier:nth-child(2) { background:#E5F2EF !important; border-color:#4F9B90 !important; }
            .evidence-tier:nth-child(3) { background:#EEEAF5 !important; border-color:#8477B0 !important; }
            * { -fs-page-break-min-height: 0; }
            """;

    private final SpecialIssueService issues;
    private final PdfExportService pdfExport;

    public SpecialIssueController(SpecialIssueService issues, PdfExportService pdfExport) {
        this.issues = issues;
        this.pdfExport = pdfExport;
    }

    @GetMapping("/product/special-issues")
    public String library(Model model, Locale locale) {
        model.addAttribute("candidates", issues.candidates(locale));
        model.addAttribute("featured", issues.wellnessIssue(locale));
        model.addAttribute("vi", "vi".equals(locale.getLanguage()));
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
        model.addAttribute("vi", "vi".equals(locale.getLanguage()));
        return "special-issue";
    }

    @GetMapping("/product/special-issues/{slug}/edit")
    public String editor(@PathVariable String slug, Model model, Locale locale) {
        model.addAttribute("issue", issues.issue(slug, locale));
        model.addAttribute("vi", "vi".equals(locale.getLanguage()));
        return "special-issue-editor";
    }

    @PostMapping("/product/special-issues/{slug}/edit")
    public String saveEditor(@PathVariable String slug,
                             @RequestParam MultiValueMap<String, String> form,
                             Locale locale, RedirectAttributes redirect) {
        try {
            issues.saveEditorial(slug, locale, form, "Nguyễn Thị Minh Hạnh");
            redirect.addFlashAttribute("editorialSuccess", "Editorial draft saved. The reader and PDF now use this version.");
        } catch (RuntimeException error) {
            redirect.addFlashAttribute("editorialError", error.getMessage());
        }
        return "redirect:/product/special-issues/" + slug + "/edit?lang=" + locale.getLanguage();
    }

    @GetMapping("/product/special-issues/{slug}.pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable String slug, Locale locale) {
        SpecialIssueService.Issue issue = issues.issue(slug, locale);
        boolean vi = "vi".equals(locale.getLanguage());
        byte[] rendered = pdfExport.render("special-issue", Map.of("issue", issue, "vi", vi), locale, PDF_CSS);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"product-academy-" + issue.slug() + (vi ? "-vi" : "-en") + ".pdf\"")
                .body(rendered);
    }

    @ExceptionHandler(SpecialIssueService.UnknownIssueException.class)
    public ResponseEntity<String> issueMissing(SpecialIssueService.UnknownIssueException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error.getMessage());
    }
}
