package com.marketradar.product;

import com.marketradar.report.ProductReportModel;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;
import java.util.Locale;

/** Department-specific report endpoint for the Product hackathon slice. */
@Controller
public class ProductBriefController {

    private final ProductBriefService briefs;
    private final ProductReportModel reportModel;
    private final ProductCadenceRegenerationService allCadences;

    public ProductBriefController(ProductBriefService briefs, ProductReportModel reportModel,
                                  ProductCadenceRegenerationService allCadences) {
        this.briefs = briefs;
        this.reportModel = reportModel;
        this.allCadences = allCadences;
    }

    @GetMapping("/report/product")
    public String productReport(@RequestParam(defaultValue = "quarterly") String cadence,
                                Model model, Locale locale) {
        ProductReportCadence selected = ProductReportCadence.parse(
                cadence, ProductReportCadence.QUARTERLY);
        reportModel.build(selected, locale).forEach(model::addAttribute);
        return "product-brief";
    }

    @PostMapping("/report/product/regenerate")
    public String regenerate(@RequestParam(defaultValue = "quarterly") String cadence) {
        ProductReportCadence selected = ProductReportCadence.parse(
                cadence, ProductReportCadence.QUARTERLY);
        briefs.regenerate(selected.days());
        return "redirect:/report/product?cadence=" + selected.name().toLowerCase(Locale.ROOT);
    }

    @PostMapping("/report/product/regenerate-all")
    @ResponseBody
    public List<ProductCadenceRegenerationService.Result> regenerateAll() {
        return allCadences.regenerateAll();
    }
}
