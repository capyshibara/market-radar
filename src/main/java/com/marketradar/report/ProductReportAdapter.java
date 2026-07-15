package com.marketradar.report;

import com.marketradar.domain.EvidenceFact;
import com.marketradar.product.ProductBriefEdition;
import com.marketradar.product.ProductBriefInsight;
import com.marketradar.product.ProductBriefService;
import com.marketradar.product.ProductInsightContract;
import com.marketradar.product.ProductPublicationGateAdapter;
import com.marketradar.product.ProductReportCadence;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.time.LocalDate;

/**
 * Read-only bridge from the immutable Product brief into the legacy report views.
 *
 * LOW-confidence insights remain visible only as single-source watch signals.  They
 * are deliberately kept out of executive insight/trend copy, where readers could
 * otherwise mistake one article for corroborated market intelligence.
 */
@Component
public class ProductReportAdapter {

    static final int MAX_EXECUTIVE_INSIGHTS = 4;
    static final int MAX_WATCH_SIGNALS = 2;
    static final int MIN_DECISION_READY_INSIGHTS = 3;

    public enum Placement { EXECUTIVE, WATCH }
    public enum Availability { READY, INSUFFICIENT_EVIDENCE }
    public enum InsufficientReason {
        NONE,
        NO_CURRENT_EDITION,
        LEGACY_OR_UNVERIFIED_EDITION,
        NO_CORROBORATED_INSIGHT
    }

    private final ProductBriefService briefs;
    private final com.marketradar.product.ProductInsightQualityGate qualityGate;
    private final com.marketradar.product.ProductInsightWriter writer;

    public ProductReportAdapter(ProductBriefService briefs,
                                com.marketradar.product.ProductInsightQualityGate qualityGate,
                                com.marketradar.product.ProductInsightWriter writer) {
        this.briefs = briefs;
        this.qualityGate = qualityGate;
        this.writer = writer;
    }

    /** The only current-report path: exact cadence dates, never "latest of any window". */
    public Snapshot current(ProductReportCadence cadence, LocalDate asOf) {
        LocalDate start = cadence.start(asOf);
        return briefs.current(start, asOf)
                .map(view -> adapt(view, start, asOf))
                .orElseGet(() -> Snapshot.insufficient(start, asOf,
                        InsufficientReason.NO_CURRENT_EDITION));
    }

    private Snapshot adapt(ProductBriefService.BriefView view,
                           LocalDate windowStart, LocalDate windowEnd) {
            if (!editionUsesCurrentVerifiedWriter(view.edition())) {
                return new Snapshot(view.edition(), Availability.INSUFFICIENT_EVIDENCE,
                        InsufficientReason.LEGACY_OR_UNVERIFIED_EDITION,
                        windowStart, windowEnd, List.of(), List.of(), Map.of());
            }
            List<ProductBriefInsight> executive = view.insights().stream()
                    .filter(i -> placement(i.getPublicationDisposition())
                            == Placement.EXECUTIVE)
                    .limit(MAX_EXECUTIVE_INSIGHTS)
                    .toList();
            List<ProductBriefInsight> watch = view.insights().stream()
                    .filter(i -> placement(i.getPublicationDisposition())
                            == Placement.WATCH)
                    .limit(MAX_WATCH_SIGNALS)
                    .toList();

            Map<Long, List<EvidenceFact>> cited = new LinkedHashMap<>();
            for (ProductBriefInsight insight : executive) {
                cited.put(insight.getId(), List.copyOf(
                        view.evidenceByInsight().getOrDefault(insight.getId(), List.of())));
            }
            for (ProductBriefInsight insight : watch) {
                cited.put(insight.getId(), List.copyOf(
                        view.evidenceByInsight().getOrDefault(insight.getId(), List.of())));
            }
            Availability availability = executive.size() < MIN_DECISION_READY_INSIGHTS
                    || view.edition().getStatus() != ProductBriefEdition.Status.READY
                    ? Availability.INSUFFICIENT_EVIDENCE : Availability.READY;
            InsufficientReason reason = availability == Availability.READY
                    ? InsufficientReason.NONE : InsufficientReason.NO_CORROBORATED_INSIGHT;
            return new Snapshot(view.edition(), availability, reason, windowStart, windowEnd,
                    executive, watch, Map.copyOf(cited));
    }

    /** Persisted gate disposition is authoritative. REJECT/null are never rendered. */
    public static Placement placement(ProductBriefInsight.PublicationDisposition disposition) {
        if (disposition == ProductBriefInsight.PublicationDisposition.DECISION_READY) {
            return Placement.EXECUTIVE;
        }
        if (disposition == ProductBriefInsight.PublicationDisposition.WATCH) {
            return Placement.WATCH;
        }
        return null;
    }

    private boolean editionUsesCurrentVerifiedWriter(ProductBriefEdition edition) {
        com.marketradar.product.ProductInsightWriter.Version writerVersion = writer.version();
        return edition != null
                && ProductInsightContract.SCHEMA_VERSION.equals(edition.getInsightSchemaVersion())
                && edition.getWriterPromptSha256() != null
                && edition.getWriterPromptSha256().length() == 64
                && edition.getWriterProvider() != null
                && !edition.getWriterProvider().startsWith("STUB")
                && edition.getWriterProvider().equals(writerVersion.providerModel())
                && edition.getWriterPromptSha256().equals(writerVersion.promptSha256())
                && edition.getQualitySignature() != null
                && edition.getQualitySignature().equals(ProductPublicationGateAdapter
                        .qualitySignature(qualityGate.version().signature()));
    }

    public record Snapshot(ProductBriefEdition edition,
                           Availability availability,
                           InsufficientReason insufficientReason,
                           LocalDate windowStart,
                           LocalDate windowEnd,
                           List<ProductBriefInsight> executiveInsights,
                           List<ProductBriefInsight> watchSignals,
                           Map<Long, List<EvidenceFact>> evidenceByInsight) {
        static Snapshot insufficient(LocalDate start, LocalDate end, InsufficientReason reason) {
            return new Snapshot(null, Availability.INSUFFICIENT_EVIDENCE, reason,
                    start, end, List.of(), List.of(), Map.of());
        }

        public boolean decisionReady() { return availability == Availability.READY; }

        public ProductBriefInsight leadInsight() {
            return executiveInsights.isEmpty() ? null : executiveInsights.get(0);
        }

        /** Exact evidence universe approved for this immutable Product edition. */
        public Set<String> citedFactCodes() {
            Set<String> codes = new LinkedHashSet<>();
            evidenceByInsight.values().forEach(list -> list.forEach(f -> codes.add(f.getFactCode())));
            return Set.copyOf(codes);
        }

        public List<EvidenceFact> references() {
            Map<String, EvidenceFact> used = new LinkedHashMap<>();
            evidenceByInsight.values().forEach(list -> list.forEach(
                    f -> used.putIfAbsent(f.getFactCode(), f)));
            return List.copyOf(used.values());
        }
    }
}
