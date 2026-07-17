package com.marketradar.report;

import com.marketradar.product.ProductReportCadence;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Locale;

/** Public editorial reader for one report fact and its complete stored source document. */
@Controller
public class ProductSourceStoryController {
    private final ProductSourceStoryService stories;

    public ProductSourceStoryController(ProductSourceStoryService stories) {
        this.stories = stories;
    }

    @GetMapping("/report/story/{factCode}")
    public String story(@PathVariable String factCode,
                        @RequestParam(defaultValue = "monthly") String cadence,
                        Locale locale, Model model) {
        ProductReportCadence selected = ProductReportCadence.parse(
                cadence, ProductReportCadence.MONTHLY);
        boolean vi = "vi".equals(locale.getLanguage());
        model.addAttribute("story", stories.story(factCode, locale));
        model.addAttribute("vi", vi);
        model.addAttribute("lang", vi ? "vi" : "en");
        model.addAttribute("cadence", selected);
        model.addAttribute("cadencePath", "/report/" + selected.name().toLowerCase(Locale.ROOT));
        return "product-source-story";
    }
}
