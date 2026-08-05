package com.marketradar.extract;

import com.marketradar.domain.Category;
import com.marketradar.domain.Classification;
import com.marketradar.domain.GeographyScope;
import com.marketradar.domain.IntelligenceTopic;
import com.marketradar.domain.RawDoc;
import com.marketradar.domain.SourceAuthority;
import com.marketradar.intelligence.CurationPriorityRules;
import com.marketradar.intelligence.EntityResolutionRules;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Model-free, event-first admission plan for the paid Researcher.
 *
 * <p>The previous selector ranked documents and cut the list at an arbitrary
 * maximum. That protected spend but could not establish that the tail contained
 * less information. This planner instead builds conservative story clusters over
 * every eligible document, then pays for a small batch of cluster representatives.
 * A second independent publisher is retained when available; additional members
 * stay in a visible audit pool. Ambiguous similarity fails open as separate
 * clusters, so cost saving never takes precedence over recall.</p>
 */
public final class ResearchCurationPlanner {

    public static final String VERSION = "research-curation-v1-event-first";
    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int FUTURE_HORIZON_DAYS = 90;
    private static final int CONTENT_PREFIX_CHARS = 8_000;
    private static final int MAX_SHINGLES = 700;

    public enum Mode { MAIN, AUDIT }
    public enum MarketLane { VIETNAM, INTERNATIONAL, UNKNOWN }
    public enum AcquisitionLane { WHITELIST, DEEP_RESEARCH, MANUAL }

    public record Config(int batchClusters, int representativesPerCluster,
                         int auditDocuments, int maxDocumentsPerSourcePerBatch,
                         int hardDocumentCeiling, int maxAgeDays,
                         String targetMarketCode) {
        public Config {
            batchClusters = positive(batchClusters, "batchClusters");
            representativesPerCluster = positive(representativesPerCluster,
                    "representativesPerCluster");
            auditDocuments = positive(auditDocuments, "auditDocuments");
            maxDocumentsPerSourcePerBatch = positive(maxDocumentsPerSourcePerBatch,
                    "maxDocumentsPerSourcePerBatch");
            hardDocumentCeiling = positive(hardDocumentCeiling, "hardDocumentCeiling");
            maxAgeDays = positive(maxAgeDays, "maxAgeDays");
            targetMarketCode = value(targetMarketCode).isBlank()
                    ? "VN" : value(targetMarketCode).toUpperCase(Locale.ROOT);
        }
    }

    public record Candidate(Classification classification, int priorityScore,
                            MarketLane marketLane, AcquisitionLane acquisitionLane,
                            SourceAuthority authority, Set<IntelligenceTopic> topics,
                            String entityKey, EntityResolutionRules.Status entityStatus,
                            Set<String> titleTokens, Set<Integer> contentShingles) {
        public Candidate {
            topics = Set.copyOf(topics);
            titleTokens = Set.copyOf(titleTokens);
            contentShingles = Set.copyOf(contentShingles);
        }
        public RawDoc document() { return classification.getRawDoc(); }
        public String sourceCode() { return ResearchCurationPlanner.sourceCode(document()); }
    }

    public record StoryCluster(String clusterKey, List<Candidate> members,
                               int priorityScore, Set<IntelligenceTopic> topics,
                               Set<AcquisitionLane> acquisitionLanes,
                               Set<String> entityKeys, boolean primaryEvidence,
                               boolean represented) {
        public StoryCluster {
            members = List.copyOf(members);
            topics = Set.copyOf(topics);
            acquisitionLanes = Set.copyOf(acquisitionLanes);
            entityKeys = Set.copyOf(entityKeys);
        }
        public Candidate lead() { return members.get(0); }
    }

