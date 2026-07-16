package com.marketradar.intake;

import com.marketradar.repo.RawDocRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;

/** Operator-only intake route.  It creates a raw document; it never auto-publishes it. */
@Controller
public class ManualDocumentController {
    private final ManualDocumentIntakeService intake;
    private final RawDocRepository rawDocs;

    public ManualDocumentController(ManualDocumentIntakeService intake, RawDocRepository rawDocs) {
        this.intake = intake;
        this.rawDocs = rawDocs;
    }

    @GetMapping("/documents/intake")
    public String page(Model model) {
        model.addAttribute("documentCount", rawDocs.count());
        model.addAttribute("today", LocalDate.now());
        return "manual-intake";
    }

    @PostMapping("/documents/intake/paste")
    public String paste(@RequestParam String title, @RequestParam String publisher,
                        @RequestParam String sourceUrl, @RequestParam LocalDate publishedDate,
                        @RequestParam String language, @RequestParam String rawText,
                        RedirectAttributes redirect) {
        try {
            addResult(intake.submitText(title, publisher, sourceUrl, publishedDate, language, rawText), redirect);
        } catch (ManualDocumentRules.ValidationException invalid) {
            redirect.addFlashAttribute("intakeError", invalid.getMessage());
        }
        return "redirect:/documents/intake";
    }

    @PostMapping("/documents/intake/upload")
    public String upload(@RequestParam String title, @RequestParam String publisher,
                         @RequestParam String sourceUrl, @RequestParam LocalDate publishedDate,
                         @RequestParam String language, @RequestParam("file") MultipartFile file,
                         RedirectAttributes redirect) {
        try {
            addResult(intake.submitFile(title, publisher, sourceUrl, publishedDate, language, file), redirect);
        } catch (ManualDocumentRules.ValidationException invalid) {
            redirect.addFlashAttribute("intakeError", invalid.getMessage());
        }
        return "redirect:/documents/intake";
    }

    private static void addResult(ManualDocumentIntakeService.Result result, RedirectAttributes redirect) {
        if (result.duplicate()) redirect.addFlashAttribute("intakeError", result.message());
        else redirect.addFlashAttribute("intakeSuccess", result.message() + " Document #" + result.rawDocId());
    }
}
