package com.marketradar.product;

import com.marketradar.quality.ProductPublicationQualityGate;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Maps structured Product clusters into the shared last-mile publication gate. */
public final class ProductPublicationGateAdapter {

    public static final String VERSION = "product-publication-adapter-v2-watch-brief";
    private static final Pattern HORIZON = Pattern.compile("(?i)within\\s+(30|45|60|90)\\s+days");

    private ProductPublicationGateAdapter() {}

    public static String qualitySignature(String groundingVerifierSignature) {
        try {
            String value = VERSION + "\n" + groundingVerifierSignature;
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public record Input(ProductBriefSynthesisRules.Draft draft,
                        ProductInsightWriter.WrittenInsight written,
                        Set<String> resolvedEvidenceIds) {}

    public record Evaluation(ProductPublicationQualityGate.MagazineResult magazine,
                             List<ProductPublicationQualityGate.InsightResult> insightResults) {}

    public static Evaluation evaluateMagazine(List<Input> inputs, LocalDate windowStart,
                                              LocalDate windowEnd, LocalDate asOf) {
        List<ProductPublicationQualityGate.InsightCandidate> candidates = new ArrayList<>();
        int rank = 1;
        for (Input input : inputs) {
            String insightId = "PRODUCT-CANDIDATE-" + rank++;
            Set<String> cited = new LinkedHashSet<>(input.written().citedFactCodes());
            List<ProductBriefSynthesisRules.Signal> signals = input.draft().signals().stream()
                    .filter(s -> cited.contains(s.factCode())).toList();
            Set<String> eventTypes = signals.stream().map(ProductBriefSynthesisRules.Signal::eventType)
                    .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
            String selectedType = eventTypes.stream().findFirst().orElse("");
            // The shared gate currently accepts one event type. Probe every cited type
            // and deliberately select a rejecting type if any would contaminate the theme.
            for (String type : eventTypes) {
                var probe = ProductPublicationQualityGate.evaluate(candidate(insightId, input,
                        signals, type, windowStart, windowEnd, asOf));
                if (probe.failureCodes().contains(
                        ProductPublicationQualityGate.FailureCode.CHAPTER_CATEGORY_CONTAMINATION)) {
                    selectedType = type;
                    break;
                }
            }
            candidates.add(candidate(insightId, input, signals, selectedType,
                    windowStart, windowEnd, asOf));
        }
        ProductPublicationQualityGate.MagazineResult magazine =
                ProductPublicationQualityGate.evaluateMagazine(candidates);
        return new Evaluation(magazine, magazine.insightResults());
    }

    private static ProductPublicationQualityGate.InsightCandidate candidate(
            String insightId, Input input, List<ProductBriefSynthesisRules.Signal> signals,
            String eventType, LocalDate windowStart, LocalDate windowEnd, LocalDate asOf) {
        Set<String> cited = new LinkedHashSet<>(input.written().citedFactCodes());
        Set<String> companies = signals.stream().map(ProductBriefSynthesisRules.Signal::company)
                .filter(ProductPublicationGateAdapter::present)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> events = signals.stream().map(ProductBriefSynthesisRules.Signal::clusterKey)
                .filter(ProductPublicationGateAdapter::present)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> sources = signals.stream().map(ProductBriefSynthesisRules.Signal::sourceCode)
                .filter(ProductPublicationGateAdapter::present)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<LocalDate> published = signals.stream().map(ProductBriefSynthesisRules.Signal::publishedDate)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> models = signals.stream().map(ProductBriefSynthesisRules.Signal::modelVersion)
                .filter(ProductPublicationGateAdapter::present)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> pipelines = signals.stream().map(ProductBriefSynthesisRules.Signal::pipelineVersion)
                .filter(ProductPublicationGateAdapter::present)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        long clusterCount = events.size();
        boolean trend = clusterCount >= 2 && sources.size() >= 2;
        LocalDate effective = signals.stream()
                .filter(s -> "UPCOMING".equals(s.temporalStatus()))
                .map(ProductBriefSynthesisRules.Signal::effectiveDate).filter(Objects::nonNull)
                .min(LocalDate::compareTo).orElse(null);
        int horizon = horizon(input.written().nowWhatEn());
        return new ProductPublicationQualityGate.InsightCandidate(
                insightId, "PRODUCT", "INTERNAL_PRODUCT", "Product", companies,
                ProductKiqContract.split(input.draft().kiqCode()), input.draft().theme().name(),
                eventType, cited, input.resolvedEvidenceIds(), events, sources,
                input.written().patternEn(), input.written().nowWhatEn(), trend, true,
                asOf.plusDays(horizon), effective, asOf, windowStart, windowEnd,
                published, models, pipelines);
    }

    private static int horizon(String nowWhatEn) {
        var matcher = HORIZON.matcher(nowWhatEn == null ? "" : nowWhatEn);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }
}