    public record Coverage(Set<IntelligenceTopic> availableTopics,
                           Set<IntelligenceTopic> representedTopics,
                           Set<AcquisitionLane> availableAcquisition,
                           Set<AcquisitionLane> representedAcquisition,
                           Set<String> availableEntities,
                           Set<String> representedEntities,
                           boolean primaryAvailable, boolean primaryRepresented,
                           boolean vietnamAvailable, boolean vietnamRepresented) {
        public Coverage {
            availableTopics = Set.copyOf(availableTopics);
            representedTopics = Set.copyOf(representedTopics);
            availableAcquisition = Set.copyOf(availableAcquisition);
            representedAcquisition = Set.copyOf(representedAcquisition);
            availableEntities = Set.copyOf(availableEntities);
            representedEntities = Set.copyOf(representedEntities);
        }
        public Set<IntelligenceTopic> missingTopics() {
            return difference(availableTopics, representedTopics);
        }
        public Set<AcquisitionLane> missingAcquisition() {
            return difference(availableAcquisition, representedAcquisition);
        }
        public Set<String> missingEntities() {
            return difference(availableEntities, representedEntities);
        }
        public boolean complete() {
            return missingTopics().isEmpty() && missingAcquisition().isEmpty()
                    && missingEntities().isEmpty()
                    && (!primaryAvailable || primaryRepresented)
                    && (!vietnamAvailable || vietnamRepresented);
        }
    }

    public record Diagnostics(int confirmedDocuments, int eligibleDocuments,
                              int candidateClusters, int representedClusters,
                              int remainingClusters, int exhaustedUnrepresentedClusters,
                              int redundantDocuments, int auditPoolDocuments,
                              int deferredDocuments, int excludedInvalidContent,
                              int excludedUndated, int excludedOutsideWindow,
                              int selectedClusters, int selectedDocuments,
                              Map<MarketLane, Integer> selectedByMarket,
                              Map<AcquisitionLane, Integer> selectedByAcquisition,
                              Map<SourceAuthority, Integer> selectedByAuthority) {
        public Diagnostics {
            selectedByMarket = Map.copyOf(selectedByMarket);
            selectedByAcquisition = Map.copyOf(selectedByAcquisition);
            selectedByAuthority = Map.copyOf(selectedByAuthority);
        }
    }

    public record Plan(Mode mode, String candidateSnapshot,
                       List<Candidate> selected, List<StoryCluster> selectedClusters,
                       List<StoryCluster> allClusters, Coverage coverage,
                       Diagnostics diagnostics) {
        public Plan {
            selected = List.copyOf(selected);
            selectedClusters = List.copyOf(selectedClusters);
            allClusters = List.copyOf(allClusters);
        }
        public List<Classification> classifications() {
            return selected.stream().map(Candidate::classification).toList();
        }
        public List<Long> selectedDocumentIds() {
            return selected.stream().map(Candidate::document).map(RawDoc::getId)
                    .filter(Objects::nonNull).toList();
        }
        public String summary() {
            String action = mode == Mode.MAIN ? "next main batch" : "deferred audit sample";
            return "Researcher event-first plan (" + VERSION + "): " + action + " selects "
                    + diagnostics.selectedDocuments() + " document(s) from "
                    + diagnostics.selectedClusters() + " story cluster(s); corpus has "
                    + diagnostics.candidateClusters() + " eligible cluster(s), "
                    + diagnostics.representedClusters() + " represented and "
                    + diagnostics.remainingClusters() + " still unrepresented. "
                    + diagnostics.auditPoolDocuments() + " additional article(s) remain in the "
                    + "deferred-audit pool; none is declared low-value merely because it was deferred.";
        }
    }

    private static final List<IntelligenceTopic> TOPIC_SEED_ORDER = List.of(
            IntelligenceTopic.REGULATION_POLICY,
            IntelligenceTopic.FINANCIAL_PERFORMANCE,
            IntelligenceTopic.MARKET_SHARE,
            IntelligenceTopic.MARKET_STRUCTURE,
            IntelligenceTopic.CORPORATE_ACTION,
            IntelligenceTopic.PRODUCT_OFFER,
            IntelligenceTopic.DISTRIBUTION,
            IntelligenceTopic.TECHNOLOGY_AI,
            IntelligenceTopic.CUSTOMER_ENGAGEMENT,
            IntelligenceTopic.MACRO_ECONOMIC,
            IntelligenceTopic.PEOPLE_TALENT,
            IntelligenceTopic.BRAND_REPUTATION,
            IntelligenceTopic.OTHER);

    private static final Set<String> TITLE_STOPWORDS = Set.of(
            "the", "a", "an", "and", "or", "of", "to", "for", "in", "on", "at", "with",
            "from", "by", "is", "are", "new", "latest", "news", "update", "report",
            "và", "hoặc", "của", "cho", "trong", "tại", "với", "từ", "bởi", "là", "về",
            "mới", "tin", "thông", "báo", "cập", "nhật", "cáo");

