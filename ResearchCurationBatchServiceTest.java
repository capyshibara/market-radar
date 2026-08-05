import com.marketradar.domain.Category;
import com.marketradar.domain.Classification;
import com.marketradar.domain.GeographyScope;
import com.marketradar.domain.RawDoc;
import com.marketradar.domain.ResearchCurationBatch;
import com.marketradar.domain.Source;
import com.marketradar.domain.SourceAuthority;
import com.marketradar.domain.SourceUsePolicy;
import com.marketradar.extract.ResearchCurationBatchService;
import com.marketradar.extract.ResearchCurationPlanner;
import com.marketradar.repo.ResearchCurationBatchRepository;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Regression contract for marginal-value saturation hand-off. */
public class ResearchCurationBatchServiceTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 6);

    public static void main(String[] args) throws Exception {
        tailMustBeSampledBeforeHandOff();
        plateauedPriorityLaneMovesToAudit();
        noValidationDeltaCertifiesSaturation();
        corroborationDeltaRequiresAnotherSample();
        System.out.println("ResearchCurationBatchServiceTest: ALL PASS");
    }

    private static void tailMustBeSampledBeforeHandOff() throws Exception {
        Plans plans = plans();
        var assessment = service(List.of()).assess(plans.main(), plans.audit());
        check(assessment.recommendation()
                        == ResearchCurationBatchService.Recommendation.RUN_DEFERRED_AUDIT,
                "unseen background tail requires one stratified audit sample");
    }

    private static void noValidationDeltaCertifiesSaturation() throws Exception {
        Plans plans = plans();
        ResearchCurationBatch audit = completedAudit(plans.main().candidateSnapshot(), 10, 1, 0,
                20, 1, 0);
        var assessment = service(List.of(audit)).assess(plans.main(), plans.audit());
        check(assessment.recommendation()
                        == ResearchCurationBatchService.Recommendation.READY_FOR_ANALYST,
                "a tail sample with no corroboration/conflict delta certifies marginal saturation");
        check(assessment.message().contains("saturated"), "handoff must state its saturation basis");
    }

    private static void plateauedPriorityLaneMovesToAudit() throws Exception {
        Plans plans = priorityPlans();
        ResearchCurationBatch main = completedMain(plans.main().candidateSnapshot(), 10, 1, 0,
                20, 1, 0);
        var assessment = service(List.of(main)).assess(plans.main(), plans.audit());
        check(assessment.recommendation()
                        == ResearchCurationBatchService.Recommendation.RUN_DEFERRED_AUDIT,
                "a priority lane with no new corroboration/conflict must move to a tail audit");
    }

    private static void corroborationDeltaRequiresAnotherSample() throws Exception {
        Plans plans = plans();
        ResearchCurationBatch audit = completedAudit(plans.main().candidateSnapshot(), 10, 1, 0,
                20, 2, 0);
        var assessment = service(List.of(audit)).assess(plans.main(), plans.audit());
        check(assessment.recommendation()
                        == ResearchCurationBatchService.Recommendation.AUDIT_FOUND_ADDITIONAL_VALUE,
                "new cross-source validation must force another tail sample");
    }

    private static Plans plans() throws Exception {
        Classification represented = classification(doc(1, source("AUTH", SourceAuthority.REGULATOR),
                "Vietnam insurance brand confidence study",
                repeated("The regulator published a Vietnam insurance brand confidence study."),
                TODAY.minusDays(2)), Category.BRAND_REPUTATION);
        Classification background = classification(doc(2, source("BACKGROUND", SourceAuthority.UNKNOWN),
                "Community sponsorship photo recap",
                repeated("A community sponsorship photo recap was published for local readers."),
                TODAY.minusDays(300)), Category.BRAND_REPUTATION);
        var config = new ResearchCurationPlanner.Config(2, 1, 5, 5, 20, 365, "VN");
        List<Classification> input = List.of(represented, background);
        var main = ResearchCurationPlanner.plan(input, TODAY, config, Set.of(1L),
                ResearchCurationPlanner.Mode.MAIN);
        var audit = ResearchCurationPlanner.plan(input, TODAY, config, Set.of(1L),
                ResearchCurationPlanner.Mode.AUDIT);
        check(main.selected().isEmpty(), "fixture must have no material main cluster left");
        check(audit.selectedDocumentIds().equals(List.of(2L)), "fixture must expose background audit");
        return new Plans(main, audit);
    }

    private static Plans priorityPlans() throws Exception {
        Classification represented = classification(doc(11, source("REG", SourceAuthority.REGULATOR),
                "Vietnam insurance regulation update",
                repeated("The regulator issued a Vietnam insurance regulation update."),
                TODAY.minusDays(2)), Category.INDUSTRY_REGULATION);
        Classification unrepresented = classification(doc(12,
                source("REG", SourceAuthority.REGULATOR),
                "Vietnam insurance regulation implementation guidance",
                repeated("The regulator issued Vietnam insurance regulation implementation guidance."),
                TODAY.minusDays(30)), Category.INDUSTRY_REGULATION);
        var config = new ResearchCurationPlanner.Config(2, 1, 5, 5, 20, 365, "VN");
        List<Classification> input = List.of(represented, unrepresented);
        var main = ResearchCurationPlanner.plan(input, TODAY, config, Set.of(11L),
                ResearchCurationPlanner.Mode.MAIN);
        var audit = ResearchCurationPlanner.plan(input, TODAY, config, Set.of(11L),
                ResearchCurationPlanner.Mode.AUDIT);
        check(!main.selected().isEmpty(), "fixture must retain a material main candidate");
        check(!audit.selected().isEmpty(), "the same unrepresented story must be auditable");
        return new Plans(main, audit);
    }

    private static ResearchCurationBatch completedAudit(String snapshot,
                                                         int eventsBefore, int corroboratedBefore,
                                                         int conflictsBefore, int eventsAfter,
                                                         int corroboratedAfter, int conflictsAfter) {
        ResearchCurationBatch row = new ResearchCurationBatch(ResearchCurationBatch.Mode.AUDIT,
                ResearchCurationPlanner.VERSION, snapshot, "2", "RC:test",
                2, 1, 1, 0, eventsBefore, corroboratedBefore, conflictsBefore);
        row.complete(1, 1, 3, eventsAfter, corroboratedAfter, conflictsAfter);
        return row;
    }

    private static ResearchCurationBatch completedMain(String snapshot,
                                                        int eventsBefore, int corroboratedBefore,
                                                        int conflictsBefore, int eventsAfter,
                                                        int corroboratedAfter, int conflictsAfter) {
        ResearchCurationBatch row = new ResearchCurationBatch(ResearchCurationBatch.Mode.MAIN,
                ResearchCurationPlanner.VERSION, snapshot, "11", "RC:main",
                2, 1, 1, 0, eventsBefore, corroboratedBefore, conflictsBefore);
        row.complete(1, 1, 3, eventsAfter, corroboratedAfter, conflictsAfter);
        return row;
    }

    @SuppressWarnings("unchecked")
    private static ResearchCurationBatchService service(List<ResearchCurationBatch> history) {
        ResearchCurationBatchRepository repository = (ResearchCurationBatchRepository) Proxy.newProxyInstance(
                ResearchCurationBatchRepository.class.getClassLoader(),
                new Class<?>[]{ResearchCurationBatchRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("findAllByOrderByStartedAtDescIdDesc")) return history;
                    if (method.getName().equals("toString")) return "ResearchBatchRepoStub";
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    if (method.getName().equals("equals")) return proxy == args[0];
                    throw new UnsupportedOperationException(method.getName());
                });
        return new ResearchCurationBatchService(repository);
    }

    private static Classification classification(RawDoc doc, Category category) {
        return new Classification(doc, Set.of(category), Classification.Status.CONFIRMED, "{}", "TEST");
    }

    private static Source source(String code, SourceAuthority authority) {
        Source source = new Source(code, code, "https://" + code.toLowerCase() + ".example/news",
                code.toLowerCase() + ".example", Source.SourceType.HTML, 1, "en");
        source.setIntelligenceMetadata(authority, GeographyScope.VIETNAM, "VN");
        source.setUsePolicy(SourceUsePolicy.DECISION_ELIGIBLE);
        return source;
    }

    private static RawDoc doc(long id, Source source, String title, String body, LocalDate date)
            throws Exception {
        Instant published = date.atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
        RawDoc doc = new RawDoc(source, "https://example.com/" + id, title, published,
                Instant.parse("2026-08-06T00:00:00Z"), sha(body), body, "en",
                RawDoc.ParseStatus.OK, null);
        doc.markFullTextAvailable();
        Field field = RawDoc.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(doc, id);
        return doc;
    }

    private static String repeated(String sentence) { return (sentence + " ").repeat(35); }

    private static String sha(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Plans(ResearchCurationPlanner.Plan main, ResearchCurationPlanner.Plan audit) {}
}
