package com.marketradar.report;

import com.marketradar.product.ProductReportCadence;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Locale;

/** Monthly and quarterly surfaces share the exact same Product edition view path. */
@Controller
public class MonthlyReportController {

    private final ProductReportModel productReportModel;

    public MonthlyReportController(ProductReportModel productReportModel) {
        this.productReportModel = productReportModel;
    }

    @GetMapping("/report/monthly")
    public String monthly(Model model, Locale locale) {
        model.addAllAttributes(productReportModel.build(ProductReportCadence.MONTHLY, locale));
        return "monthly-report";
    }

    @GetMapping("/report/quarterly")
    public String quarterly(Model model, Locale locale) {
        model.addAllAttributes(productReportModel.build(ProductReportCadence.QUARTERLY, locale));
        return "monthly-report";
    }
}