    private ResearchCurationPlanner() {}

    public static Plan plan(List<Classification> input, LocalDate today, Config config,
                            Set<Long> representedDocumentIds, Mode mode) {
        return plan(input, today, config, representedDocumentIds,
                representedDocumentIds, mode);
    }

    /**
     * Builds a plan while distinguishing successful representation from terminal
     * attempts. EMPTY_RESULT and SCHEMA_REJECTED documents are not silently retried
     * forever; their cluster remains visibly unrepresented for operator review.
     * Transient LLM/provider failures must not be included in attemptedDocumentIds.
     */
    public static Plan plan(List<Classification> input, LocalDate today, Config config,
                            Set<Long> representedDocumentIds,
                            Set<Long> attemptedDocumentIds, Mode mode) {
        Objects.requireNonNull(today, "today is required");
        Objects.requireNonNull(config, "config is required");
        Set<Long> representedIds = representedDocumentIds == null
                ? Set.of() : Set.copyOf(representedDocumentIds);
        Set<Long> attemptedIds = attemptedDocumentIds == null
                ? representedIds : Set.copyOf(attemptedDocumentIds);
        Mode actualMode = mode == null ? Mode.MAIN : mode;
        List<Classification> confirmed = input == null ? List.of() : input.stream()
                .filter(Objects::nonNull)
                .filter(c -> c.getStatus() == Classification.Status.CONFIRMED)
                .toList();

        int invalid = 0, undated = 0, outsideWindow = 0;
        List<Candidate> candidates = new ArrayList<>();
        for (Classification classification : confirmed) {
            RawDoc doc = classification.getRawDoc();
            if (!eligibleContent(doc)) { invalid++; continue; }
            if (doc.getPublishedAt() == null) { undated++; continue; }
            LocalDate published = doc.getPublishedAt().atZone(REPORT_ZONE).toLocalDate();
            long ageDays = ChronoUnit.DAYS.between(published, today);
            if (ageDays < -FUTURE_HORIZON_DAYS || ageDays > config.maxAgeDays()) {
                outsideWindow++;
                continue;
            }
            candidates.add(candidate(classification, Math.max(0, ageDays),
                    config.targetMarketCode()));
        }
        candidates.sort(candidateOrder());
        String snapshot = candidateSnapshot(candidates);
        List<StoryCluster> clusters = cluster(candidates, representedIds);
        Coverage coverage = coverage(clusters);

        BatchSelection batch = actualMode == Mode.MAIN
                ? selectMain(clusters, coverage, config, representedIds, attemptedIds)
                : selectAudit(clusters, config, attemptedIds, snapshot);

        Map<MarketLane, Integer> byMarket = zeroed(MarketLane.class);
        Map<AcquisitionLane, Integer> byAcquisition = zeroed(AcquisitionLane.class);
        Map<SourceAuthority, Integer> byAuthority = zeroed(SourceAuthority.class);
        for (Candidate candidate : batch.documents()) {
            byMarket.merge(candidate.marketLane(), 1, Integer::sum);
            byAcquisition.merge(candidate.acquisitionLane(), 1, Integer::sum);
            byAuthority.merge(candidate.authority(), 1, Integer::sum);
        }
        int representedClusters = (int) clusters.stream().filter(StoryCluster::represented).count();
        int exhaustedUnrepresentedClusters = (int) clusters.stream()
                .filter(cluster -> !cluster.represented())
                .filter(cluster -> cluster.members().stream().allMatch(candidate ->
                        candidate.document().getId() != null
                                && attemptedIds.contains(candidate.document().getId())))
                .count();
        int redundant = clusters.stream().mapToInt(cluster -> Math.max(0, cluster.members().size() - 1)).sum();
        int auditPool = (int) clusters.stream().filter(StoryCluster::represented)
                .flatMap(cluster -> cluster.members().stream())
                .filter(candidate -> candidate.document().getId() == null
                        || !attemptedIds.contains(candidate.document().getId()))
                .count();
        int deferred = (int) candidates.stream()
                .filter(candidate -> !attemptedIds.contains(candidate.document().getId()))
                .filter(candidate -> !batch.documents().contains(candidate)).count();
        Diagnostics diagnostics = new Diagnostics(confirmed.size(), candidates.size(), clusters.size(),
                representedClusters, clusters.size() - representedClusters,
                exhaustedUnrepresentedClusters, redundant, auditPool, deferred,
                invalid, undated, outsideWindow, batch.clusters().size(), batch.documents().size(),
                byMarket, byAcquisition, byAuthority);
        return new Plan(actualMode, snapshot, batch.documents(), batch.clusters(), clusters,
                coverage, diagnostics);
    }

