package com.marketradar.source;

import com.marketradar.domain.Source;
import com.marketradar.repo.SourceRepository;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Per-source on/off toggle for the Source Registry screen — the operator control the CFO
 * demo restructure asked for (Tier 3 sources start auto-deactivated by
 * TierReclassificationMigration, but must stay reversible, not a one-way lockout).
 */
@Controller
public class SourceActivationController {

    private final SourceRepository sources;

    public SourceActivationController(SourceRepository sources) {
        this.sources = sources;
    }

    @PostMapping("/sources/{id}/toggle-active")
    @Transactional
    public String toggleActive(@PathVariable Long id, RedirectAttributes redirect) {
        Source source = sources.findById(id).orElse(null);
        if (source == null) {
            redirect.addFlashAttribute("intakeError", "Source not found.");
            return "redirect:/sources";
        }
        source.setActive(!source.isActive());
        sources.save(source);
        return "redirect:/sources";
    }
}
