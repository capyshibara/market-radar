package com.marketradar.classify;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/** Dependency-free closed-enum validation for classifier output. */
public final class ClassificationSchemaRules {
    private ClassificationSchemaRules() {}

    /** Reject the complete sample if any label is null, blank or unsupported. */
    public static Optional<Set<String>> validateLabels(Collection<String> labels,
                                                        Set<String> allowedLabels) {
        if (labels == null || allowedLabels == null || allowedLabels.isEmpty()) return Optional.empty();
        Set<String> accepted = new LinkedHashSet<>();
        for (String label : labels) {
            if (label == null || label.isBlank() || !allowedLabels.contains(label)) return Optional.empty();
            accepted.add(label);
        }
        return Optional.of(Set.copyOf(accepted));
    }
}