    private static List<StoryCluster> cluster(List<Candidate> candidates, Set<Long> representedIds) {
        // Complete-link grouping: a new member must match every existing member.
        // This deliberately avoids transitive A~B~C chains where A and C describe
        // different stories and would otherwise be merged by union-find.
        List<List<Candidate>> groups = new ArrayList<>();
        for (Candidate candidate : candidates) {
            List<Candidate> match = groups.stream()
                    .filter(group -> group.stream().allMatch(member -> sameStory(member, candidate)))
                    .findFirst().orElse(null);
            if (match == null) {
                match = new ArrayList<>();
                groups.add(match);
            }
            match.add(candidate);
        }
        List<StoryCluster> out = new ArrayList<>();
        for (List<Candidate> members : groups) {
            members.sort(candidateOrder());
            Set<IntelligenceTopic> topics = members.stream().flatMap(c -> c.topics().stream())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Set<AcquisitionLane> acquisition = members.stream().map(Candidate::acquisitionLane)
                    .collect(Collectors.toCollection(() -> java.util.EnumSet.noneOf(AcquisitionLane.class)));
            Set<String> entities = members.stream().map(Candidate::entityKey)
                    .filter(value -> value != null && !value.isBlank())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            boolean represented = members.stream().map(Candidate::document).map(RawDoc::getId)
                    .anyMatch(id -> id != null && representedIds.contains(id));
            boolean primary = members.stream().anyMatch(c -> c.authority().isPrimaryEvidence());
            String memberIdentity = members.stream().map(Candidate::document)
                    .map(doc -> doc.getContentHash() + "|" + Objects.toString(doc.getId(), doc.getUrl()))
                    .sorted().collect(Collectors.joining("\n"));
            out.add(new StoryCluster("RC:" + sha256(memberIdentity).substring(0, 20), members,
                    members.get(0).priorityScore(), topics, acquisition, entities, primary, represented));
        }
        out.sort(clusterOrder());
        return List.copyOf(out);
    }

    /**
     * Merge only high-confidence republications. If legal entity, date, market or
     * topic is incompatible, the pair remains separate even when wording is similar.
     */
    static boolean sameStory(Candidate left, Candidate right) {
        LocalDate leftDate = publicationDate(left.document());
        LocalDate rightDate = publicationDate(right.document());
        if (leftDate == null || rightDate == null
                || Math.abs(ChronoUnit.DAYS.between(leftDate, rightDate)) > 14) return false;
        if (disjoint(left.topics(), right.topics())) return false;
        if (!marketCompatible(left.marketLane(), right.marketLane())) return false;
        if (!entityCompatible(left, right)) return false;

        double title = jaccard(left.titleTokens(), right.titleTokens());
        double content = jaccard(left.contentShingles(), right.contentShingles());
        boolean enoughTitle = Math.min(left.titleTokens().size(), right.titleTokens().size()) >= 3;
        return (enoughTitle && title >= 0.72d) || content >= 0.88d;
    }

    private static boolean entityCompatible(Candidate left, Candidate right) {
        if (left.entityStatus() == EntityResolutionRules.Status.CONFLICT
                || right.entityStatus() == EntityResolutionRules.Status.CONFLICT
                || left.entityStatus() == EntityResolutionRules.Status.MULTIPLE
                || right.entityStatus() == EntityResolutionRules.Status.MULTIPLE) return false;
        if (left.entityKey() != null && right.entityKey() != null) {
            return left.entityKey().equals(right.entityKey());
        }
        // One resolved and one unresolved document may be a copied story, but only
        // byte-near content is strong enough to merge without guessing attribution.
        if ((left.entityKey() == null) != (right.entityKey() == null)) {
            return jaccard(left.contentShingles(), right.contentShingles()) >= 0.94d;
        }
        return true;
    }

