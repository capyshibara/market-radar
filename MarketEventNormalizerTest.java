import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.MarketEvent;
import com.marketradar.domain.RawDoc;
import com.marketradar.domain.Source;
import com.marketradar.intelligence.MarketEventNormalizer;
import com.marketradar.intelligence.MarketEventTemporalRules;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Pure deterministic smoke test; no Spring context or database required.
 *
 * Run after compiling the application:
 *   javac -cp target/classes -d /tmp/market-event-test MarketEventNormalizerTest.java
 *   java -cp target/classes:/tmp/market-event-test MarketEventNormalizerTest
 */
public class MarketEventNormalizerTest {

    public static void main(String[] args) {
        MarketEventNormalizer normalizer = new MarketEventNormalizer();

        Source vn = new Source("AIA_VN", "AIA Vietnam", "https://aia.com.vn/news",
                "aia.com.vn", Source.SourceType.HTML, 2, "vi");
        RawDoc launchDoc = doc(vn, Instant.parse("2026-07-15T20:00:00Z"));
        EvidenceFact launch = new EvidenceFact("F-901", launchDoc,
                EvidenceFact.FactType.PRODUCT_LAUNCH, "AIA ra mắt Sống Khỏe.", "vi")
                .company("AIA").productName("Sống Khỏe")
                .eventDate(LocalDate.of(2026, 7, 15));

        MarketEvent launchEvent = normalizer.normalize(launch, "gpt-5-mini");
        check(launchEvent.getMarketScope() == MarketEvent.MarketScope.VIETNAM,
                "Vietnam scope");
        check("Vietnam".equals(launchEvent.getGeography()), "Vietnam geography");
        check(LocalDate.of(2026, 7, 16).equals(launchEvent.getPublishedDate()),
                "published date uses business timezone");
        check(LocalDate.of(2026, 7, 15).equals(launchEvent.getOccurredDate()),
                "launch date becomes occurred date");
        check(launchEvent.getEffectiveDate() == null, "launch has no effective date");
        check("F-901".equals(launchEvent.getEvidenceFactCode()), "evidence provenance");
        check("gpt-5-mini".equals(launchEvent.getModelVersion()), "model provenance");

        Source sg = new Source("MAS_SG", "MAS", "https://mas.gov.sg/news",
                "www.mas.gov.sg", Source.SourceType.HTML, 1, "en");
        RawDoc metricDoc = doc(sg, Instant.parse("2026-07-15T01:00:00Z"));
        EvidenceFact forecast = new EvidenceFact("F-902", metricDoc,
                EvidenceFact.FactType.METRIC, "The ratio will reach 20% in 2030.", "en")
                .eventDate(LocalDate.of(2030, 1, 1));

        MarketEvent forecastEvent = normalizer.normalize(forecast, "");
        check(forecastEvent.getMarketScope() == MarketEvent.MarketScope.REGIONAL,
                "regional scope");
        check("Singapore".equals(forecastEvent.getGeography()), "source-code geography");
        check(LocalDate.of(2030, 1, 1).equals(forecastEvent.getForecastHorizon()),
                "future metric becomes forecast horizon");
        check(forecastEvent.getOccurredDate() == null, "forecast is not occurrence");
        check("UNKNOWN_LEGACY".equals(forecastEvent.getModelVersion()),
                "missing model is explicit");

        EvidenceFact regulation = new EvidenceFact("F-903", metricDoc,
                EvidenceFact.FactType.REGULATION, "The rule takes effect in 2027.", "en")
                .eventDate(LocalDate.of(2027, 1, 1));
        MarketEvent regulationEvent = normalizer.normalize(regulation, "gpt-5-mini");
        check(LocalDate.of(2027, 1, 1).equals(regulationEvent.getEffectiveDate()),
                "future regulation date becomes effective date");
        check(regulationEvent.getOccurredDate() == null,
                "future regulation date is not occurrence");

        EvidenceFact expiredOffer = new EvidenceFact("F-904", launchDoc,
                EvidenceFact.FactType.FEE_CHANGE, "The offer ended on 2026-06-30.", "en")
                .occurredDate(LocalDate.of(2026, 5, 1))
                .effectiveDate(LocalDate.of(2026, 5, 1))
                .expiryDate(LocalDate.of(2026, 6, 30));
        MarketEvent expiredEvent = normalizer.normalize(expiredOffer, "gpt-5-mini");
        check(LocalDate.of(2026, 6, 30).equals(expiredEvent.getExpiryDate()),
                "explicit expiry date is normalized");
        check(MarketEventTemporalRules.status(expiredEvent, LocalDate.of(2026, 7, 16))
                        == MarketEventTemporalRules.Status.EXPIRED,
                "expired lifecycle is explicit");
        check(!MarketEventTemporalRules.futureActionEligible(
                        expiredEvent, LocalDate.of(2026, 7, 16)),
                "expired event cannot drive future-looking action");

        String firstKey = launchEvent.getEventKey();
        String secondKey = normalizer.normalize(launch, "another-model").getEventKey();
        check(firstKey.equals(secondKey), "event key is deterministic for fact + pipeline");

        System.out.println("MarketEventNormalizerTest: ALL PASS");
    }

    private static RawDoc doc(Source source, Instant publishedAt) {
        return new RawDoc(source, source.getFetchUrl(), "Title", publishedAt,
                Instant.parse("2026-07-16T02:00:00Z"), "a".repeat(64), "body",
                source.getLanguage(), RawDoc.ParseStatus.OK, null);
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError("Failed: " + name);
    }
}
