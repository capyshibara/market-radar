package com.marketradar.intelligence;

import com.marketradar.domain.MarketEvent;
import com.marketradar.domain.MarketEventCluster;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Conservative deterministic fact-to-event clustering; no semantic guesswork or LLM. */
public final class MarketEventClustering {

    public static final String VERSION = "market-event-cluster-v2-dimension-safe";

    private MarketEventClustering() {}

    public static List<ClusterDraft> cluster(List<MarketEvent> events) {
        Map<String, List<MarketEvent>> groups = new LinkedHashMap<>();
        events.stream().sorted(Comparator.comparing(MarketEvent::getEventKey))
                .forEach(event -> groups.computeIfAbsent(clusterKey(event), ignored -> new ArrayList<>())
                        .add(event));
        return groups.entrySet().stream().map(e -> summarize(e.getKey(), e.getValue())).toList();
    }

    public static String clusterKey(MarketEvent event) {
        Long docId = event.getEvidenceFact().getRawDoc().getId();
        String entity = normalize(event.getSubjectEntityKey());
        if (entity.isEmpty()) entity = normalize(event.getCompany());
        String product = normalize(event.getProductName());
        String kpi = normalize(event.getEvidenceFact().getKpiLabel());
        String topic = event.getIntelligenceTopic() == null
                ? "" : event.getIntelligenceTopic().name();
        String market = normalize(event.getMarketCode());

        /*
         * Cross-document grouping has two deliberately narrow lanes:
         * 1) a resolved entity plus a verbatim product name; or
         * 2) a named KPI plus entity/market and the reusable intelligence topic.
         *
         * Lane 2 lets two publishers corroborate market share, APE, GDP or another
         * explicitly labelled metric. It does not group generic company articles,
         * avoiding the old failure mode where unrelated news about one insurer was
         * mistaken for one trend. Any missing discriminator fails closed to the
         * originating document.
         */
        String identity;
        if (!entity.isEmpty() && !product.isEmpty()) {
            identity = "ENTITY:" + entity + "|PRODUCT:" + product;
        } else if (!kpi.isEmpty() && !topic.isEmpty() && (!entity.isEmpty() || !market.isEmpty())) {
            identity = "SUBJECT:" + (!entity.isEmpty() ? entity : "MARKET:" + market)
                    + "|TOPIC:" + topic + "|KPI:" + kpi;
        } else {
            identity = "DOC:" + (docId == null ? event.getEventKey() : docId);
        }
        LocalDate anchor = anchor(event);
        String month = anchor == null ? "UNDATED" : YearMonth.from(anchor).toString();
        String raw = VERSION + "|" + identity + "|" + event.getEventType()
                + "|MARKET:" + market + "|" + month;
        return sha256(raw);
    }

    private static ClusterDraft summarize(String key, List<MarketEvent> events) {
        MarketEvent first = events.get(0);
        Set<String> sources = new TreeSet<>();
        Set<String> documents = new TreeSet<>();
        Set<String> facts = new TreeSet<>();
        Set<LocalDate> occurred = new TreeSet<>(), effective = new TreeSet<>(), expiry = new TreeSet<>();
        for (MarketEvent event : events) {
            sources.add(event.getSourceCode());
            Long rawDocId = event.getEvidenceFact().getRawDoc().getId();
            documents.add(rawDocId == null ? "EVENT:" + event.getEventKey() : "DOC:" + rawDocId);
            facts.add(event.getEvidenceFactCode());
            if (event.getOccurredDate() != null) occurred.add(event.getOccurredDate());
            if (event.getEffectiveDate() != null) effective.add(event.getEffectiveDate());
            if (event.getExpiryDate() != null) expiry.add(event.getExpiryDate());
        }
        boolean dateConflict = occurred.size() > 1 || effective.size() > 1 || expiry.size() > 1;
        LocalDate anchor = events.stream().map(MarketEventClustering::anchor)
                .filter(java.util.Objects::nonNull).min(LocalDate::compareTo).orElse(null);
        return new ClusterDraft(key, first.getEventType(), first.getCompany(), first.getProductName(),
                first.getGeography(), anchor, events.size(), documents.size(), sources.size(),
                sources.size() >= 2 ? MarketEventCluster.ProvenanceState.INDEPENDENT_SOURCES
                        : MarketEventCluster.ProvenanceState.SINGLE_SOURCE,
                dateConflict ? MarketEventCluster.ConflictState.DATE_CONFLICT
                        : MarketEventCluster.ConflictState.NONE,
                String.join(",", facts), String.join(",", sources), List.copyOf(events));
    }

    private static LocalDate anchor(MarketEvent event) {
        if (event.getOccurredDate() != null) return event.getOccurredDate();
        if (event.getEffectiveDate() != null) return event.getEffectiveDate();
        return event.getPublishedDate();
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public record ClusterDraft(String clusterKey, MarketEvent.EventType eventType,
                               String company, String productName, String geography,
                               LocalDate anchorDate, int factCount, int documentCount,
                               int independentSourceCount,
                               MarketEventCluster.ProvenanceState provenanceState,
                               MarketEventCluster.ConflictState conflictState,
                               String evidenceFactCodes, String sourceCodes,
                               List<MarketEvent> members) {}
}
