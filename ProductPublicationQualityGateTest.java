import com.marketradar.quality.ProductPublicationQualityGate;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Dependency-free regression suite. The bad cases mirror the frozen defects in
 * docs/run-archive/2026-07-16-current-report-baseline.md.
 *
 * Run:
 * javac -d /tmp/publication-quality-test src/main/java/com/marketradar/quality/ProductPublicationQualityGate.java ProductPublicationQualityGateTest.java
 * java -ea -cp /tmp/publication-quality-test ProductPublicationQualityGateTest
 */
public class ProductPublicationQualityGateTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 7, 16);
    private static int checks;

    public static void main(String[] args) {
        requiresEveryCitationToResolve();
        rejectsWrongDepartmentRecommendation();
        rejectsCompetitorDepartmentAsActionOwner();
        rejectsPromotionInRegulationChapter();
        rejectsExpiredCertificateConversionAction();
        requiresProductKiq();
        rejectsOneArticlePresentedAsTrend();
        keepsSingleSourceSignalOnWatch();
        rejectsGenericMonitorRecommendation();
        rejectsLegacyModelMix();
        rejectsEvidenceOutsideEditionWindow();
        requiresEventAndSourceProvenance();
        acceptsDecisionReadyInsight();
        publishesSparseSafeMagazineAsWatchBrief();
        refusesMagazineWithoutSafeInsights();
        publishesThreeDecisionReadyInsights();
        exposesMachineReadableReasons();
        System.out.println("ProductPublicationQualityGateTest: " + checks + " checks passed");
    }

    private static void requiresEveryCitationToResolve() {
        var result = new Candidate().cited("F-101", "F-999").resolved("F-101").buildResult();
        check(result.disposition() == ProductPublicationQualityGate.Disposition.REJECT, "unresolved evidence must reject");
        check(result.resolvedEvidenceRatio() == 0.5, "resolution ratio must be exact");
        has(result, ProductPublicationQualityGate.FailureCode.UNRESOLVED_EVIDENCE);
    }

    private static void rejectsWrongDepartmentRecommendation() {
        var result = new Candidate().department("MARKETING").audience("MARKETING").buildResult();
        has(result, ProductPublicationQualityGate.FailureCode.WRONG_DEPARTMENT_AUDIENCE);
    }

    private static void rejectsCompetitorDepartmentAsActionOwner() {
        var result = new Candidate()
                .external("Fubon Life")
                .owner("Fubon Life")
                .action("Phòng Marketing của Fubon Life nên theo dõi phản hồi khách hàng.")
                .buildResult();
        has(result, ProductPublicationQualityGate.FailureCode.EXTERNAL_ENTITY_AS_ACTION_OWNER);
    }

    private static void rejectsPromotionInRegulationChapter() {
        var result = new Candidate().theme("VN_REGULATORY_CHANGE").eventType("MARKETING_PROMOTION").buildResult();
        has(result, ProductPublicationQualityGate.FailureCode.CHAPTER_CATEGORY_CONTAMINATION);
    }

    private static void rejectsExpiredCertificateConversionAction() {
        var result = new Candidate().futureAction(true)
                .deadline(LocalDate.of(2025, 12, 31))
                .effective(LocalDate.of(2026, 1, 1))
                .buildResult();
        has(result, ProductPublicationQualityGate.FailureCode.ACTION_DEADLINE_PASSED);
        has(result, ProductPublicationQualityGate.FailureCode.EFFECTIVE_DATE_PASSED);
    }

    private static void requiresProductKiq() {
        var result = new Candidate().kiqs("HR-KIQ-01").buildResult();
        has(result, ProductPublicationQualityGate.FailureCode.MISSING_PRODUCT_KIQ);
    }

    private static void rejectsOneArticlePresentedAsTrend() {
        var result = new Candidate().trend(true).events("EV-1").sources("SOURCE-1").buildResult();
        has(result, ProductPublicationQualityGate.FailureCode.TREND_INSUFFICIENT_EVENTS);
        has(result, ProductPublicationQualityGate.FailureCode.TREND_INSUFFICIENT_SOURCES);
        check(result.disposition() == ProductPublicationQualityGate.Disposition.REJECT, "unsupported trend must reject");
    }

    private static void keepsSingleSourceSignalOnWatch() {
        var result = new Candidate().trend(false).pattern("One documented signal, not a trend")
                .events("EV-1").sources("SOURCE-1").buildResult();
        check(result.disposition() == ProductPublicationQualityGate.Disposition.WATCH, "single source must remain WATCH");
        has(result, ProductPublicationQualityGate.FailureCode.SINGLE_SOURCE_WATCH);
    }

    private static void rejectsGenericMonitorRecommendation() {
        var result = new Candidate().action("Product team should monitor the market.").buildResult();
        has(result, ProductPublicationQualityGate.FailureCode.GENERIC_MONITOR_ACTION);
    }

    private static void rejectsLegacyModelMix() {
        var result = new Candidate().models("gpt-4o-mini", "gpt-5-mini").buildResult();
        has(result, ProductPublicationQualityGate.FailureCode.LEGACY_VERSION_MIX);
    }

    private static void rejectsEvidenceOutsideEditionWindow() {
        var result = new Candidate().published(LocalDate.of(2025, 11, 1), LocalDate.of(2026, 7, 10)).buildResult();
        has(result, ProductPublicationQualityGate.FailureCode.STALE_INPUT);
    }

    private static void requiresEventAndSourceProvenance() {
        var result = new Candidate().events().sources().buildResult();
        has(result, ProductPublicationQualityGate.FailureCode.MISSING_EVENT_PROVENANCE);
        has(result, ProductPublicationQualityGate.FailureCode.MISSING_SOURCE_PROVENANCE);
    }

    private static void acceptsDecisionReadyInsight() {
        var result = new Candidate().buildResult();
        check(result.disposition() == ProductPublicationQualityGate.Disposition.DECISION_READY,
                "fully supported Product insight should be decision-ready: " + result.findings());
        check(result.resolvedEvidenceRatio() == 1.0, "decision-ready insight has 100% citation resolution");
        check(result.findings().isEmpty(), "decision-ready insight has no failures");
    }

    private static void publishesSparseSafeMagazineAsWatchBrief() {
        var magazine = ProductPublicationQualityGate.evaluateMagazine(List.of(
                new Candidate().id("I-1").build(), new Candidate().id("I-2").build()));
        check(magazine.status() == ProductPublicationQualityGate.MagazineStatus.WATCH_BRIEF,
                "one or two safe grounded insights must publish as a Current Watch Brief");
        check(magazine.decisionReadyInsights() == 2, "ready count remains auditable");
        check(magazine.findings().get(0).code()
                        == ProductPublicationQualityGate.FailureCode.INSUFFICIENT_DECISION_READY_INSIGHTS,
                "watch brief exposes machine-readable decision-brief threshold reason");
        check(magazine.findings().get(0).severity() == ProductPublicationQualityGate.Severity.WATCH,
                "a safe Watch Brief must be a warning, not a rejection");
    }

    private static void refusesMagazineWithoutSafeInsights() {
        var magazine = ProductPublicationQualityGate.evaluateMagazine(List.of(
                new Candidate().id("I-1").cited().build(),
                new Candidate().id("I-2").action("").build()));
        check(magazine.status() == ProductPublicationQualityGate.MagazineStatus.INSUFFICIENT_EVIDENCE,
                "rejected candidates cannot become a Watch Brief");
        check(magazine.decisionReadyInsights() == 0 && magazine.watchInsights() == 0,
                "no safe candidate must remain visible in the audit counts");
        check(magazine.findings().get(0).severity() == ProductPublicationQualityGate.Severity.REJECT,
                "no-safe-signal result remains a publication rejection");
    }

    private static void publishesThreeDecisionReadyInsights() {
        var magazine = ProductPublicationQualityGate.evaluateMagazine(List.of(
                new Candidate().id("I-1").build(), new Candidate().id("I-2").build(), new Candidate().id("I-3").build()));
        check(magazine.status() == ProductPublicationQualityGate.MagazineStatus.READY,
                "3 decision-ready insights may publish");
        check(magazine.findings().isEmpty(), "ready magazine has no edition failure");
    }

    private static void exposesMachineReadableReasons() {
        var result = new Candidate().cited().kiqs().action("").buildResult();
        check(result.failureCodes().containsAll(Set.of(
                        ProductPublicationQualityGate.FailureCode.NO_CITATIONS,
                        ProductPublicationQualityGate.FailureCode.MISSING_PRODUCT_KIQ,
                        ProductPublicationQualityGate.FailureCode.MISSING_ACTION)),
                "failure codes must be programmatically consumable");
    }

    private static void has(ProductPublicationQualityGate.InsightResult result,
                            ProductPublicationQualityGate.FailureCode code) {
        check(result.failureCodes().contains(code), "expected " + code + " but found " + result.findings());
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static final class Candidate {
        private String id = "I-BASE";
        private String department = "PRODUCT";
        private String audience = "INTERNAL_PRODUCT";
        private String owner = "OUR_PRODUCT_TEAM";
        private Set<String> external = Set.of("Competitor Life");
        private Set<String> kiqs = Set.of("P-KIQ-01");
        private String theme = "VN_PRODUCT_OFFER";
        private String eventType = "PRODUCT_CHANGE";
        private Set<String> cited = Set.of("F-101", "F-102");
        private Set<String> resolved = Set.of("F-101", "F-102");
        private Set<String> events = Set.of("EV-1", "EV-2");
        private Set<String> sources = Set.of("SOURCE-1", "SOURCE-2");
        private String pattern = "Two independent events support a testable pattern.";
        private String action = "Product team should compare benefit gaps and prototype two options for a go/no-go decision.";
        private boolean trend;
        private boolean futureAction;
        private LocalDate deadline = AS_OF.plusDays(30);
        private LocalDate effective = AS_OF.plusDays(45);
        private Set<LocalDate> published = Set.of(AS_OF.minusDays(2), AS_OF.minusDays(8));
        private Set<String> models = Set.of("gpt-5-mini-2026-06");
        private Set<String> pipelines = Set.of("market-event-v2");

        Candidate id(String value) { id = value; return this; }
        Candidate department(String value) { department = value; return this; }
        Candidate audience(String value) { audience = value; return this; }
        Candidate owner(String value) { owner = value; return this; }
        Candidate external(String... values) { external = Set.of(values); return this; }
        Candidate kiqs(String... values) { kiqs = Set.of(values); return this; }
        Candidate theme(String value) { theme = value; return this; }
        Candidate eventType(String value) { eventType = value; return this; }
        Candidate cited(String... values) { cited = Set.of(values); return this; }
        Candidate resolved(String... values) { resolved = Set.of(values); return this; }
        Candidate events(String... values) { events = Set.of(values); return this; }
        Candidate sources(String... values) { sources = Set.of(values); return this; }
        Candidate pattern(String value) { pattern = value; return this; }
        Candidate action(String value) { action = value; return this; }
        Candidate trend(boolean value) { trend = value; return this; }
        Candidate futureAction(boolean value) { futureAction = value; return this; }
        Candidate deadline(LocalDate value) { deadline = value; return this; }
        Candidate effective(LocalDate value) { effective = value; return this; }
        Candidate published(LocalDate... values) { published = Set.of(values); return this; }
        Candidate models(String... values) { models = Set.of(values); return this; }

        ProductPublicationQualityGate.InsightCandidate build() {
            return new ProductPublicationQualityGate.InsightCandidate(id, department, audience, owner, external, kiqs,
                    theme, eventType, cited, resolved, events, sources, pattern, action, trend, futureAction,
                    deadline, effective, AS_OF, AS_OF.minusDays(90), AS_OF, published, models, pipelines);
        }

        ProductPublicationQualityGate.InsightResult buildResult() {
            return ProductPublicationQualityGate.evaluate(build());
        }
    }
}
