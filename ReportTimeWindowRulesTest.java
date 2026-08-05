import com.marketradar.intelligence.ReportTimeWindowRules;

import java.time.LocalDate;

/** Regression controls for CFO report-period semantics. */
public class ReportTimeWindowRulesTest {
    public static void main(String[] args) {
        LocalDate start = LocalDate.of(2026, 7, 1);
        LocalDate end = LocalDate.of(2026, 7, 31);

        check(new ReportTimeWindowRules.Dates(LocalDate.of(2026, 7, 10),
                        LocalDate.of(2026, 7, 9), null, null, null), start, end,
                ReportTimeWindowRules.Inclusion.CURRENT_EVENT, "event in window");
        check(new ReportTimeWindowRules.Dates(LocalDate.of(2026, 7, 10),
                        LocalDate.of(2024, 5, 1), null, null, null), start, end,
                ReportTimeWindowRules.Inclusion.NEW_DISCLOSURE_WITH_HISTORICAL_CONTEXT,
                "new disclosure does not pretend historical event is current");
        check(new ReportTimeWindowRules.Dates(LocalDate.of(2026, 7, 20),
                        null, null, null, LocalDate.of(2026, 8, 20)), start, end,
                ReportTimeWindowRules.Inclusion.UPCOMING_ANNOUNCED_EVENT, "upcoming calendar");
        check(new ReportTimeWindowRules.Dates(LocalDate.of(2026, 7, 15),
                        null, LocalDate.of(2026, 8, 15), null, null), start, end,
                ReportTimeWindowRules.Inclusion.UPCOMING_ANNOUNCED_EVENT,
                "announced future effective date enters calendar");
        check(new ReportTimeWindowRules.Dates(LocalDate.of(2026, 7, 15),
                        null, null, LocalDate.of(2026, 8, 10), null), start, end,
                ReportTimeWindowRules.Inclusion.UPCOMING_ANNOUNCED_EVENT,
                "announced future expiry enters calendar");
        check(new ReportTimeWindowRules.Dates(null, null, null, null, null), start, end,
                ReportTimeWindowRules.Inclusion.EXCLUDE, "undated excluded");
        check(new ReportTimeWindowRules.Dates(LocalDate.of(2026, 6, 1),
                        LocalDate.of(2026, 6, 1), null, null, null), start, end,
                ReportTimeWindowRules.Inclusion.EXCLUDE, "old item excluded");
        System.out.println("ReportTimeWindowRulesTest: ALL PASS");
    }

    private static void check(ReportTimeWindowRules.Dates dates, LocalDate start, LocalDate end,
                              ReportTimeWindowRules.Inclusion expected, String label) {
        var actual = ReportTimeWindowRules.classify(dates, start, end, 90);
        if (actual != expected) throw new AssertionError(label + ": expected " + expected + " but got " + actual);
    }
}
