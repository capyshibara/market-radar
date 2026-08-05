import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.GeographyScope;
import com.marketradar.domain.IntelligenceTopic;
import com.marketradar.domain.RawDoc;
import com.marketradar.domain.Source;
import com.marketradar.domain.SourceAuthority;
import com.marketradar.domain.SourceUsePolicy;
import com.marketradar.domain.TemporalRole;
import com.marketradar.intelligence.EntityResolutionRules;
import com.marketradar.interpret.AnalystInputSelection;

import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Regression controls for the bounded, source-diverse paid Analyst input. */
public class AnalystInputSelectionTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 5);
    private static final AtomicLong NEXT_ID = new AtomicLong(1);

    public static void main(String[] args) {
        preservesAcquisitionAndMarketBreadth();
        quarantinesUnsafeAndTemporallyInvalidFacts();
        boundsExecutivePromptAndPrefersDecisionEvidence();
        laterBatchContinuesIntoEligibleTail();
        System.out.println("AnalystInputSelectionTest: ALL PASS");
    }

    private static void preservesAcquisitionAndMarketBreadth() {
        Source official = source("MOF", SourceAuthority.REGULATOR, "VN");
        Source research = source("BCG", SourceAuthority.PROFESSIONAL_SERVICES, "GLOBAL");
        Source manual = source("MANUAL", SourceAuthority.SPECIALIST_RESEARCH, "VN");
        List<EvidenceFact> facts = List.of(
                fact(doc(official, "official", RawDoc.IntakeMethod.CRAWLED, TODAY.minusDays(2)),
                        "F-1", IntelligenceTopic.REGULATION_POLICY, "VN", SourceAuthority.REGULATOR),
                fact(doc(research, "research", RawDoc.IntakeMethod.OPEN_SEARCH, TODAY.minusDays(8)),
                        "F-2", IntelligenceTopic.TECHNOLOGY_AI, "GLOBAL", SourceAuthority.PROFESSIONAL_SERVICES),
                fact(doc(manual, "manual", RawDoc.IntakeMethod.FILE_UPLOAD, TODAY.minusDays(4)),
                        "F-3", IntelligenceTopic.FINANCIAL_PERFORMANCE, "VN", SourceAuthority.SPECIALIST_RESEARCH));

        var selection = AnalystInputSelection.select(facts, TODAY,
                new AnalystInputSelection.Config(3, 6, 3, 2, 365, "VN"));
        check(selection.selectedByDocument().size() == 3, "all three eligible lanes should fit");
        check(selection.diagnostics().selectedByAcquisition()
                        .get(AnalystInputSelection.AcquisitionLane.WHITELIST) == 1,
                "whitelist lane retained");
        check(selection.diagnostics().selectedByAcquisition()
                        .get(AnalystInputSelection.AcquisitionLane.DEEP_RESEARCH) == 1,
                "deep-research lane retained");
        check(selection.diagnostics().selectedByAcquisition()
                        .get(AnalystInputSelection.AcquisitionLane.MANUAL) == 1,
                "manual lane retained");
        check(selection.diagnostics().selectedByMarket()
                        .get(AnalystInputSelection.MarketLane.VIETNAM) == 2,
                "Vietnam evidence remains the anchor");
        check(selection.diagnostics().selectedByMarket()
                        .get(AnalystInputSelection.MarketLane.INTERNATIONAL) == 1,
                "international transfer context remains visible");
    }

    private static void quarantinesUnsafeAndTemporallyInvalidFacts() {
        Source source = source("OFFICIAL", SourceAuthority.OFFICIAL_COMPANY, "VN");
        EvidenceFact safe = fact(doc(source, "safe", RawDoc.IntakeMethod.CRAWLED, TODAY.minusDays(2)),
                "F-10", IntelligenceTopic.PRODUCT_OFFER, "VN", SourceAuthority.OFFICIAL_COMPANY);
        EvidenceFact unsafe = new EvidenceFact("F-11",
                doc(source, "unsafe", RawDoc.IntakeMethod.CRAWLED, TODAY.minusDays(2)),
                EvidenceFact.FactType.METRIC,
                "Prudential announced results without a unique legal entity.", "en")
                .eventDate(TODAY.minusDays(2))
                .intelligenceTopic(IntelligenceTopic.FINANCIAL_PERFORMANCE)
                .sourceAuthority(SourceAuthority.OFFICIAL_COMPANY)
                .geography(GeographyScope.VIETNAM, "VN")
                .temporalRole(TemporalRole.OCCURRED)
                .entityResolution(EntityResolutionRules.resolve("Prudential announced results", "GLOBAL"));
        EvidenceFact stale = fact(doc(source, "stale", RawDoc.IntakeMethod.CRAWLED, TODAY.minusDays(500)),
                "F-12", IntelligenceTopic.PRODUCT_OFFER, "VN", SourceAuthority.OFFICIAL_COMPANY);
        EvidenceFact scheduled = fact(doc(source, "scheduled", RawDoc.IntakeMethod.CRAWLED, TODAY.plusDays(30)),
                "F-14", IntelligenceTopic.CORPORATE_ACTION, "VN", SourceAuthority.OFFICIAL_COMPANY)
                .temporalRole(TemporalRole.SCHEDULED);
        RawDoc undatedDoc = doc(source, "undated", RawDoc.IntakeMethod.CRAWLED, null);
        EvidenceFact undated = fact(undatedDoc, "F-13", IntelligenceTopic.PRODUCT_OFFER,
                "VN", SourceAuthority.OFFICIAL_COMPANY).eventDate(null);

        var selection = AnalystInputSelection.select(List.of(safe, unsafe, stale, undated, scheduled), TODAY,
                new AnalystInputSelection.Config(10, 6, 10, 3, 365, "VN"));
        check(selection.selectedByDocument().size() == 2,
                "current and near-future scheduled evidence are paid input");
        check(selection.diagnostics().quarantinedEntityFacts() == 1, "ambiguous entity is quarantined");
        check(selection.diagnostics().excludedStaleFacts() == 1, "stale fact is explicit");
        check(selection.diagnostics().excludedUndatedFacts() == 1, "undated fact is explicit");
    }

    private static void boundsExecutivePromptAndPrefersDecisionEvidence() {
        Source regulator = source("REG", SourceAuthority.REGULATOR, "VN");
        Source media = source("MEDIA", SourceAuthority.ESTABLISHED_MEDIA, "US");
        List<EvidenceFact> facts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            facts.add(fact(doc(regulator, "reg-" + i, RawDoc.IntakeMethod.CRAWLED, TODAY.minusDays(i)),
                    "F-R" + i, IntelligenceTopic.REGULATION_POLICY, "VN", SourceAuthority.REGULATOR));
            facts.add(fact(doc(media, "media-" + i, RawDoc.IntakeMethod.CRAWLED, TODAY.minusDays(30 + i)),
                    "F-M" + i, IntelligenceTopic.BRAND_REPUTATION, "US", SourceAuthority.ESTABLISHED_MEDIA));
        }
        var selection = AnalystInputSelection.select(facts, TODAY,
                new AnalystInputSelection.Config(4, 3, 2, 2, 365, "VN"));
        check(selection.selectedByDocument().size() == 4, "document budget is exact");
        check(selection.executiveFacts().size() == 2, "executive prompt fact budget is exact");
        check(selection.selectedByDocument().values().stream().flatMap(List::stream)
                        .anyMatch(f -> "F-R0".equals(f.getFactCode())),
                "fresh domestic regulator evidence must surface");
    }

    private static void laterBatchContinuesIntoEligibleTail() {
        Source source = source("BATCH", SourceAuthority.ESTABLISHED_MEDIA, "VN");
        List<EvidenceFact> facts = List.of(
                fact(doc(source, "batch-a", RawDoc.IntakeMethod.CRAWLED, TODAY.minusDays(1)),
                        "F-B1", IntelligenceTopic.REGULATION_POLICY, "VN", SourceAuthority.ESTABLISHED_MEDIA),
                fact(doc(source, "batch-b", RawDoc.IntakeMethod.CRAWLED, TODAY.minusDays(2)),
                        "F-B2", IntelligenceTopic.DISTRIBUTION, "VN", SourceAuthority.ESTABLISHED_MEDIA),
                fact(doc(source, "batch-c", RawDoc.IntakeMethod.CRAWLED, TODAY.minusDays(3)),
                        "F-B3", IntelligenceTopic.PRODUCT_OFFER, "VN", SourceAuthority.ESTABLISHED_MEDIA));
        var config = new AnalystInputSelection.Config(2, 3, 3, 3, 365, "VN");
        var first = AnalystInputSelection.select(facts, TODAY, config);
        Set<Long> represented = first.selectedByDocument().keySet().stream()
                .map(RawDoc::getId).collect(java.util.stream.Collectors.toSet());
        var second = AnalystInputSelection.select(facts, TODAY, config, represented);
        check(first.selectedByDocument().size() == 2, "first Analyst action is bounded");
        check(second.selectedByDocument().size() == 1, "next action reaches the eligible tail");
        check(second.eligibleByDocument().size() == 3,
                "publication coverage keeps the complete eligible corpus");
        check(second.selectedByDocument().keySet().stream()
                        .noneMatch(doc -> represented.contains(doc.getId())),
                "current interpretation editions are not paid twice");
    }

    private static Source source(String code, SourceAuthority authority, String market) {
        Source source = new Source(code, code, "https://" + code.toLowerCase() + ".example/news",
                code.toLowerCase() + ".example", Source.SourceType.HTML, 1, "en");
        source.setIntelligenceMetadata(authority,
                "VN".equals(market) ? GeographyScope.VIETNAM : GeographyScope.GLOBAL, market);
        source.setUsePolicy(SourceUsePolicy.DECISION_ELIGIBLE);
        return source;
    }

    private static RawDoc doc(Source source, String key, RawDoc.IntakeMethod method, LocalDate date) {
        Instant published = date == null ? null : date.atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
        String text = "Full evidence text for " + key + " ".repeat(30);
        RawDoc doc = new RawDoc(source, "https://example.com/" + key, key, published,
                Instant.parse("2026-08-05T00:00:00Z"), sha(text), text, "en",
                RawDoc.ParseStatus.OK, null);
        doc.setIntakeMethod(method);
        doc.markFullTextAvailable();
        try {
            Field id = RawDoc.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(doc, NEXT_ID.getAndIncrement());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return doc;
    }

    private static EvidenceFact fact(RawDoc doc, String code, IntelligenceTopic topic,
                                     String market, SourceAuthority authority) {
        String span = "The insurance market published a traceable current development for analysis.";
        return new EvidenceFact(code, doc, EvidenceFact.FactType.EVENT, span, "en")
                .eventDate(doc.getPublishedAt() == null ? null
                        : doc.getPublishedAt().atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate())
                .intelligenceTopic(topic)
                .sourceAuthority(authority)
                .geography("VN".equals(market) ? GeographyScope.VIETNAM : GeographyScope.GLOBAL, market)
                .temporalRole(TemporalRole.OCCURRED)
                .entityResolution(EntityResolutionRules.resolve(span, market));
    }

    private static String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
