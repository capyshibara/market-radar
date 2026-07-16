package com.marketradar.product;

import com.marketradar.report.ProductReportModel;
import com.marketradar.pipeline.PipelineRunStatusService;
import org.springframework.stereotype.Controller;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** Department-specific report endpoint for the Product hackathon slice. */
@Controller
public class ProductBriefController {

    private final ProductBriefService briefs;
    private final ProductReportModel reportModel;
    private final ProductCadenceRegenerationService allCadences;
    private final PipelineRunStatusService pipelineStatus;
    private final ProductRegenerationCoordinator regenerationCoordinator;

    public ProductBriefController(ProductBriefService briefs, ProductReportModel reportModel,
                                  ProductCadenceRegenerationService allCadences,
                                  PipelineRunStatusService pipelineStatus,
                                  ProductRegenerationCoordinator regenerationCoordinator) {
        this.briefs = briefs;
        this.reportModel = reportModel;
        this.allCadences = allCadences;
        this.pipelineStatus = pipelineStatus;
        this.regenerationCoordinator = regenerationCoordinator;
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
        runWhenPipelineIdle(() -> briefs.regenerate(selected.days()));
        return "redirect:/report/product?cadence=" + selected.name().toLowerCase(Locale.ROOT);
    }

    @PostMapping("/report/product/regenerate-all")
    @ResponseBody
    public List<ProductCadenceRegenerationService.Result> regenerateAll() {
        return runWhenPipelineIdle(allCadences::regenerateAll);
    }

    private <T> T runWhenPipelineIdle(Supplier<T> work) {
        if (pipelineStatus.anyRunning()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Product regeneration is blocked while a pipeline stage is running. Wait for all stages to finish.");
        }
        return regenerationCoordinator.runExclusive(work);
    }
}
