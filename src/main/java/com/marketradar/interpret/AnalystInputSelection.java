package com.marketradar.interpret;

import com.marketradar.domain.Department;
import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.IntelligenceTopic;
import com.marketradar.domain.RawDoc;
import com.marketradar.domain.SourceAuthority;
import com.marketradar.intelligence.CurationPriorityRules;
import com.marketradar.report.ReportWindow;
import com.marketradar.review.EntityAttributionGuard;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic editorial budget for the paid Analyst stage.
 *
 * <p>The evidence store remains complete and auditable. This selector only decides
 * which current, entity-safe documents deserve a paid interpretation call now and
 * which bounded cross-document facts may enter the executive synthesis prompt.
 * Selection never upgrades source credibility or bypasses Gate L1/L2.</p>
 */
public final class AnalystInputSelection {

    /** Keep scheduled disclosures/events visible for the press-release calendar. */
    private static final int FUTURE_HORIZON_DAYS = 90;

    public enum MarketLane { VIETNAM, INTERNATIONAL, UNKNOWN }
    public enum AcquisitionLane { WHITELIST, DEEP_RESEARCH, MANUAL }

    public record Config(int maxDocuments, int maxFactsPerDocument,
                         int maxExecutiveFacts, int maxDocumentsPerSource,
                         int maxAgeDays, String targetMarketCode) {
        public Config {
            maxDocuments = positive(maxDocuments, "maxDocuments");
            maxFactsPerDocument = positive(maxFactsPerDocument, "maxFactsPerDocument");
            maxExecutiveFacts = positive(maxExecutiveFacts, "maxExecutiveFacts");
            maxDocumentsPerSource = positive(maxDocumentsPerSource, "maxDocumentsPerSource");
            maxAgeDays = positive(maxAgeDays, "maxAgeDays");
            targetMarketCode = value(targetMarketCode).isBlank() ? "VN" : value(targetMarketCode);
        }
    }

    public record Diagnostics(int corpusDocuments, int eligibleDocuments,
                              int representedDocuments, int selectedDocuments,
                              int deferredDocuments,
                              int excludedUndatedFacts, int excludedStaleFacts,
                              int quarantinedEntityFacts,
                              Map<MarketLane, Integer> selectedByMarket,
                              Map<AcquisitionLane, Integer> selectedByAcquisition) {
        public Diagnostics {
            selectedByMarket = Map.copyOf(selectedByMarket);
            selectedByAcquisition = Map.copyOf(selectedByAcquisition);
        }

        public String summary() {
            return "Analyst curation: selected " + selectedDocuments + "/" + eligibleDocuments
                    + " eligible document(s) for this batch; " + representedDocuments
                    + " already have a current interpretation and " + deferredDocuments
                    + " remain for later batches; market=" + selectedByMarket
                    + "; acquisition=" + selectedByAcquisition
                    + "; held facts: entity=" + quarantinedEntityFacts
                    + ", undated=" + excludedUndatedFacts + ", stale=" + excludedStaleFacts + ".";
        }
    }

    public record Selection(Map<RawDoc, List<EvidenceFact>> eligibleByDocument,
                            Map<RawDoc, List<EvidenceFact>> selectedByDocument,
                            List<EvidenceFact> executiveFacts,
                            Diagnostics diagnostics) {
        public Selection {
            Map<RawDoc, List<EvidenceFact>> eligibleCopy = new LinkedHashMap<>();
            eligibleByDocument.forEach((doc, facts) ->
                    eligibleCopy.put(doc, List.copyOf(facts)));
            eligibleByDocument = java.util.Collections.unmodifiableMap(eligibleCopy);
            Map<RawDoc, List<EvidenceFact>> copy = new LinkedHashMap<>();
            selectedByDocument.forEach((doc, facts) -> copy.put(doc, List.copyOf(facts)));
            selectedByDocument = java.util.Collections.unmodifiableMap(copy);
            executiveFacts = List.copyOf(executiveFacts);
        }
    }

    private record ScoredFact(EvidenceFact fact, int score) {}
    private record Candidate(RawDoc doc, List<ScoredFact> facts, int score,
                             MarketLane marketLane, AcquisitionLane acquisitionLane,
                             Set<IntelligenceTopic> topics, String sourceCode) {}

