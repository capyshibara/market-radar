package com.marketradar.pipeline;

import com.marketradar.domain.PipelineRunLog;

import java.util.List;

/**
 * A durable view of one pipeline cycle.  A cycle starts when Ingest is run; the
 * following stage runs remain in that cycle until the next Ingest starts one.
 *
 * The database column is still named {@code batchId} for backwards compatibility,
 * but this type keeps the user-facing meaning explicit and prevents callers from
 * treating arbitrary stage runs as a single undifferentiated history.
 */
public record PipelineCycle(int cycleId, List<PipelineRunLog> runs) {
    public PipelineCycle {
        runs = List.copyOf(runs);
    }

    public int runCount() {
        return runs.size();
    }
}
