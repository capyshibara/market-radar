package com.marketradar.intelligence;

import java.time.LocalDate;

/**
 * Explicit report-time semantics. Publication, occurrence, effective and
 * scheduled dates answer different questions and must not be collapsed.
 */
public final class ReportTimeWindowRules {

    private ReportTimeWindowRules() {}

    public enum Inclusion {
        CURRENT_EVENT,
        NEW_DISCLOSURE_WITH_HISTORICAL_CONTEXT,
        UPCOMING_ANNOUNCED_EVENT,
        EXCLUDE
    }

    public record Dates(LocalDate published, LocalDate occurred, LocalDate effective,
                        LocalDate expiry, LocalDate forecastOrScheduled) {}

    public static Inclusion classify(Dates dates, LocalDate windowStart, LocalDate windowEnd,
                                     int upcomingHorizonDays) {
        if (dates == null || windowStart == null || windowEnd == null) return Inclusion.EXCLUDE;
        if (windowEnd.isBefore(windowStart)) throw new IllegalArgumentException("windowEnd before windowStart");

        if (inWindow(dates.occurred(), windowStart, windowEnd)
                || inWindow(dates.effective(), windowStart, windowEnd)
                || inWindow(dates.expiry(), windowStart, windowEnd)) {
            return Inclusion.CURRENT_EVENT;
        }

        // An announced future effective date or expiry is operationally a calendar
        // event just like an explicitly scheduled disclosure. Keeping these semantics
        // separate in storage does not mean omitting them from the forward calendar.
        LocalDate scheduled = earliestAfter(windowEnd, dates.effective(), dates.expiry(),
                dates.forecastOrScheduled());
        if (scheduled != null && scheduled.isAfter(windowEnd)
                && !scheduled.isAfter(windowEnd.plusDays(Math.max(0, upcomingHorizonDays)))
                && dates.published() != null && !dates.published().isAfter(windowEnd)) {
            return Inclusion.UPCOMING_ANNOUNCED_EVENT;
        }

        if (inWindow(dates.published(), windowStart, windowEnd)) {
            boolean historical = (dates.occurred() != null && dates.occurred().isBefore(windowStart))
                    || (dates.effective() != null && dates.effective().isBefore(windowStart));
            return historical ? Inclusion.NEW_DISCLOSURE_WITH_HISTORICAL_CONTEXT
                    : Inclusion.CURRENT_EVENT;
        }
        return Inclusion.EXCLUDE;
    }

    private static boolean inWindow(LocalDate date, LocalDate start, LocalDate end) {
        return date != null && !date.isBefore(start) && !date.isAfter(end);
    }

    private static LocalDate earliestAfter(LocalDate boundary, LocalDate... candidates) {
        LocalDate earliest = null;
        for (LocalDate candidate : candidates) {
            if (candidate == null || !candidate.isAfter(boundary)) continue;
            if (earliest == null || candidate.isBefore(earliest)) earliest = candidate;
        }
        return earliest;
    }
}
