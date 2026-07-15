package com.marketradar.pipeline;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Operational API; intentionally separate from the broad legacy refetch stage. */
@RestController
public class TargetedRefetchController {

    private final TargetedRefetchService refetch;

    public TargetedRefetchController(TargetedRefetchService refetch) {
        this.refetch = refetch;
    }

    @GetMapping("/pipeline/refetch/plan.json")
    public TargetedRefetchService.Plan plan(
            @RequestParam(required = false) List<Long> rawDocIds) {
        try {
            return refetch.plan(rawDocIds);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @PostMapping("/pipeline/refetch/execute.json")
    public TargetedRefetchService.Execution execute(
            @RequestParam List<Long> rawDocIds,
            @RequestParam(defaultValue = "false") boolean confirm) {
        try {
            return refetch.execute(rawDocIds, confirm);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }
}
