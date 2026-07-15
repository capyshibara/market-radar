package com.marketradar.extract;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

/** Batch 8: POST /extract/run — chạy tay AI#2 (cùng pattern /ingest/run, /classify/run). */
@Controller
public class ExtractionController {

    private final FactExtractionJob job;
    private final ExtractionBackfillService backfill;

    public ExtractionController(FactExtractionJob job, ExtractionBackfillService backfill) {
        this.job = job;
        this.backfill = backfill;
    }

    @PostMapping("/extract/run")
    @ResponseBody
    public String run() {
        return "Kết quả extract:\n" + job.runOnce();
    }

    /** Read-only: no DB writes and no LLM calls. */
    @GetMapping("/extract/backfill/plan")
    @ResponseBody
    public ExtractionBackfillService.BackfillPlan backfillPlan(
            @RequestParam(defaultValue = "10") int limit) {
        return backfill.plan(limit);
    }

    /**
     * Safe targeted rerun: IDs are explicit, capped at 25, and confirm=true is
     * required. Current, incomplete, duplicate and unconfirmed docs are rejected.
     */
    @PostMapping("/extract/backfill/run")
    @ResponseBody
    public String targetedBackfill(@RequestParam List<Long> rawDocId,
                                   @RequestParam(defaultValue = "false") boolean confirm) {
        var selection = backfill.selectTargets(rawDocId);
        if (!confirm) {
            return "DRY RUN ONLY — add confirm=true to execute.\nEligible: "
                    + selection.acceptedIds() + "\nRejected: " + selection.rejected() + "\n";
        }
        return "Targeted extraction backfill:\n" + job.runTargeted(rawDocId);
    }
}
