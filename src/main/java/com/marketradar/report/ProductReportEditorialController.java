package com.marketradar.report;

import com.marketradar.product.ProductReportCadence;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Locale;

/** Human review desk for the narrative layer of Weekly, Monthly and Quarterly reports. */
@Controller
public class ProductReportEditorialController {
    private final ProductReportModel reports;
    private final ProductReportEditorialService editorial;

    public ProductReportEditorialController(ProductReportModel reports,
                                            ProductReportEditorialService editorial) {
        this.reports = reports;
        this.editorial = editorial;
    }

    @GetMapping("/report/product/edit")
    public String editor(@RequestParam(defaultValue = "monthly") String cadence,
                         Model model, Locale locale) {
        ProductReportCadence selected = ProductReportCadence.parse(
                cadence, ProductReportCadence.MONTHLY);
        model.addAllAttributes(reports.build(selected, locale));
        model.addAttribute("cadenceOptions", List.of(ProductReportCadence.values()));
        return "product-report-editor";
    }

    @PostMapping("/report/product/edit")
    public String save(@RequestParam(defaultValue = "monthly") String cadence,
                       @RequestParam MultiValueMap<String, String> form,
                       Locale locale, RedirectAttributes redirect) {
        ProductReportCadence selected = ProductReportCadence.parse(
                cadence, ProductReportCadence.MONTHLY);
        try {
            editorial.save(selected, locale, form);
            redirect.addFlashAttribute("editorialSuccess", "vi".equals(locale.getLanguage())
                    ? "Đã lưu bản biên tập. Bản đọc và PDF hiện dùng nội dung này."
                    : "Editorial draft saved. The reader and PDF now use this content.");
        } catch (RuntimeException error) {
            redirect.addFlashAttribute("editorialError", error.getMessage());
        }
        return "redirect:/report/product/edit?cadence="
                + selected.name().toLowerCase(Locale.ROOT)
                + "&lang=" + ("vi".equals(locale.getLanguage()) ? "vi" : "en");
    }
}
