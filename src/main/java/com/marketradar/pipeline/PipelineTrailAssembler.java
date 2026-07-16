package com.marketradar.pipeline;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Pure assembly rules for the per-document pipeline trail. */
public final class PipelineTrailAssembler {
    public static final String NOT_PROCESSED = "NOT_PROCESSED";

    private PipelineTrailAssembler() { }

    public record Event(int cycleId, long docId, String stage, String status) { }

    /**
     * Produces exactly one row for each (document, cycle) pair.  Statuses from
     * different cycles are deliberately never combined; repeated attempts in a
     * single cycle are summarized only within that one cycle.
     */
    public static Map<DocCycleKey, Map<String, String>> statusesByDocumentAndCycle(
            Collection<Event> events, Set<Integer> includedCycles) {
        Map<DocCycleKey, Map<String, List<String>>> raw = new LinkedHashMap<>();
        for (Event event : events) {
            if (!includedCycles.contains(event.cycleId()) || event.stage() == null) continue;
            DocCycleKey key = new DocCycleKey(event.cycleId(), event.docId());
            raw.computeIfAbsent(key, ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(event.stage(), ignored -> new ArrayList<>())
                    .add(event.status());
        }

        Map<DocCycleKey, Map<String, String>> result = new LinkedHashMap<>();
        raw.forEach((key, byStage) -> {
            Map<String, String> summarized = new LinkedHashMap<>();
            byStage.forEach((stage, statuses) -> summarized.put(stage, summarize(statuses)));
            result.put(key, summarized);
        });
        return result;
    }

    public static String statusFor(Map<String, String> byStage, String stage) {
        return byStage.getOrDefault(stage, NOT_PROCESSED);
    }

    static String summarize(Collection<String> statuses) {
        if (statuses == null || statuses.isEmpty()) return NOT_PROCESSED;
        return statuses.stream()
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .stream().sorted(Comparator.naturalOrder())
                .collect(Collectors.joining(", "));
    }

    public record DocCycleKey(int cycleId, long docId) { }
}
