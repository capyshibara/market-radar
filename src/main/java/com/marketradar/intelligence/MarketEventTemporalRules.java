package com.marketradar.intelligence;

import com.marketradar.domain.MarketEvent;

import java.time.LocalDate;

/** Time-relative lifecycle rules; status is computed, never persisted stale. */
public final class MarketEventTemporalRules {

    public enum Status { UPCOMING, ACTIVE, EXPIRED, INVALID_DATE_RANGE, UNDATED }

    private MarketEventTemporalRules() {}

    public static Status status(MarketEvent event, LocalDate asOf) {
        if (event == null || asOf == null) throw new IllegalArgumentException("event and asOf are required");
        if (event.getEffectiveDate() != null && event.getExpiryDate() != null
                && event.getExpiryDate().isBefore(event.getEffectiveDate())) {
            return Status.INVALID_DATE_RANGE;
        }
        if (event.getExpiryDate() != null && event.getExpiryDate().isBefore(asOf)) {
            return Status.EXPIRED;
        }
        LocalDate start = first(event.getEffectiveDate(), event.getOccurredDate(),
                event.getPublishedDate());
        if (start != null && start.isAfter(asOf)) return Status.UPCOMING;
        if (start != null || event.getExpiryDate() != null) return Status.ACTIVE;
        return Status.UNDATED;
    }

    /** Expired or internally inconsistent events cannot drive future-looking actions. */
    public static boolean futureActionEligible(MarketEvent event, LocalDate asOf) {
        Status status = status(event, asOf);
        return status != Status.EXPIRED && status != Status.INVALID_DATE_RANGE;
    }

    private static LocalDate first(LocalDate... values) {
        for (LocalDate value : values) if (value != null) return value;
        return null;
    }
}
