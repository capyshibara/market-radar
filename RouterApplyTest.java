package com.marketradar.extract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketradar.domain.EvidenceFact;

import java.time.LocalDate;

/** Offline regression for Router label validation and application. */
public class RouterApplyTest {
    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        EvidenceFact fact = new EvidenceFact("F-TEST", null, EvidenceFact.FactType.EVENT,
                "verbatim", "vi");
        FactExtractionJob.applyRouting(fact, mapper.readTree("""
                {
                  "bucket":" company_event ",
                  "subject_key":" AIA Việt Nam ",
                  "highlight_card_label":" product launch ",
                  "severity":"critical",
                  "severity_trend":"rising",
                  "event_date_range_start":"2026-08-10",
                  "event_date_range_end":"not-a-date",
                  "kpi_label":"APE growth",
                  "kpi_value":"18%",
                  "highlight":true
                }
                """));

        check("COMPANY_EVENT".equals(fact.getBiBucket()), "valid bucket is normalized");
        check("AIA Việt Nam".equals(fact.getSubjectKey()), "subject is trimmed without translation");
        check("PRODUCT_LAUNCH".equals(fact.getHighlightCardLabel()), "card label is normalized");
        check(fact.getSeverity() == null, "unknown severity is rejected");
        check("RISING".equals(fact.getSeverityTrend()), "valid trend is normalized");
        check(LocalDate.of(2026, 8, 10).equals(fact.getEventDateRangeStart()), "valid date is stored");
        check(fact.getEventDateRangeEnd() == null, "invalid date is ignored");
        check(fact.isHighlight(), "highlight is applied");

        EvidenceFact invalid = new EvidenceFact("F-BAD", null, EvidenceFact.FactType.EVENT,
                "verbatim", "en");
        FactExtractionJob.applyRouting(invalid,
                mapper.readTree("{\"bucket\":\"DEEP_DIVE\",\"highlight\":false}"));
        check(invalid.getBiBucket() == null,
                "per-fact Router cannot assign the Connector-only DEEP_DIVE bucket");

        System.out.println("RouterApplyTest: ALL PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError("Failed: " + message);
    }
}
