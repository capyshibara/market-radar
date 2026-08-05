import com.marketradar.domain.Category;
import com.marketradar.domain.Classification;
import com.marketradar.domain.GeographyScope;
import com.marketradar.domain.RawDoc;
import com.marketradar.domain.Source;
import com.marketradar.domain.SourceAuthority;
import com.marketradar.domain.SourceUsePolicy;
import com.marketradar.extract.ResearchCurationPlanner;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Regression controls for event-first, batch/audit Researcher admission. */
public class ResearchCurationPlannerTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 6);

    public static void main(String[] args) throws Exception {
        clustersOnlyHighConfidenceRepublicationsAndKeepsIndependentSources();
        incompatibleEntityOrDateFailsOpenAsSeparateStory();
        laterBatchesReachTheTailInsteadOfApplyingAnArbitraryCut();
        auditSamplesBothUnrepresentedStoriesAndDeferredRepublications();
        priorityTailMovesToSaturationAuditAfterCoverage();
        terminalAttemptDoesNotLoopAndRemainsVisible();
        System.out.println("ResearchCurationPlannerTest: ALL PASS");
    }

    private static void clustersOnlyHighConfidenceRepublicationsAndKeepsIndependentSources()
            throws Exception {
        String title = "Prudential Vietnam and VIB renew strategic cooperation agreement";
        String body = repeated("Prudential Vietnam and VIB renew their strategic cooperation agreement "
                + "to improve customer service and distribution across Vietnam.");
        Classification original = classification(doc(1, source("PRUDENTIAL_VN", "Prudential Vietnam",
                SourceAuthority.OFFICIAL_COMPANY), title, body, TODAY.minusDays(2)),
                Category.DISTRIBUTION_CHANNEL);
        Classification bank = classification(doc(2, source("VIB", "VIB",
                SourceAuthority.OFFICIAL_COMPANY), title, body, TODAY.minusDays(2)),
                Category.DISTRIBUTION_CHANNEL);
        Classification repost = classification(doc(3, source("MEDIA", "Media",
                SourceAuthority.ESTABLISHED_MEDIA), title, body, TODAY.minusDays(3)),
                Category.DISTRIBUTION_CHANNEL);

        var plan = plan(List.of(original, bank, repost), Set.of(),
                new ResearchCurationPlanner.Config(1, 2, 2, 5, 20, 365, "VN"),
                ResearchCurationPlanner.Mode.MAIN);
        check(plan.diagnostics().candidateClusters() == 1, "three near-identical articles form one story");
        check(plan.selected().size() == 2, "primary plus independent publisher are retained");
        check(plan.selected().stream().map(ResearchCurationPlanner.Candidate::sourceCode).distinct().count() == 2,
                "representatives must come from distinct publishers");
        check(plan.diagnostics().redundantDocuments() == 2,
                "third and additional members remain visible in the audit pool");
    }

    private static void incompatibleEntityOrDateFailsOpenAsSeparateStory() throws Exception {
        String body = repeated("Prudential Vietnam publishes financial and product information for customers.");
        Classification vietnam = classification(doc(10, source("PRU_VN", "Prudential Vietnam",
                SourceAuthority.OFFICIAL_COMPANY), "Prudential Vietnam publishes annual results",
                body, TODAY.minusDays(2)), Category.COMPANY_FINANCIAL_PERFORMANCE);
        Classification us = classification(doc(11, source("PRU_US", "Prudential Financial",
                SourceAuthority.OFFICIAL_COMPANY), "Prudential Financial publishes annual results",
                repeated("Prudential Financial and PGIM publish annual results in the United States."),
                TODAY.minusDays(2)), Category.COMPANY_FINANCIAL_PERFORMANCE);
        Classification old = classification(doc(12, source("PRU_VN_OLD", "Prudential Vietnam archive",
                SourceAuthority.OFFICIAL_COMPANY), "Prudential Vietnam publishes annual results",
                body, TODAY.minusDays(40)), Category.COMPANY_FINANCIAL_PERFORMANCE);

        var plan = plan(List.of(vietnam, us, old), Set.of(),
                new ResearchCurationPlanner.Config(5, 2, 2, 5, 20, 365, "VN"),
                ResearchCurationPlanner.Mode.MAIN);
        check(plan.diagnostics().candidateClusters() == 3,
                "different legal entity or distant publication date must fail open as separate stories");
    }

    private static void laterBatchesReachTheTailInsteadOfApplyingAnArbitraryCut() throws Exception {
        Classification regulator = classification(doc(20, source("MOF", "Ministry of Finance",
                SourceAuthority.REGULATOR), "Vietnam insurance regulation issued",
                repeated("The Ministry of Finance of Vietnam issued an insurance regulation."),
                TODAY.minusDays(1)), Category.INDUSTRY_REGULATION);
        Classification lowerRankedUnique = classification(doc(21, source("SPECIALIST", "Specialist",
                SourceAuthority.OTHER_PUBLISHER), "A unique distribution experiment in rural Vietnam",
                repeated("A unique distribution experiment was launched for rural customers in Vietnam."),
                TODAY.minusDays(90)), Category.DISTRIBUTION_CHANNEL);
        var config = new ResearchCurationPlanner.Config(1, 1, 2, 5, 20, 365, "VN");
        var first = plan(List.of(regulator, lowerRankedUnique), Set.of(), config,
                ResearchCurationPlanner.Mode.MAIN);
        check(first.selected().size() == 1, "first bounded batch contains one cluster");
        long firstId = first.selected().get(0).document().getId();
        var second = plan(List.of(regulator, lowerRankedUnique), Set.of(firstId), config,
                ResearchCurationPlanner.Mode.MAIN);
        check(second.selected().size() == 1, "a later batch must continue into the tail");
        check(second.selected().get(0).document().getId() != firstId,
                "lower-ranked unique evidence is deferred, never silently discarded");
    }

    private static void auditSamplesBothUnrepresentedStoriesAndDeferredRepublications() throws Exception {
        String body = repeated("AIA Vietnam launched a digital customer experience programme this month.");
        Classification lead = classification(doc(30, source("AIA", "AIA Vietnam",
                SourceAuthority.OFFICIAL_COMPANY), "AIA Vietnam launches digital customer experience",
                body, TODAY.minusDays(2)), Category.CUSTOMER_EXPERIENCE);
        Classification copy = classification(doc(31, source("MEDIA_A", "Media A",
                SourceAuthority.ESTABLISHED_MEDIA), "AIA Vietnam launches digital customer experience",
                body, TODAY.minusDays(2)), Category.CUSTOMER_EXPERIENCE);
        Classification unrelated = classification(doc(32, source("OTHER", "Other",
                SourceAuthority.ESTABLISHED_MEDIA), "Sun Life Vietnam launches retirement product",
                repeated("Sun Life Vietnam launched a retirement insurance product for employers."),
                TODAY.minusDays(2)), Category.PRODUCT_LAUNCH);
        var audit = plan(List.of(lead, copy, unrelated), Set.of(30L),
                new ResearchCurationPlanner.Config(2, 1, 5, 5, 20, 365, "VN"),
                ResearchCurationPlanner.Mode.AUDIT);
        check(audit.selectedDocumentIds().containsAll(List.of(31L, 32L)),
                "audit samples both an unrepresented story and deferred republication");
    }

    private static void priorityTailMovesToSaturationAuditAfterCoverage() throws Exception {
        Classification represented = classification(doc(35, source("AUTHORITY", "Authority",
                SourceAuthority.REGULATOR), "Vietnam insurance brand confidence study",
                repeated("The regulator published a Vietnam insurance brand confidence study."),
                TODAY.minusDays(2)), Category.BRAND_REPUTATION);
        Classification background = classification(doc(36, source("UNKNOWN", "Unknown publisher",
                SourceAuthority.UNKNOWN), "Community sponsorship photo recap",
                repeated("A community sponsorship photo recap was published for local readers."),
                TODAY.minusDays(300)), Category.BRAND_REPUTATION);
        var config = new ResearchCurationPlanner.Config(2, 1, 5, 5, 20, 365, "VN");

        var main = plan(List.of(represented, background), Set.of(35L), config,
                ResearchCurationPlanner.Mode.MAIN);
        check(main.selected().isEmpty(),
                "below-PRIORITY tail must not force another automatic paid main batch after coverage");
        check(main.diagnostics().remainingClusters() == 1,
                "background cluster remains explicit rather than being deleted");

        var audit = plan(List.of(represented, background), Set.of(35L), config,
                ResearchCurationPlanner.Mode.AUDIT);
        check(audit.selectedDocumentIds().equals(List.of(36L)),
                "stratified saturation audit samples the unrepresented background tail");
    }

    private static void terminalAttemptDoesNotLoopAndRemainsVisible() throws Exception {
        Classification failed = classification(doc(40, source("FAILED", "Failed source",
                SourceAuthority.ESTABLISHED_MEDIA), "Unique market signal with rejected schema",
                repeated("A unique Vietnam insurance market signal remains under operator review."),
                TODAY.minusDays(2)), Category.MARKET_STRUCTURE);
        var plan = ResearchCurationPlanner.plan(List.of(failed), TODAY,
                new ResearchCurationPlanner.Config(2, 1, 2, 5, 20, 365, "VN"),
                Set.of(), Set.of(40L), ResearchCurationPlanner.Mode.MAIN);
        check(plan.selected().isEmpty(), "terminal content failure must not loop automatically");
        check(plan.diagnostics().remainingClusters() == 1,
                "failed cluster must not be misrepresented as covered");
        check(plan.diagnostics().exhaustedUnrepresentedClusters() == 1,
                "operator-review cluster must remain explicit in diagnostics");
    }

    private static ResearchCurationPlanner.Plan plan(List<Classification> values, Set<Long> represented,
                                                      ResearchCurationPlanner.Config config,
                                                      ResearchCurationPlanner.Mode mode) {
        return ResearchCurationPlanner.plan(values, TODAY, config, represented, mode);
    }

    private static Classification classification(RawDoc doc, Category category) {
        return new Classification(doc, Set.of(category), Classification.Status.CONFIRMED, "{}", "TEST");
    }

    private static Source source(String code, String name, SourceAuthority authority) {
        Source source = new Source(code, name, "https://" + code.toLowerCase() + ".example/news",
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

    private static String repeated(String sentence) {
        return (sentence + " ").repeat(35);
    }

    private static String sha(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
