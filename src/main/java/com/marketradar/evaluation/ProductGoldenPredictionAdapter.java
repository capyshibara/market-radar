package com.marketradar.evaluation;

import com.marketradar.intelligence.ProductEventTaxonomy;
import com.marketradar.intelligence.ProductMaterialityRules;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Adapts the intentionally small golden fixture to the current deterministic
 * Product taxonomy/materiality path. Missing article fields are never fabricated.
 */
public final class ProductGoldenPredictionAdapter {

    public static final String ADAPTER_VERSION = "product-golden-adapter-v1";
    private static final Set<String> PRODUCT_LABELS = Set.of(
            "PRODUCT_LAUNCH", "FEE_BENEFIT_COMMISSION_CHANGE", "PRODUCT_REGULATION");
    private static final List<String> UNAVAILABLE_ARTICLE_FIELDS = List.of(
            "classificationStatus", "rawText", "evidenceSpan", "summary", "company",
            "productName", "publishedDate", "eventDate", "parseStatus", "duplicate",
            "sourceTier");

    private ProductGoldenPredictionAdapter() {}

    public static Prediction predict(FixtureCase fixture) {
        Set<String> labels = fixture.observedLegacyLabels() == null ? Set.of()
                : fixture.observedLegacyLabels().stream()
                .filter(v -> v != null && !v.isBlank())
                .map(v -> v.strip().toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        String factType = legacyFactType(labels);
        ProductEventTaxonomy.EventType event = ProductEventTaxonomy.classify(
                fixture.title(), null, null, factType);

        // contentDepth tells us whether the corpus once had full text; the fixture
        // intentionally does not contain that text. Passing title as rawText/span
        // would falsely make an evidence-free case look publishable.
        ProductMaterialityRules.Score score = ProductMaterialityRules.score(
                new ProductMaterialityRules.Input(
                        factType, labels, null, fixture.title(), null, null, null,
                        null, null, null, null, fixture.snapshotDate(), false,
                        null, false, null));

        boolean pendingTitleOnlyProduct = "TITLE_ONLY".equalsIgnoreCase(fixture.contentDepth())
                && labels.stream().anyMatch(PRODUCT_LABELS::contains);
        boolean directlyRelevant = ProductEventTaxonomy.isProductOffer(event)
                || event == ProductEventTaxonomy.EventType.REGULATORY_CHANGE
                || event == ProductEventTaxonomy.EventType.CUSTOMER_NEED_SIGNAL;
        boolean concreteServiceInnovation = event == ProductEventTaxonomy.EventType.SERVICE_EXPERIENCE_CHANGE
                && containsAny(fixture.title(), "app feature", "customer journey", "self-service",
                "claims service", "tính năng ứng dụng", "hành trình khách hàng", "tự phục vụ");
        boolean conditionalOperational = concreteServiceInnovation
                || (event == ProductEventTaxonomy.EventType.DISTRIBUTION_CHANGE
                && score.decisionRelevance() >= 24 && !score.productKiqs().isEmpty());
        boolean relevant = pendingTitleOnlyProduct || directlyRelevant || conditionalOperational;

        // No fixture case contains the article body/evidence span required by the
        // current publication gate. Relevance can be assessed from its declared
        // fixture fields, but publication quality must remain fail-closed.
        String decision = relevant ? "NEEDS_EVIDENCE" : "EXCLUDE";
        String kiqs = score.productKiqs().stream().map(Enum::name).sorted()
                .collect(java.util.stream.Collectors.joining(","));
        String rulesVersion = ADAPTER_VERSION + "|" + ProductEventTaxonomy.VERSION
                + "|" + ProductMaterialityRules.RULES_VERSION;
        return new Prediction(fixture.caseId(), relevant, event.name(), decision,
                score.total(), false, false, rulesVersion,
                List.copyOf(UNAVAILABLE_ARTICLE_FIELDS), kiqs);
    }

    private static String legacyFactType(Set<String> labels) {
        if (labels.contains("SALES_DATA")) return "METRIC";
        if (labels.contains("PRODUCT_REGULATION")) return "REGULATION";
        if (labels.contains("FEE_BENEFIT_COMMISSION_CHANGE")) return "FEE_CHANGE";
        if (labels.contains("PRODUCT_LAUNCH")) return "PRODUCT_LAUNCH";
        return "EVENT";
    }

    private static boolean containsAny(String value, String... terms) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        for (String term : terms) if (normalized.contains(term)) return true;
        return false;
    }

    public record FixtureCase(String caseId, String title, String contentDepth,
                              Set<String> observedLegacyLabels, LocalDate snapshotDate) {}

    public record Prediction(String caseId, boolean departmentRelevant,
                             String primaryEventType, String qualityDecision,
                             int materialityScore, boolean publishEligible,
                             boolean evidenceEvaluable, String rulesVersion,
                             List<String> unavailableFields, String productKiqsCsv) {}
}
