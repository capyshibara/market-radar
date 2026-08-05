package com.marketradar.extract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.GeographyScope;
import com.marketradar.domain.IntelligenceTopic;
import com.marketradar.domain.RawDoc;
import com.marketradar.domain.Source;
import com.marketradar.domain.SourceAuthority;
import com.marketradar.intelligence.EntityResolutionRules;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Offline regression for Router label validation and application. */
public class RouterApplyTest {
    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        EvidenceFact fact = new EvidenceFact("F-TEST", null, EvidenceFact.FactType.EVENT,
                "AIA Việt Nam công bố ngày 10/08/2026, APE tăng 18%.", "vi");
        fact.entityResolution(EntityResolutionRules.resolve("AIA Việt Nam", "VN"));
        FactExtractionJob.applyRouting(fact, mapper.readTree("""
                {
                  "intelligence_topic":" financial_performance ",
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
        check(fact.getIntelligenceTopic() == IntelligenceTopic.FINANCIAL_PERFORMANCE,
                "core topic is independent from report bucket");
        check("AIA Việt Nam".equals(fact.getSubjectKey()), "subject is trimmed without translation");
        check("PRODUCT_LAUNCH".equals(fact.getHighlightCardLabel()), "card label is normalized");
        check(fact.getSeverity() == null, "unknown severity is rejected");
        check("RISING".equals(fact.getSeverityTrend()), "valid trend is normalized");
        check(LocalDate.of(2026, 8, 10).equals(fact.getEventDateRangeStart()), "valid date is stored");
        check(fact.getEventDateRangeEnd() == null, "invalid date is ignored");
        check(fact.isHighlight(), "highlight is applied");
        check("18%".equals(fact.getKpiValue()), "KPI value must be verbatim in evidence");

        EvidenceFact invalid = new EvidenceFact("F-BAD", null, EvidenceFact.FactType.EVENT,
                "verbatim", "en");
        FactExtractionJob.applyRouting(invalid,
                mapper.readTree("{\"bucket\":\"DEEP_DIVE\",\"highlight\":false}"));
        check(invalid.getBiBucket() == null,
                "per-fact Router cannot assign the Connector-only DEEP_DIVE bucket");

        EvidenceFact batchFirst = new EvidenceFact("F-B1", null, EvidenceFact.FactType.EVENT,
                "First fact", "en");
        EvidenceFact batchSecond = new EvidenceFact("F-B2", null, EvidenceFact.FactType.METRIC,
                "Second fact contains 21%", "en");
        FactExtractionJob.applyBatchRouting(List.of(batchFirst, batchSecond), mapper.readTree("""
                {"routes":[
                  {"fact_index":1,"intelligence_topic":"MARKET_SHARE",
                   "bucket":"MARKET_SHARE_OR_AWARD","kpi_value":"21%","highlight":true},
                  {"fact_index":0,"intelligence_topic":"CORPORATE_ACTION",
                   "bucket":"COMPANY_EVENT","highlight":false},
                  {"fact_index":1,"intelligence_topic":"OTHER",
                   "bucket":"COMPANY_EVENT","highlight":false},
                  {"fact_index":99,"intelligence_topic":"OTHER",
                   "bucket":"COMPANY_EVENT","highlight":false}
                ]}
                """));
        check("COMPANY_EVENT".equals(batchFirst.getBiBucket()),
                "batch routing maps an out-of-order result by fact_index");
        check("MARKET_SHARE_OR_AWARD".equals(batchSecond.getBiBucket()),
                "batch routing applies each valid index exactly once");
        check(batchSecond.getIntelligenceTopic() == IntelligenceTopic.MARKET_SHARE,
                "duplicate indexes cannot overwrite the first accepted route");
        check("21%".equals(batchSecond.getKpiValue()),
                "batch routing preserves the same verbatim KPI gate");

        Source globalSource = new Source("GLOBAL_TEST", "Global test",
                "https://example.com/news", "example.com", Source.SourceType.HTML, 3, "en");
        globalSource.setIntelligenceMetadata(SourceAuthority.ESTABLISHED_MEDIA,
                GeographyScope.GLOBAL, "GLOBAL");
        RawDoc multiCompanyArticle = new RawDoc(globalSource, "https://example.com/news/1",
                "Prudential plc and AIA Group market update", Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-01T01:00:00Z"), "a".repeat(64),
                "Prudential Financial reported a US metric.", "en", RawDoc.ParseStatus.OK, null);
        EvidenceFact usFact = new EvidenceFact("F-US", multiCompanyArticle,
                EvidenceFact.FactType.METRIC,
                "Prudential Financial reported a US metric.", "en")
                .company("Prudential Financial");
        FactExtractionJob.enrichCoreDimensions(usFact, multiCompanyArticle);
        check("PRUDENTIAL_FINANCIAL_US".equals(usFact.getSubjectEntityKey()),
                "fact-level entity is resolved from its exact span before a multi-company title");
        check("US".equals(usFact.getMarketCode()),
                "fact entity overrides broad source market without inheriting headline entities");

        EvidenceFact headlineOnly = new EvidenceFact("F-HEADLINE", multiCompanyArticle,
                EvidenceFact.FactType.METRIC, "APE increased by 18%.", "en")
                .company("Prudential plc");
        FactExtractionJob.enrichCoreDimensions(headlineOnly, multiCompanyArticle);
        check(headlineOnly.getEntityResolutionStatus() == EntityResolutionRules.Status.UNRESOLVED,
                "model company field and article title cannot substitute for a name in the exact span");
        check(headlineOnly.getSubjectEntityKey() == null,
                "headline-only attribution remains quarantined instead of becoming an entity fact");

        System.out.println("RouterApplyTest: ALL PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError("Failed: " + message);
    }
}
