package com.marketradar.source;

import com.marketradar.domain.Source;
import com.marketradar.domain.SourceUsePolicy;
import com.marketradar.repo.SourceRepository;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Admin control for editorial use; never changes crawl activation. */
@Controller
public class SourcePolicyController {

    private final SourceRepository sources;

    public SourcePolicyController(SourceRepository sources) {
        this.sources = sources;
    }

    @PostMapping("/sources/{id}/use-policy")
    @Transactional
    public String update(@PathVariable Long id,
                         @RequestParam("usePolicy") String value,
                         RedirectAttributes redirect) {
        Source source = sources.findById(id).orElse(null);
        if (source == null) {
            redirect.addFlashAttribute("intakeError", "Source not found.");
            return "redirect:/sources";
        }
        try {
            source.setUsePolicy(SourceUsePolicy.valueOf(value));
            sources.save(source);
        } catch (IllegalArgumentException invalid) {
            redirect.addFlashAttribute("intakeError", "Invalid source-use policy.");
        }
        return "redirect:/sources";
    }
}
