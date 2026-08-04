package com.marketradar.pipeline;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only operational checkpoint used by the UI, scripts and morning handoff. */
@RestController
public class PipelineCheckpointController {

    private final PipelineCheckpointService checkpoints;

    public PipelineCheckpointController(PipelineCheckpointService checkpoints) {
        this.checkpoints = checkpoints;
    }

    @GetMapping("/pipeline/checkpoint.json")
    public PipelineCheckpointService.Snapshot inspect() {
        return checkpoints.inspect();
    }
}