    private static final List<IntelligenceTopic> TOPIC_SEED_ORDER = List.of(
            IntelligenceTopic.REGULATION_POLICY,
            IntelligenceTopic.FINANCIAL_PERFORMANCE,
            IntelligenceTopic.MARKET_SHARE,
            IntelligenceTopic.MARKET_STRUCTURE,
            IntelligenceTopic.CORPORATE_ACTION,
            IntelligenceTopic.TECHNOLOGY_AI,
            IntelligenceTopic.CUSTOMER_ENGAGEMENT,
            IntelligenceTopic.PRODUCT_OFFER,
            IntelligenceTopic.DISTRIBUTION,
            IntelligenceTopic.MACRO_ECONOMIC,
            IntelligenceTopic.PEOPLE_TALENT,
            IntelligenceTopic.BRAND_REPUTATION,
            IntelligenceTopic.OTHER);

    private AnalystInputSelection() {}

    public static Selection select(List<EvidenceFact> input, LocalDate today, Config config) {
        return select(input, today, config, Set.of());
    }

    /**
     * Selects the next paid Analyst batch. representedDocumentIds are excluded
     * from this action, but remain in eligibleByDocument for checkpoint/report
     * coverage. Therefore maxDocuments is a batch size, never a quality cutoff.
     */
    public static Selection select(List<EvidenceFact> input, LocalDate today, Config config,
                                   Set<Long> representedDocumentIds) {
        Objects.requireNonNull(today, "today is required");
        Objects.requireNonNull(config, "config is required");
        Set<Long> representedIds = representedDocumentIds == null
                ? Set.of() : Set.copyOf(representedDocumentIds);
        List<EvidenceFact> facts = input == null ? List.of() : input.stream()
                .filter(Objects::nonNull).toList();

        Set<RawDoc> corpusDocs = new LinkedHashSet<>();
        Map<RawDoc, List<ScoredFact>> eligibleByDoc = new LinkedHashMap<>();
        int undated = 0, stale = 0, entityRisk = 0;
        for (EvidenceFact fact : facts) {
            RawDoc doc = fact.getRawDoc();
            if (doc == null) continue;
            corpusDocs.add(doc);
            if (!eligibleDocument(doc)) continue;
            LocalDate date = ReportWindow.factDisplayDate(fact);
            if (date == null) { undated++; continue; }
            long ageDays = ChronoUnit.DAYS.between(date, today);
            if (ageDays < -FUTURE_HORIZON_DAYS || ageDays > config.maxAgeDays()) {
                stale++;
                continue;
            }
            if (!EntityAttributionGuard.isFactAttributionSafe(fact)) { entityRisk++; continue; }
            int score = factScore(fact, Math.max(0, ageDays), config.targetMarketCode());
            eligibleByDoc.computeIfAbsent(doc, ignored -> new ArrayList<>())
                    .add(new ScoredFact(fact, score));
        }

        List<Candidate> candidates = eligibleByDoc.entrySet().stream()
                .map(entry -> candidate(entry.getKey(), entry.getValue(), config.maxFactsPerDocument()))
                .sorted(candidateOrder())
                .toList();
        List<Candidate> batchCandidates = candidates.stream()
                .filter(candidate -> candidate.doc().getId() == null
                        || !representedIds.contains(candidate.doc().getId()))
                .toList();

        LinkedHashSet<Candidate> selected = new LinkedHashSet<>();
        Map<String, Integer> perSource = new LinkedHashMap<>();

        // First preserve topical breadth, then all three acquisition paths and both
        // domestic/international context. The final fill returns to pure priority.
        int perTopic = Math.max(1, Math.min(3, config.maxDocuments() / 40));
        for (IntelligenceTopic topic : TOPIC_SEED_ORDER) {
            addUntil(selected, perSource, batchCandidates, config, perTopic,
                    candidate -> candidate.topics().contains(topic));
        }
        int acquisitionFloor = Math.max(1, Math.min(12, config.maxDocuments() / 10));
        for (AcquisitionLane lane : AcquisitionLane.values()) {
            addUntil(selected, perSource, batchCandidates, config, acquisitionFloor,
                    candidate -> candidate.acquisitionLane() == lane);
        }
        addUntil(selected, perSource, batchCandidates, config,
                Math.max(1, config.maxDocuments() / 3),
                candidate -> candidate.marketLane() == MarketLane.VIETNAM);
        addUntil(selected, perSource, batchCandidates, config,
                Math.max(1, config.maxDocuments() / 5),
                candidate -> candidate.marketLane() == MarketLane.INTERNATIONAL);
        addUntil(selected, perSource, batchCandidates, config, config.maxDocuments(), candidate -> true);

        // If strict source diversity prevents filling the budget, relax only that
        // presentation constraint. Evidence gates and entity safety remain unchanged.
        if (selected.size() < Math.min(config.maxDocuments(), batchCandidates.size())) {
            for (Candidate candidate : batchCandidates) {
                if (selected.size() >= config.maxDocuments()) break;
                selected.add(candidate);
            }
        }

        Map<RawDoc, List<EvidenceFact>> byDoc = new LinkedHashMap<>();
        for (Candidate candidate : selected) {
            byDoc.put(candidate.doc(), candidate.facts().stream().map(ScoredFact::fact).toList());
        }
        Map<RawDoc, List<EvidenceFact>> allEligibleByDoc = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            allEligibleByDoc.put(candidate.doc(), candidate.facts().stream()
                    .map(ScoredFact::fact).toList());
        }
        List<EvidenceFact> executiveFacts = executiveFacts(
                new LinkedHashSet<>(candidates), config.maxExecutiveFacts());

