package com.marketradar.interpret;

import java.util.List;
import java.util.function.Predicate;

/** Pure selection rule: stale inputs are never used as a fallback for a fresh narrative. */
public final class NarrativeInputSelection {
    private NarrativeInputSelection() {}

    public static <T> List<T> freshOnly(List<T> candidates, Predicate<T> inWindow) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        if (inWindow == null) throw new IllegalArgumentException("inWindow predicate is required");
        return candidates.stream().filter(inWindow).toList();
    }
}
