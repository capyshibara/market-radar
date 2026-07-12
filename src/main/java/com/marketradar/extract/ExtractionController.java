package com.marketradar.extract;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/** Batch 8: POST /extract/run — chạy tay AI#2 (cùng pattern /ingest/run, /classify/run). */
@Controller
public class ExtractionController {

    private final FactExtractionJob job;

    public ExtractionController(FactExtractionJob job) {
        this.job = job;
    }

    @PostMapping("/extract/run")
    @ResponseBody
    public String run() {
        return "Kết quả extract:\n" + job.runOnce();
    }
}