    private static BatchSelection selectMain(List<StoryCluster> clusters, Coverage coverage,
                                             Config config, Set<Long> representedIds,
                                             Set<Long> attemptedIds) {
        List<StoryCluster> remaining = clusters.stream().filter(c -> !c.represented()).toList();
        LinkedHashSet<StoryCluster> ordered = new LinkedHashSet<>();
        for (IntelligenceTopic topic : TOPIC_SEED_ORDER) {
            if (coverage.representedTopics().contains(topic)) continue;
            remaining.stream().filter(c -> c.topics().contains(topic)).findFirst().ifPresent(ordered::add);
        }
        for (AcquisitionLane lane : AcquisitionLane.values()) {
            if (coverage.representedAcquisition().contains(lane)) continue;
            remaining.stream().filter(c -> c.acquisitionLanes().contains(lane)).findFirst().ifPresent(ordered::add);
        }
        if (coverage.primaryAvailable() && !coverage.primaryRepresented()) {
            remaining.stream().filter(StoryCluster::primaryEvidence).findFirst().ifPresent(ordered::add);
        }
        if (coverage.vietnamAvailable() && !coverage.vietnamRepresented()) {
            remaining.stream().filter(c -> c.members().stream()
                    .anyMatch(member -> member.marketLane() == MarketLane.VIETNAM))
                    .findFirst().ifPresent(ordered::add);
        }
        for (String entity : new TreeSet<>(coverage.missingEntities())) {
            remaining.stream().filter(c -> c.entityKeys().contains(entity)).findFirst().ifPresent(ordered::add);
        }
        ordered.addAll(remaining);

        List<Candidate> selectedDocs = new ArrayList<>();
        List<StoryCluster> selectedClusters = new ArrayList<>();
        Map<String, Integer> perSource = new LinkedHashMap<>();
        int remainingEmergencyBudget = Math.max(0,
                config.hardDocumentCeiling() - attemptedIds.size());
        for (StoryCluster cluster : ordered) {
            if (selectedClusters.size() >= config.batchClusters()
                    || selectedDocs.size() >= remainingEmergencyBudget) break;
            List<Candidate> representatives = representatives(cluster, attemptedIds,
                    perSource, config);
            if (representatives.size() > remainingEmergencyBudget - selectedDocs.size()) {
                representatives = representatives.subList(0,
                        Math.max(0, remainingEmergencyBudget - selectedDocs.size()));
            }
            if (representatives.isEmpty()) continue;
            selectedClusters.add(cluster);
            selectedDocs.addAll(representatives);
        }
        return new BatchSelection(List.copyOf(selectedDocs), List.copyOf(selectedClusters));
    }

    private static List<Candidate> representatives(StoryCluster cluster, Set<Long> attemptedIds,
                                                   Map<String, Integer> perSource, Config config) {
        List<Candidate> available = cluster.members().stream()
                .filter(c -> c.document().getId() == null || !attemptedIds.contains(c.document().getId()))
                .toList();
        if (available.isEmpty()) return List.of();
        List<Candidate> out = new ArrayList<>();
        Set<String> sources = new LinkedHashSet<>();
        for (Candidate candidate : available) {
            String source = candidate.sourceCode();
            if (perSource.getOrDefault(source, 0) >= config.maxDocumentsPerSourcePerBatch()) continue;
            if (!out.isEmpty() && sources.contains(source)) continue;
            out.add(candidate);
            sources.add(source);
            perSource.merge(source, 1, Integer::sum);
            if (out.size() >= config.representativesPerCluster()) break;
        }
        return List.copyOf(out);
    }