        Map<MarketLane, Integer> byMarket = new EnumMap<>(MarketLane.class);
        Map<AcquisitionLane, Integer> byAcquisition = new EnumMap<>(AcquisitionLane.class);
        for (MarketLane lane : MarketLane.values()) byMarket.put(lane, 0);
        for (AcquisitionLane lane : AcquisitionLane.values()) byAcquisition.put(lane, 0);
        for (Candidate candidate : selected) {
            byMarket.merge(candidate.marketLane(), 1, Integer::sum);
            byAcquisition.merge(candidate.acquisitionLane(), 1, Integer::sum);
        }
        int represented = (int) candidates.stream().map(Candidate::doc).map(RawDoc::getId)
                .filter(Objects::nonNull).filter(representedIds::contains).count();
        Diagnostics diagnostics = new Diagnostics(corpusDocs.size(), candidates.size(), represented,
                selected.size(), Math.max(0, candidates.size() - represented - selected.size()),
                undated, stale, entityRisk,
                byMarket, byAcquisition);
        return new Selection(allEligibleByDoc, byDoc, executiveFacts, diagnostics);
    }

    private static Candidate candidate(RawDoc doc, List<ScoredFact> input, int maxFacts) {
        List<ScoredFact> ranked = input.stream()
                .sorted(Comparator.comparingInt(ScoredFact::score).reversed()
                        .thenComparing(sf -> value(sf.fact().getFactCode())))
                .limit(maxFacts).toList();
        Set<IntelligenceTopic> topics = new LinkedHashSet<>();
        for (ScoredFact fact : ranked) {
            topics.add(fact.fact().getIntelligenceTopic() == null
                    ? IntelligenceTopic.OTHER : fact.fact().getIntelligenceTopic());
        }
        int score = ranked.stream().mapToInt(ScoredFact::score).max().orElse(0);
        return new Candidate(doc, ranked, score, marketLane(ranked), acquisitionLane(doc),
                Set.copyOf(topics), sourceCode(doc));
    }

    private static int factScore(EvidenceFact fact, long ageDays, String targetMarket) {
        SourceAuthority authority = fact.getSourceAuthority();
        if (authority == null && fact.getRawDoc() != null && fact.getRawDoc().getSource() != null) {
            authority = fact.getRawDoc().getSource().getAuthority();
        }
        IntelligenceTopic topic = fact.getIntelligenceTopic() == null
                ? IntelligenceTopic.OTHER : fact.getIntelligenceTopic();
        String market = value(fact.getMarketCode());
        if (market.isBlank() && fact.getRawDoc() != null && fact.getRawDoc().getSource() != null) {
            market = value(fact.getRawDoc().getSource().getDefaultMarketCode());
        }
        CurationPriorityRules.Score score = CurationPriorityRules.score(new CurationPriorityRules.Input(
                Department.STRATEGY, Set.of(topic), targetMarket,
                market.isBlank() ? Set.of() : Set.of(market),
                authority == null ? SourceAuthority.UNKNOWN.credibilityScore() : authority.credibilityScore(),
                1, ageDays, fact.isHighlight(), true));
        return score.total();
    }

    private static Comparator<Candidate> candidateOrder() {
        return Comparator.comparingInt(Candidate::score).reversed()
                .thenComparing(candidate -> candidate.marketLane() == MarketLane.VIETNAM ? 0 : 1)
                .thenComparing(Candidate::sourceCode)
                .thenComparing(candidate -> docKey(candidate.doc()));
    }

    private static void addUntil(Set<Candidate> selected, Map<String, Integer> perSource,
                                 List<Candidate> candidates, Config config, int targetMatches,
                                 java.util.function.Predicate<Candidate> predicate) {
        int matches = (int) selected.stream().filter(predicate).count();
        if (matches >= targetMatches || selected.size() >= config.maxDocuments()) return;
        for (Candidate candidate : candidates) {
            if (selected.size() >= config.maxDocuments() || matches >= targetMatches) break;
            if (selected.contains(candidate) || !predicate.test(candidate)) continue;
            int sourceCount = perSource.getOrDefault(candidate.sourceCode(), 0);
            if (sourceCount >= config.maxDocumentsPerSource()) continue;
            selected.add(candidate);
            perSource.put(candidate.sourceCode(), sourceCount + 1);
            matches++;
        }
    }

    private static List<EvidenceFact> executiveFacts(Set<Candidate> selected, int maxFacts) {
        List<Candidate> candidates = new ArrayList<>(selected);
        List<EvidenceFact> out = new ArrayList<>();
        int round = 0;
        boolean added;
        do {
            added = false;
            for (Candidate candidate : candidates) {
                if (out.size() >= maxFacts) return List.copyOf(out);
                if (round < candidate.facts().size()) {
                    out.add(candidate.facts().get(round).fact());
                    added = true;
                }
            }
            round++;
        } while (added);
        return List.copyOf(out);
    }

    private static MarketLane marketLane(List<ScoredFact> facts) {
        boolean unknown = true;
        for (ScoredFact scored : facts) {
            EvidenceFact fact = scored.fact();
            String market = value(fact.getMarketCode());
            if (market.isBlank() && fact.getRawDoc() != null && fact.getRawDoc().getSource() != null) {
                market = value(fact.getRawDoc().getSource().getDefaultMarketCode());
            }
            if (!market.isBlank()) unknown = false;
            if ("VN".equals(market)) return MarketLane.VIETNAM;
        }
        return unknown ? MarketLane.UNKNOWN : MarketLane.INTERNATIONAL;
    }

    private static AcquisitionLane acquisitionLane(RawDoc doc) {
        return switch (doc.getIntakeMethod()) {
            case MANUAL_TEXT, FILE_UPLOAD -> AcquisitionLane.MANUAL;
            case OPEN_SEARCH, BROWSER_RENDER -> AcquisitionLane.DEEP_RESEARCH;
            case CRAWLED -> AcquisitionLane.WHITELIST;
        };
    }

    private static boolean eligibleDocument(RawDoc doc) {
        return !doc.isSampleData() && doc.getDuplicateOfId() == null
                && doc.getSource() != null && doc.getSource().getUsePolicy().allowsAnalysis();
    }

    private static String sourceCode(RawDoc doc) {
        if (doc.getSource() == null) return "UNKNOWN";
        return value(doc.getSource().getCode()).isBlank() ? "UNKNOWN" : value(doc.getSource().getCode());
    }

    private static String docKey(RawDoc doc) {
        if (doc.getId() != null) return String.format(Locale.ROOT, "%020d", doc.getId());
        return value(doc.getUrl()) + "|" + value(doc.getTitle());
    }

    private static int positive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static String value(String value) {
        return value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
    }
}
