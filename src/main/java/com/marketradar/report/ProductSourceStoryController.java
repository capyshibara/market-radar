package com.marketradar.report;

import com.marketradar.product.ProductReportCadence;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.util.Locale;

/** Public editorial reader for one report fact and its complete stored source document. */
@Controller
public class ProductSourceStoryController {
    private final ProductSourceStoryService stories;
    private final SourceStoryExplainerService explainers;

    public ProductSourceStoryController(ProductSourceStoryService stories,
                                        SourceStoryExplainerService explainers) {
        this.stories = stories;
        this.explainers = explainers;
    }

    @GetMapping("/report/story/{factCode}")
    public String story(@PathVariable String factCode,
                        @RequestParam(defaultValue = "monthly") String cadence,
                        Locale locale, Model model) {
        ProductReportCadence selected = ProductReportCadence.parse(
                cadence, ProductReportCadence.MONTHLY);
        boolean vi = "vi".equals(locale.getLanguage());
        var story = stories.story(factCode, locale);
        model.addAttribute("story", story);
        model.addAttribute("explainer", explainers.find(story.factCode()).orElse(null));
        model.addAttribute("vi", vi);
        model.addAttribute("lang", vi ? "vi" : "en");
        model.addAttribute("cadence", selected);
        model.addAttribute("cadencePath", "/report/" + selected.name().toLowerCase(Locale.ROOT));
        return "product-source-story";
    }

    @PostMapping("/report/story/{factCode}/explain")
    public String explain(@PathVariable String factCode,
                          @RequestParam(defaultValue = "monthly") String cadence,
                          @RequestParam(defaultValue = "en") String lang,
                          RedirectAttributes redirect) {
        try {
            explainers.generateIfAbsent(factCode.strip().toUpperCase(Locale.ROOT));
        } catch (SourceStoryExplainerService.ExplainerRejectedException e) {
            redirect.addFlashAttribute("explainError", e.getMessage());
        }
        return "redirect:/report/story/" + URLEncoder.encode(factCode, StandardCharsets.UTF_8)
                + "?cadence=" + URLEncoder.encode(cadence, StandardCharsets.UTF_8)
                + "&lang=" + ("vi".equals(lang) ? "vi" : "en");
    }
}
