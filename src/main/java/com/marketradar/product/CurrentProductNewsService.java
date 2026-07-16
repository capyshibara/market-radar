package com.marketradar.product;

import com.marketradar.domain.Classification;
import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.RawDoc;
import com.marketradar.repo.ClassificationRepository;
import com.marketradar.repo.EvidenceFactRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read-only current-news layer used when the decision brief is sparse. This
 * service has no LLM calls and does not reuse legacy narrative claims.
 */
@Service
public class CurrentProductNewsService {

    public static final int MAX_ITEMS = 12;
    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final EvidenceFactRepository facts;
    private final ClassificationRepository classifications;
    private final Clock clock;

    @Autowired
    public CurrentProductNewsService(EvidenceFactRepository facts,
                                     ClassificationRepository classifications) {
        this(facts, classifications, Clock.system(REPORT_ZONE));
    }

    CurrentProductNewsService(EvidenceFactRepository facts,
                              ClassificationRepository classifications,
                              Clock clock) {
        this.facts = facts;
        this.classifications = classifications;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<CurrentProductNewsItem> findCurrent(ProductReportCadence cadence) {
        return current(cadence, LocalDate.now(clock));
    }

    /** Explicit as-of seam keeps all Product read models on the same rolling window. */
    @Transactional(readOnly = true)
    public List<CurrentProductNewsItem> current(ProductReportCadence cadence, LocalDate asOf) {
        if (cadence == null) throw new IllegalArgumentException("cadence is required");
        if (asOf == null) throw new IllegalArgumentException("asOf is required");
        List<EvidenceFact> candidates = facts.findCurrentProductNewsCandidates();
        if (candidates.isEmpty()) return List.of();

        List<Long> docIds = candidates.stream().map(f -> f.getRawDoc().getId()).distinct().toList();
        Map<Long, Classification> classificationByDoc = classifications.findByRawDocIdIn(docIds).stream()
                .collect(Collectors.toMap(c -> c.getRawDoc().getId(), c -> c, (first, ignored) -> first));

        List<EvidenceFact> ordered = candidates.stream()
                .filter(f -> allowed(f, classificationByDoc.get(f.getRawDoc().getId()), cadence, asOf))
                .sorted(Comparator
                        .comparing((EvidenceFact f) -> publicationDate(f), Comparator.reverseOrder())
                        .thenComparingInt(f -> f.getRawDoc().getSource().getTier())
                        .thenComparing(EvidenceFact::getFactCode))
                .toList();

        Set<Long> selectedDocuments = new HashSet<>();
        List<CurrentProductNewsItem> uniqueItems = new ArrayList<>();
        for (EvidenceFact fact : ordered) {
            RawDoc doc = fact.getRawDoc();
            if (!selectedDocuments.add(doc.getId())) continue; // one source item per document
            uniqueItems.add(toItem(fact, classificationByDoc.get(doc.getId()), asOf));
        }
        return selectBalancedCoverage(uniqueItems);
    }

    private static boolean allowed(EvidenceFact fact, Classification classification,
                                   ProductReportCadence cadence, LocalDate asOf) {
        RawDoc doc = fact.getRawDoc();
        Set<String> labels = classification == null ? Set.of() : classification.getLabels().stream()
                .map(Enum::name).collect(Collectors.toUnmodifiableSet());
        CurrentProductNewsPolicy.Input input = new CurrentProductNewsPolicy.Input(
                fact.isActive(), doc.getSource().isActive(), doc.getRawText(), doc.isFullTextFetched(),
                doc.getParseStatus() == null ? null : doc.getParseStatus().name(), doc.isSampleData(),
                doc.getDuplicateOfId() != null, doc.getSource().getTier(), publicationDate(fact),
                classification == null ? null : classification.getStatus().name(), labels,
                doc.getTitle(), fact.getSpanText());
        return CurrentProductNewsPolicy.evaluate(input, cadence, asOf).eligible();
    }

    private static CurrentProductNewsItem toItem(EvidenceFact fact,
                                                 Classification classification,
                                                 LocalDate asOf) {
        RawDoc doc = fact.getRawDoc();
        Set<String> labels = classification == null ? Set.of() : classification.getLabels().stream()
                .map(Enum::name).collect(Collectors.toUnmodifiableSet());
        LocalDate published = publicationDate(fact);
        CurrentProductNewsTopic topic = CurrentProductNewsTopic.from(labels, fact.getFactType().name());
        return new CurrentProductNewsItem(fact.getFactCode(), doc.getId(), doc.getTitle(),
                doc.getSource().getCode(), doc.getSource().getName(), doc.getSource().getTier(),
                doc.getUrl(), published, fact.getFactType().name(), fact.getSpanText(), topic,
                ChronoUnit.DAYS.between(published, asOf), fact.getSummaryVi(), fact.getSummaryEn(),
                fact.getSpanLanguage());
    }

    /**
     * Prevent one noisy category from consuming the whole brief. The newest
     * item in every available Product topic is selected before any topic gets a
     * second item; selected cards are then grouped in stable editorial order.
     */
    /** Public deterministic seam used by coverage regression tests. */
    public static List<CurrentProductNewsItem> selectBalancedCoverage(List<CurrentProductNewsItem> items) {
        if (items == null || items.isEmpty()) return List.of();
        EnumMap<CurrentProductNewsTopic, List<CurrentProductNewsItem>> byTopic =
                new EnumMap<>(CurrentProductNewsTopic.class);
        for (CurrentProductNewsItem item : items) {
            if (item == null || item.topic() == null) continue;
            byTopic.computeIfAbsent(item.topic(), ignored -> new ArrayList<>()).add(item);
        }
        byTopic.values().forEach(topicItems -> topicItems.sort(Comparator
                .comparing(CurrentProductNewsItem::publishedDate, Comparator.reverseOrder())
                .thenComparingInt(CurrentProductNewsItem::sourceTier)
                .thenComparing(CurrentProductNewsItem::factCode)));

        List<CurrentProductNewsItem> selected = new ArrayList<>();
        for (int round = 0; selected.size() < MAX_ITEMS; round++) {
            boolean found = false;
            for (CurrentProductNewsTopic topic : CurrentProductNewsTopic.values()) {
                List<CurrentProductNewsItem> topicItems = byTopic.getOrDefault(topic, List.of());
                if (round < topicItems.size()) {
                    selected.add(topicItems.get(round));
                    found = true;
                    if (selected.size() == MAX_ITEMS) break;
                }
            }
            if (!found) break;
        }
        selected.sort(Comparator
                .comparingInt((CurrentProductNewsItem item) -> item.topic().ordinal())
                .thenComparing(CurrentProductNewsItem::publishedDate, Comparator.reverseOrder())
                .thenComparing(CurrentProductNewsItem::factCode));
        return List.copyOf(selected);
    }

    private static LocalDate publicationDate(EvidenceFact fact) {
        if (fact == null || fact.getRawDoc() == null || fact.getRawDoc().getPublishedAt() == null) {
            return null;
        }
        return fact.getRawDoc().getPublishedAt().atZone(REPORT_ZONE).toLocalDate();
    }
}
