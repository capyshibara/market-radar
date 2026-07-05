package com.marketradar.alert;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import com.marketradar.repo.AlertLogRepository;

/**
 * Batch 5:
 *  GET  /alerts      — audit mọi hot alert (SENT/FAILED/SKIPPED, payload nguyên văn)
 *  POST /alerts/test — smoke-test webhook không cần claim thật
 */
@Controller
public class AlertController {

    private final AlertLogRepository alertLogs;
    private final HotAlertService alertService;

    public AlertController(AlertLogRepository alertLogs, HotAlertService alertService) {
        this.alertLogs = alertLogs;
        this.alertService = alertService;
    }

    @GetMapping("/alerts")
    public String alerts(Model model) {
        model.addAttribute("alerts", alertLogs.findAllByOrderByCreatedAtDescIdDesc());
        return "alerts";
    }

    @PostMapping("/alerts/test")
    @ResponseBody
    public String test() {
        return alertService.sendTest();
    }
}
