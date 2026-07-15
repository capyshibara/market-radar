package com.marketradar.pipeline;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only endpoint. It never starts, deletes or rewrites a pipeline stage. */
@RestController
public class ReprocessPreflightController {

    private final ReprocessPreflightService preflight;

    public ReprocessPreflightController(ReprocessPreflightService preflight) {
        this.preflight = preflight;
    }

    @GetMapping("/pipeline/reprocess/preflight.json")
    public ReprocessPreflightRules.Report inspect(
            @RequestParam(defaultValue = "false") boolean backupConfirmed) {
        return preflight.inspect(backupConfirmed);
    }
}
