package com.marketradar.report;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Batch 8: fake-SSO login (client-side only, no real auth — see
 * static/js/ops-session.js) and the role-matrix reference page. Every other
 * ops console route (/sources, /claims, /classifications, /review, /dedup,
 * /alerts, /labels) is unchanged — this controller only adds the two new
 * pages the redesign introduced.
 */
@Controller
public class OpsController {

    @GetMapping("/ops/login")
    public String login() {
        return "ops/login";
    }

    @GetMapping("/ops/role-matrix")
    public String roleMatrix() {
        return "ops/role-matrix";
    }
}