    private static BatchSelection selectAudit(List<StoryCluster> clusters, Config config,
                                              Set<Long> attemptedIds, String snapshot) {
        List<Candidate> pool = clusters.stream().filter(StoryCluster::represented)
                .flatMap(cluster -> cluster.members().stream())
                .filter(candidate -> candidate.document().getId() == null
                        || !attemptedIds.contains(candidate.document().getId()))
                .toList();
        Map<String, List<Candidate>> strata = new LinkedHashMap<>();
        pool.stream().sorted(Comparator.comparing(candidate -> auditHash(snapshot, candidate)))
                .forEach(candidate -> strata.computeIfAbsent(auditStratum(candidate), ignored -> new ArrayList<>())
                        .add(candidate));
        List<Candidate> selected = new ArrayList<>();
        Map<String, Integer> perSource = new LinkedHashMap<>();
        boolean added;
        do {
            added = false;
            for (List<Candidate> stratum : strata.values()) {
                if (selected.size() >= config.auditDocuments() || stratum.isEmpty()) break;
                Candidate candidate = stratum.remove(0);
                String source = candidate.sourceCode();
                if (perSource.getOrDefault(source, 0) >= config.maxDocumentsPerSourcePerBatch()) continue;
                selected.add(candidate);
                perSource.merge(source, 1, Integer::sum);
                added = true;
            }
        } while (added && selected.size() < config.auditDocuments());
        Set<Candidate> chosen = Set.copyOf(selected);
        List<StoryCluster> selectedClusters = clusters.stream()
                .filter(cluster -> cluster.members().stream().anyMatch(chosen::contains)).toList();
        return new BatchSelection(List.copyOf(selected), selectedClusters);
    }

    private static String auditStratum(Candidate candidate) {
        String band = candidate.priorityScore() >= 80 ? "HIGH"
                : candidate.priorityScore() >= 60 ? "MEDIUM" : "LOW";
        return band + "|" + candidate.acquisitionLane() + "|"
                + (candidate.authority().isPrimaryEvidence() ? "PRIMARY" : "SECONDARY") + "|"
                + candidate.topics().stream().sorted().findFirst().orElse(IntelligenceTopic.OTHER);
    }

    private static String auditHash(String snapshot, Candidate candidate) {
        return sha256(snapshot + "|" + Objects.toString(candidate.document().getId(),
                candidate.document().getContentHash()));
    }

    private static Coverage coverage(List<StoryCluster> clusters) {
        Set<IntelligenceTopic> availableTopics = union(clusters, StoryCluster::topics);
        Set<IntelligenceTopic> representedTopics = union(
                clusters.stream().filter(StoryCluster::represented).toList(), StoryCluster::topics);
        Set<AcquisitionLane> availableAcquisition = union(clusters, StoryCluster::acquisitionLanes);
        Set<AcquisitionLane> representedAcquisition = union(
                clusters.stream().filter(StoryCluster::represented).toList(), StoryCluster::acquisitionLanes);
        Set<String> availableEntities = union(clusters, StoryCluster::entityKeys);
        Set<String> representedEntities = union(
                clusters.stream().filter(StoryCluster::represented).toList(), StoryCluster::entityKeys);
        boolean primaryAvailable = clusters.stream().anyMatch(StoryCluster::primaryEvidence);
        boolean primaryRepresented = clusters.stream().filter(StoryCluster::represented)
                .anyMatch(StoryCluster::primaryEvidence);
        boolean vietnamAvailable = clusters.stream().flatMap(c -> c.members().stream())
                .anyMatch(c -> c.marketLane() == MarketLane.VIETNAM);
        boolean vietnamRepresented = clusters.stream().filter(StoryCluster::represented)
                .flatMap(c -> c.members().stream()).anyMatch(c -> c.marketLane() == MarketLane.VIETNAM);
        return new Coverage(availableTopics, representedTopics, availableAcquisition,
                representedAcquisition, availableEntities, representedEntities,
                primaryAvailable, primaryRepresented, vietnamAvailable, vietnamRepresented);
    }

    private static Candidate candidate(Classification classification, long ageDays, String targetMarket) {
        RawDoc doc = classification.getRawDoc();
        SourceAuthority authority = doc.getSource().getAuthority();
        Set<IntelligenceTopic> topics = topics(classification.getLabels());
        String market = value(doc.getSource().getDefaultMarketCode());
        CurationPriorityRules.Score priority = CurationPriorityRules.score(new CurationPriorityRules.Input(
                com.marketradar.domain.Department.STRATEGY, topics, targetMarket,
                market.isBlank() ? Set.of() : Set.of(market), authority.credibilityScore(),
                1, ageDays, false, true));
        int depthBoost = Math.min(5, Math.max(0, doc.getRawText().length() / 6_000));
        int primaryBoost = authority.isPrimaryEvidence() ? 5 : 0;
        String entityText = Objects.toString(doc.getTitle(), "") + "\n"
                + prefix(doc.getRawText(), 4_000);
        EntityResolutionRules.Resolution resolution = EntityResolutionRules.resolve(
                entityText, doc.getSource().getDefaultMarketCode());
        String entityKey = resolution.singleEntity() == null ? null : resolution.singleEntity().key();
        return new Candidate(classification,
                Math.min(100, priority.total() + depthBoost + primaryBoost),
                marketLane(doc), acquisitionLane(doc), authority, topics, entityKey,
                resolution.status(), titleTokens(doc.getTitle()), contentShingles(doc.getRawText()));
    }

