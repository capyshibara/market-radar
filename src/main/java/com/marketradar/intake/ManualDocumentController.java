package com.marketradar.intake;

import com.marketradar.repo.RawDocRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

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
        return "manual-intake";
    }

    @PostMapping("/documents/intake/url")
    public String importUrl(@RequestParam String sourceUrl, RedirectAttributes redirect) {
        try {
            addResult(intake.importUrl(sourceUrl), redirect);
        } catch (ManualDocumentRules.ValidationException invalid) {
            redirect.addFlashAttribute("intakeError", invalid.getMessage());
        }
        return "redirect:/documents/intake";
    }

    @PostMapping("/documents/intake/upload")
    public String upload(@RequestParam("file") MultipartFile file,
                         RedirectAttributes redirect) {
        try {
            addResult(intake.submitFile(file), redirect);
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
