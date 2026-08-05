import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.GeographyScope;
import com.marketradar.domain.IntelligenceTopic;
import com.marketradar.domain.MarketEventCluster;
import com.marketradar.domain.RawDoc;
import com.marketradar.domain.Source;
import com.marketradar.domain.SourceAuthority;
import com.marketradar.intelligence.MarketEventClustering;
import com.marketradar.intelligence.MarketEventNormalizer;
import com.marketradar.intelligence.MarketEventIntelligenceView;
import com.marketradar.intelligence.EntityResolutionRules;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public class MarketEventClusteringTest {
    public static void main(String[] args) {
        var normalizer = new MarketEventNormalizer();
        var source1 = source("SOURCE_A");
        var source2 = source("SOURCE_B");
        var fact1 = fact("F-951", doc(source1), LocalDate.of(2026, 7, 1), "Shared Life", "Care Plus");
        var fact2 = fact("F-952", doc(source2), LocalDate.of(2026, 7, 2), "Shared Life", "Care Plus");

        var event1 = normalizer.normalize(fact1, "model-a");
        var event2 = normalizer.normalize(fact2, "model-b");
        var clusters = MarketEventClustering.cluster(List.of(event1, event2));
        check(clusters.size() == 1, "same resolved entity/product/month clusters conservatively");
        var cluster = clusters.get(0);
        check(cluster.independentSourceCount() == 2, "independent source provenance counted");
        check(cluster.provenanceState() == MarketEventCluster.ProvenanceState.INDEPENDENT_SOURCES,
                "corroboration state explicit");
        check(cluster.conflictState() == MarketEventCluster.ConflictState.DATE_CONFLICT,
                "different stated occurrence dates create explicit conflict");
        check(cluster.evidenceFactCodes().contains("F-951")
                        && cluster.evidenceFactCodes().contains("F-952"),
                "cluster retains evidence provenance");

        var persistedShape = new MarketEventCluster(cluster.clusterKey(), MarketEventClustering.VERSION);
        persistedShape.refresh(cluster.eventType(), cluster.company(), cluster.productName(),
                cluster.geography(), cluster.anchorDate(), cluster.factCount(), cluster.documentCount(),
                cluster.independentSourceCount(), cluster.provenanceState(), cluster.conflictState(),
                cluster.evidenceFactCodes(), cluster.sourceCodes());
        event1.assignCluster(persistedShape);
        var view = MarketEventIntelligenceView.from(event1, LocalDate.of(2026, 7, 16));
        check("SOURCE_A".equals(view.sourceCode()), "read model must expose exact cited source");
        check(view.sourceTier() == 2, "read model must expose source tier");
        check("market-event-v4-entity-time-taxonomy".equals(view.pipelineVersion()),
                "read model must expose normalizer pipeline version");
        check(view.sourceAuthority() == SourceAuthority.ESTABLISHED_MEDIA,
                "read model exposes authority independently from legacy tier");
        check("VN".equals(view.marketCode()), "read model exposes explicit market");
        check("model-a".equals(view.modelVersion()),
                "read model must expose extraction model version");
        check(view.independentSourceCount() == 2 && view.clusterDocumentCount() == 2,
                "downstream view exposes cluster provenance counts");
        check(view.conflictState() == MarketEventCluster.ConflictState.DATE_CONFLICT,
                "downstream view exposes conflict state");

        var anonymous1 = fact("F-953", doc(source1), LocalDate.of(2026, 7, 1), null, null);
        var anonymous2 = fact("F-954", doc(source2), LocalDate.of(2026, 7, 1), null, null);
        check(MarketEventClustering.cluster(List.of(normalizer.normalize(anonymous1, "m"),
                        normalizer.normalize(anonymous2, "m"))).size() == 2,
                "missing entity/product fails closed to separate clusters");

        var metric1 = metric("F-955", doc(source1), "GDP growth", "7.5%");
        var metric2 = metric("F-956", doc(source2), "GDP growth", "7.5%");
        check(MarketEventClustering.cluster(List.of(normalizer.normalize(metric1, "m"),
                        normalizer.normalize(metric2, "m"))).size() == 1,
                "same market/topic/named KPI can corroborate across documents");
        var differentMetric = metric("F-957", doc(source2), "Inflation", "3.2%");
        check(MarketEventClustering.cluster(List.of(normalizer.normalize(metric1, "m"),
                        normalizer.normalize(differentMetric, "m"))).size() == 2,
                "different KPI labels never merge merely because market and topic match");

        System.out.println("MarketEventClusteringTest: ALL PASS");
    }

    private static Source source(String code) {
        Source source = new Source(code, code, "https://example.com/" + code, "example.com",
                Source.SourceType.HTML, 2, "en");
        source.setIntelligenceMetadata(SourceAuthority.ESTABLISHED_MEDIA,
                GeographyScope.VIETNAM, "VN");
        return source;
    }

    private static RawDoc doc(Source source) {
        return new RawDoc(source, source.getFetchUrl(), "Launch", Instant.parse("2026-07-03T00:00:00Z"),
                Instant.parse("2026-07-03T01:00:00Z"), (source.getCode() + "x".repeat(64)).substring(0, 64),
                "full article", "en", RawDoc.ParseStatus.OK, null);
    }

    private static EvidenceFact fact(String code, RawDoc doc, LocalDate occurred,
                                     String company, String product) {
        return new EvidenceFact(code, doc, EvidenceFact.FactType.PRODUCT_LAUNCH,
                "Shared Life launched Care Plus.", "en")
                .company(company).productName(product).occurredDate(occurred).eventDate(occurred)
                .geography(GeographyScope.VIETNAM, "VN")
                .entityResolution(resolved(company));
    }

    private static EntityResolutionRules.Resolution resolved(String company) {
        if (company == null) {
            return new EntityResolutionRules.Resolution(EntityResolutionRules.Status.UNRESOLVED,
                    List.of(), List.of(), "No named entity");
        }
        var entity = new EntityResolutionRules.Entity("SHARED_LIFE_VN", company,
                EntityResolutionRules.EntityKind.LOCAL_OPERATING_COMPANY,
                "VN", null, "SHARED_LIFE", List.of(company), List.of());
        return new EntityResolutionRules.Resolution(EntityResolutionRules.Status.RESOLVED,
                List.of(entity), List.of(company), "Test entity resolved from exact span");
    }

    private static EvidenceFact metric(String code, RawDoc doc, String label, String value) {
        return new EvidenceFact(code, doc, EvidenceFact.FactType.METRIC,
                label + " was " + value + ".", "en")
                .kpiLabel(label).kpiValue(value)
                .intelligenceTopic(IntelligenceTopic.MACRO_ECONOMIC)
                .geography(GeographyScope.VIETNAM, "VN")
                .occurredDate(LocalDate.of(2026, 7, 1));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError("Failed: " + message);
    }
}