    private static Comparator<Candidate> candidateOrder() {
        return Comparator.comparingInt(Candidate::priorityScore).reversed()
                .thenComparing(candidate -> candidate.authority().isPrimaryEvidence() ? 0 : 1)
                .thenComparing(candidate -> candidate.marketLane() == MarketLane.VIETNAM ? 0 : 1)
                .thenComparing(candidate -> candidate.document().getPublishedAt(), Comparator.reverseOrder())
                .thenComparing(Candidate::sourceCode)
                .thenComparing(candidate -> docKey(candidate.document()));
    }

    private static Comparator<StoryCluster> clusterOrder() {
        return Comparator.comparingInt(StoryCluster::priorityScore).reversed()
                .thenComparing(cluster -> cluster.primaryEvidence() ? 0 : 1)
                .thenComparing(cluster -> cluster.lead().marketLane() == MarketLane.VIETNAM ? 0 : 1)
                .thenComparing(cluster -> cluster.lead().document().getPublishedAt(), Comparator.reverseOrder())
                .thenComparing(StoryCluster::clusterKey);
    }

    private static Set<String> titleTokens(String title) {
        if (title == null || title.isBlank()) return Set.of();
        return tokens(title).stream().filter(token -> token.length() >= 2)
                .filter(token -> !TITLE_STOPWORDS.contains(token))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<Integer> contentShingles(String text) {
        List<String> tokens = tokens(prefix(text, CONTENT_PREFIX_CHARS));
        if (tokens.size() < 5) return Set.of();
        Set<Integer> out = new LinkedHashSet<>();
        for (int index = 0; index <= tokens.size() - 5 && out.size() < MAX_SHINGLES; index++) {
            out.add(String.join(" ", tokens.subList(index, index + 5)).hashCode());
        }
        return Set.copyOf(out);
    }

    private static List<String> tokens(String value) {
        if (value == null || value.isBlank()) return List.of();
        String normalized = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
        return normalized.isBlank() ? List.of() : List.of(normalized.split("\\s+"));
    }

    private static double jaccard(Set<?> left, Set<?> right) {
        if (left.isEmpty() || right.isEmpty()) return 0d;
        int intersection = 0;
        Set<?> smaller = left.size() <= right.size() ? left : right;
        Set<?> larger = left.size() <= right.size() ? right : left;
        for (Object value : smaller) if (larger.contains(value)) intersection++;
        return intersection / (double) (left.size() + right.size() - intersection);
    }

    private static boolean marketCompatible(MarketLane left, MarketLane right) {
        return left == right || left == MarketLane.UNKNOWN || right == MarketLane.UNKNOWN;
    }

    private static boolean disjoint(Set<?> left, Set<?> right) {
        return left.stream().noneMatch(right::contains);
    }

    private static boolean eligibleContent(RawDoc doc) {
        return doc != null && !doc.isSampleData() && doc.getDuplicateOfId() == null
                && doc.getParseStatus() == RawDoc.ParseStatus.OK && doc.isFullTextFetched()
                && doc.getRawText() != null && !doc.getRawText().isBlank()
                && doc.getSource() != null && doc.getSource().getUsePolicy().allowsAnalysis();
    }

    private static Set<IntelligenceTopic> topics(Set<Category> categories) {
        Set<IntelligenceTopic> out = new LinkedHashSet<>();
        if (categories != null) categories.forEach(category -> out.add(topic(category)));
        if (out.isEmpty()) out.add(IntelligenceTopic.OTHER);
        return Set.copyOf(out);
    }

    private static IntelligenceTopic topic(Category category) {
        if (category == null) return IntelligenceTopic.OTHER;
        return switch (category) {
            case MACRO_ECONOMIC -> IntelligenceTopic.MACRO_ECONOMIC;
            case INDUSTRY_REGULATION, PRODUCT_REGULATION -> IntelligenceTopic.REGULATION_POLICY;
            case MARKET_STRUCTURE, STRATEGIC_RESEARCH -> IntelligenceTopic.MARKET_STRUCTURE;
            case COMPANY_FINANCIAL_PERFORMANCE, SALES_DATA -> IntelligenceTopic.FINANCIAL_PERFORMANCE;
            case CORPORATE_ACTION -> IntelligenceTopic.CORPORATE_ACTION;
            case TECHNOLOGY_AI -> IntelligenceTopic.TECHNOLOGY_AI;
            case CUSTOMER_EXPERIENCE -> IntelligenceTopic.CUSTOMER_ENGAGEMENT;
            case PEOPLE_TALENT -> IntelligenceTopic.PEOPLE_TALENT;
            case BRAND_REPUTATION -> IntelligenceTopic.BRAND_REPUTATION;
            case PRODUCT_LAUNCH, FEE_BENEFIT_COMMISSION_CHANGE -> IntelligenceTopic.PRODUCT_OFFER;
            case DISTRIBUTION_CHANNEL -> IntelligenceTopic.DISTRIBUTION;
        };
    }

    private static MarketLane marketLane(RawDoc doc) {
        String market = value(doc.getSource().getDefaultMarketCode()).toUpperCase(Locale.ROOT);
        if ("VN".equals(market) || doc.getSource().getDefaultMarketScope() == GeographyScope.VIETNAM) {
            return MarketLane.VIETNAM;
        }
        return market.isBlank() || doc.getSource().getDefaultMarketScope() == GeographyScope.UNKNOWN
                ? MarketLane.UNKNOWN : MarketLane.INTERNATIONAL;
    }

    private static AcquisitionLane acquisitionLane(RawDoc doc) {
        return switch (doc.getIntakeMethod()) {
            case MANUAL_TEXT, FILE_UPLOAD -> AcquisitionLane.MANUAL;
            case OPEN_SEARCH, BROWSER_RENDER -> AcquisitionLane.DEEP_RESEARCH;
            case CRAWLED -> AcquisitionLane.WHITELIST;
        };
    }

    private static LocalDate publicationDate(RawDoc doc) {
        return doc.getPublishedAt() == null ? null : doc.getPublishedAt().atZone(REPORT_ZONE).toLocalDate();
    }

    private static String candidateSnapshot(List<Candidate> candidates) {
        return sha256(candidates.stream().map(Candidate::document)
                .map(doc -> Objects.toString(doc.getId(), doc.getUrl()) + "|" + doc.getContentHash())
                .sorted().collect(Collectors.joining("\n")));
    }

    private static String sourceCode(RawDoc doc) {
        return doc.getSource() == null ? "UNKNOWN" : value(doc.getSource().getCode());
    }

    private static String docKey(RawDoc doc) {
        return doc.getId() == null ? value(doc.getUrl())
                : String.format(Locale.ROOT, "%020d", doc.getId());
    }

    private static String prefix(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String value(String value) {
        return value == null ? "" : value.strip();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static int positive(int value, String field) {
        if (value <= 0) throw new IllegalArgumentException(field + " must be positive");
        return value;
    }

    private static <E extends Enum<E>> Map<E, Integer> zeroed(Class<E> type) {
        Map<E, Integer> out = new EnumMap<>(type);
        for (E value : type.getEnumConstants()) out.put(value, 0);
        return out;
    }

    private static <T> Set<T> union(Collection<StoryCluster> clusters,
                                    Function<StoryCluster, Set<T>> values) {
        Set<T> out = new LinkedHashSet<>();
        clusters.forEach(cluster -> out.addAll(values.apply(cluster)));
        return Set.copyOf(out);
    }

    private static <T> Set<T> difference(Set<T> all, Set<T> present) {
        Set<T> out = new LinkedHashSet<>(all);
        out.removeAll(present);
        return Set.copyOf(out);
    }

    private record BatchSelection(List<Candidate> documents, List<StoryCluster> clusters) {}

}
