package com.marketradar.product;

import com.marketradar.domain.*;
import com.marketradar.intelligence.MarketEventNormalizer;
import com.marketradar.intelligence.MarketEventReadService;
import com.marketradar.intelligence.MarketEventIntelligenceView;
import com.marketradar.intelligence.MarketEventService;
import com.marketradar.intelligence.ProductMaterialityRules;
import com.marketradar.intelligence.ProductMaterialityScorer;
import com.marketradar.intelligence.ProductEventTaxonomy;
import com.marketradar.repo.ClassificationRepository;
import com.marketradar.repo.EvidenceFactRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.marketradar.quality.ProductPublicationQualityGate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Builds immutable Product brief editions from normalized events, never legacy implications. */
@Service
public class ProductBriefService {

    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final MarketEventService eventService;
    private final MarketEventReadService eventReads;
    private final ClassificationRepository classifications;
    private final ProductMaterialityScorer materiality;
    private final ProductBriefEditionRepository editions;
    private final ProductBriefInsightRepository insights;
    private final EvidenceFactRepository facts;
    private final ProductInsightWriter writer;
    private final ProductInsightQualityGate qualityGate;

    public ProductBriefService(MarketEventService eventService, MarketEventReadService eventReads,
                               ClassificationRepository classifications,
                               ProductMaterialityScorer materiality,
                               ProductBriefEditionRepository editions,
                               ProductBriefInsightRepository insights,
                               EvidenceFactRepository facts,
                               ProductInsightWriter writer,
                               ProductInsightQualityGate qualityGate) {
        this.eventService = eventService;
        this.eventReads = eventReads;
        this.classifications = classifications;
        this.materiality = materiality;
        this.editions = editions;
        this.insights = insights;
        this.facts = facts;
        this.writer = writer;
        this.qualityGate = qualityGate;
    }

    /**
     * Creates a new immutable edition when the source fingerprint changed; otherwise
     * returns the existing edition.  Freshness is based on publication date, not the
     * ambiguous legacy eventDate (which may be an effective date or 2030 forecast).
     */
    @Transactional
    public ProductBriefEdition regenerate(int windowDays) {
        if (windowDays < 7 || windowDays > 365) {
            throw new IllegalArgumentException("windowDays must be between 7 and 365");
        }
        eventService.materializeMissing();

        LocalDate end = LocalDate.now(REPORT_ZONE);
        LocalDate start = end.minusDays(windowDays - 1L);
        Map<Long, Classification> classificationByDoc = classifications.findAllForDisplay().stream()
                .collect(Collectors.toMap(c -> c.getRawDoc().getId(), Function.identity(),
                        (newer, older) -> newer));

        List<ScoredEvent> scored = eventReads.readForSynthesis(
                        MarketEventNormalizer.PIPELINE_VERSION, end).stream()
                .filter(e -> e.publishedDate() != null
                        && !e.publishedDate().isBefore(start) && !e.publishedDate().isAfter(end))
                .map(e -> {
                    EvidenceFact fact = e.evidenceFact();
                    Classification classification = classificationByDoc.get(fact.getRawDoc().getId());
                    return new ScoredEvent(e, materiality.score(fact, classification));
                })
                .sorted(Comparator.comparing(se -> se.event().eventKey()))
                .toList();

        List<ProductBriefSynthesisRules.Signal> eligible = scored.stream()
                .filter(se -> se.score().publishEligible())
                .map(ProductBriefService::toSignal)
                .toList();
        List<ProductBriefSynthesisRules.Draft> drafts = ProductBriefSynthesisRules.synthesize(eligible);
        ProductInsightWriter.Version writerVersion = writer.version();
        ProductInsightQualityGate.Version qualityVersion = qualityGate.version();
        String publicationQualitySignature = ProductPublicationGateAdapter
                .qualitySignature(qualityVersion.signature());
        String fingerprint = fingerprint(start, end, scored, writerVersion,
                publicationQualitySignature);

        Optional<ProductBriefEdition> existing = editions
                .findTopByDepartmentAndAlgorithmVersionAndSourceFingerprintOrderByCreatedAtDesc(
                        Department.PRODUCT, ProductBriefSynthesisRules.ALGORITHM_VERSION, fingerprint);
        if (existing.isPresent()
                && existing.get().getStatus() != ProductBriefEdition.Status.GENERATION_FAILED) {
            return existing.get();
        }

        String baseEditionCode = "PROD-" + end.format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + fingerprint.substring(0, 10).toUpperCase(Locale.ROOT);

        List<PreparedInsight> prepared = new ArrayList<>();
        if (!drafts.isEmpty()) {
            try {
                for (ProductBriefSynthesisRules.Draft draft : drafts) {
                    ProductInsightWriter.WrittenInsight written = writer.write(draft);
                    List<EvidenceFact> cited = facts.findAllByFactCodeInForAudit(
                            written.citedFactCodes());
                    if (cited.size() != new LinkedHashSet<>(written.citedFactCodes()).size()) {
                        throw new ProductInsightWritingException(
                                "Product writer cited evidence that is not available for audit");
                    }
                    ProductInsightQualityGate.Result quality = qualityGate.evaluate(
                            written, cited, new LinkedHashSet<>(draft.factCodes()));
                    if (!quality.publishable()) {
                        throw new ProductInsightWritingException(
                                "Product candidate blocked: " + quality.status() + " — " + quality.detail());
                    }
                    ProductInsightContract.Shape shape = new ProductInsightContract.Shape(
                            draft.kiqCode(), written.headlineVi(), written.headlineEn(),
                            written.whatVi(), written.whatEn(), written.patternVi(), written.patternEn(),
                            written.soWhatVi(), written.soWhatEn(), written.nowWhatVi(), written.nowWhatEn(),
                            written.caveatVi(), written.caveatEn());
                    Set<String> citedCodes = new LinkedHashSet<>(written.citedFactCodes());
                    long docs = draft.signals().stream().filter(s -> citedCodes.contains(s.factCode()))
                            .map(ProductBriefSynthesisRules.Signal::rawDocId).distinct().count();
                    long sources = draft.signals().stream().filter(s -> citedCodes.contains(s.factCode()))
                            .map(ProductBriefSynthesisRules.Signal::sourceCode)
                            .filter(Objects::nonNull).distinct().count();
                    prepared.add(new PreparedInsight(draft, written,
                            cited.stream().map(EvidenceFact::getFactCode)
                                    .collect(Collectors.toCollection(LinkedHashSet::new))));
                }
            } catch (RuntimeException writingFailure) {
                return saveFailedEdition(baseEditionCode, start, end, fingerprint,
                        writerVersion, publicationQualitySignature, eligible.size(), writingFailure);
            }
        }

        List<ProductPublicationGateAdapter.Input> publicationInputs = prepared.stream()
                .map(p -> new ProductPublicationGateAdapter.Input(
                        p.draft(), p.written(), p.resolvedEvidenceIds())).toList();
        ProductPublicationGateAdapter.Evaluation publication;
        try {
            publication = ProductPublicationGateAdapter.evaluateMagazine(
                    publicationInputs, start, end, end);
        } catch (RuntimeException publicationFailure) {
            return saveFailedEdition(baseEditionCode, start, end, fingerprint,
                    writerVersion, publicationQualitySignature, eligible.size(), publicationFailure);
        }

        ProductBriefEdition edition = editions.save(new ProductBriefEdition(
                baseEditionCode, Department.PRODUCT, start, end,
                ProductBriefSynthesisRules.ALGORITHM_VERSION, fingerprint,
                writerVersion.providerModel(), writerVersion.promptSha256(),
                writerVersion.schemaVersion(), publicationQualitySignature,
                publication.magazine().status()
                        == ProductPublicationQualityGate.MagazineStatus.READY
                        ? ProductBriefEdition.Status.READY
                        : ProductBriefEdition.Status.INSUFFICIENT_EVIDENCE,
                eligible.size(), prepared.size(), null));

        int rank = 1;
        for (int index = 0; index < prepared.size(); index++) {
            PreparedInsight p = prepared.get(index);
            ProductPublicationQualityGate.InsightResult publicationResult =
                    publication.insightResults().get(index);
            ProductBriefSynthesisRules.Draft d = p.draft();
            ProductInsightWriter.WrittenInsight w = p.written();
            insights.save(new ProductBriefInsight(edition, rank++, d.kiqCode(), d.theme().name(),
                    w.headlineVi(), w.headlineEn(), w.whatVi(), w.whatEn(),
                    w.patternVi(), w.patternEn(), w.soWhatVi(), w.soWhatEn(),
                    w.nowWhatVi(), w.nowWhatEn(), w.caveatVi(), w.caveatEn(),
                    d.confidence(), d.materialityScore(), String.join(",", w.citedFactCodes()),
                    d.independentClusterCount(), d.independentDocumentCount(),
                    d.independentSourceCount(), d.conflictFree(), d.futureActionEligible(),
                    d.signals().stream().map(ProductBriefSynthesisRules.Signal::clusterKey)
                            .distinct().collect(Collectors.joining(",")),
                    ProductBriefInsight.PublicationDisposition.valueOf(
                            publicationResult.disposition().name()),
                    publicationResult.failureCodes().stream().map(Enum::name).sorted()
                            .collect(Collectors.joining(",")),
                    publicationResult.resolvedEvidenceRatio(), ProductPublicationGateAdapter.VERSION));
        }
        return edition;
    }

    private ProductBriefEdition saveFailedEdition(
            String baseCode, LocalDate start, LocalDate end, String fingerprint,
            ProductInsightWriter.Version writerVersion, String qualitySignature,
            int eligibleCount, RuntimeException failure) {
        String suffix = "-F" + Long.toUnsignedString(System.nanoTime(), 36)
                .toUpperCase(Locale.ROOT);
        String code = (baseCode + suffix).substring(0, Math.min(48, baseCode.length() + suffix.length()));
        return editions.save(new ProductBriefEdition(code, Department.PRODUCT, start, end,
                ProductBriefSynthesisRules.ALGORITHM_VERSION, fingerprint,
                writerVersion.providerModel(), writerVersion.promptSha256(),
                writerVersion.schemaVersion(), qualitySignature,
                ProductBriefEdition.Status.GENERATION_FAILED, eligibleCount, 0,
                failure.getMessage()));
    }

    @Transactional(readOnly = true)
    public Optional<BriefView> latest() {
        return editions.findTopByDepartmentOrderByCreatedAtDesc(Department.PRODUCT).map(this::view);
    }

    @Transactional(readOnly = true)
    public Optional<BriefView> current(LocalDate windowStart, LocalDate windowEnd) {
        return editions.findTopByDepartmentAndWindowStartAndWindowEndOrderByCreatedAtDesc(
                Department.PRODUCT, windowStart, windowEnd).map(this::view);
    }

    @Transactional(readOnly = true)
    public Optional<BriefView> findByCode(String editionCode) {
        return editions.findByEditionCode(editionCode).map(this::view);
    }

    private BriefView view(ProductBriefEdition edition) {
        List<ProductBriefInsight> editionInsights = insights.findByEditionOrderByRankOrderAsc(edition);
        List<String> citedCodes = editionInsights.stream()
                .flatMap(insight -> splitCodes(insight.getFactCodesCsv()).stream())
                .distinct().toList();
        Map<String, EvidenceFact> byCode = (citedCodes.isEmpty() ? List.<EvidenceFact>of()
                : facts.findAllByFactCodeInForAudit(citedCodes)).stream()
                .collect(Collectors.toMap(EvidenceFact::getFactCode, Function.identity(), (a, b) -> a));
        Map<Long, List<EvidenceFact>> evidenceByInsight = new LinkedHashMap<>();
        for (ProductBriefInsight insight : editionInsights) {
            List<EvidenceFact> cited = splitCodes(insight.getFactCodesCsv()).stream()
                    .map(byCode::get).filter(Objects::nonNull).toList();
            evidenceByInsight.put(insight.getId(), cited);
        }
        return new BriefView(edition, editionInsights, evidenceByInsight);
    }

    private static ProductBriefSynthesisRules.Signal toSignal(ScoredEvent scored) {
        MarketEventIntelligenceView e = scored.event();
        EvidenceFact f = e.evidenceFact();
        ProductEventTaxonomy.EventType canonicalType = ProductEventTaxonomy.classify(
                f.getRawDoc().getTitle(), firstNonBlank(f.getSummaryEn(), f.getSummaryVi()),
                f.getSpanText(), e.eventType().name());
        Set<String> kiqs = scored.score().productKiqs().stream()
                .map(Enum::name).collect(Collectors.toCollection(LinkedHashSet::new));
        // Geography is known only after MarketEvent normalization. Regional product
        // launches and concrete channel innovations belong to the transfer KIQ.
        boolean regional = !isVietnam(e, f);
        if (regional
                && (ProductEventTaxonomy.isProductOffer(canonicalType)
                || canonicalType == ProductEventTaxonomy.EventType.DISTRIBUTION_CHANGE
                || canonicalType == ProductEventTaxonomy.EventType.SERVICE_EXPERIENCE_CHANGE
                || kiqs.contains("KIQ_4_TRANSFERABLE_INNOVATION"))) {
            kiqs.add("KIQ_4_TRANSFERABLE_INNOVATION");
        }
        return new ProductBriefSynthesisRules.Signal(
                e.evidenceFactCode(), f.getRawDoc().getId(), f.getRawDoc().getSource().getCode(),
                f.getRawDoc().getSource().getTier(),
                e.company(), e.productName(), f.getRawDoc().getTitle(), f.getSpanText(),
                canonicalType.name(), regional ? "REGIONAL" : "VIETNAM",
                f.getExtractionRun() == null ? "UNKNOWN_LEGACY" : f.getExtractionRun().getModelVersion(),
                MarketEventNormalizer.PIPELINE_VERSION,
                e.publishedDate(), e.clusterKey(), e.clusterDocumentCount(),
                e.independentSourceCount(), e.conflictState().name(),
                e.effectiveDate(), e.expiryDate(), e.temporalStatus().name(),
                e.futureActionEligible(), f.getSummaryVi(), f.getSummaryEn(),
                scored.score().total(), kiqs);
    }

    private static boolean isVietnam(MarketEventIntelligenceView event, EvidenceFact fact) {
        String geography = event.geography() == null ? "" : event.geography().toLowerCase(Locale.ROOT);
        return geography.contains("vietnam") || geography.contains("việt nam")
                || "vi".equalsIgnoreCase(fact.getRawDoc().getSource().getLanguage());
    }

    private static String fingerprint(LocalDate start, LocalDate end, List<ScoredEvent> scored,
                                      ProductInsightWriter.Version writerVersion,
                                      String publicationQualitySignature) {
        StringBuilder input = new StringBuilder(ProductBriefSynthesisRules.ALGORITHM_VERSION)
                .append('|').append(MarketEventNormalizer.PIPELINE_VERSION)
                .append('|').append(ProductEventTaxonomy.VERSION)
                .append('|').append(ProductMaterialityRules.RULES_VERSION)
                .append('|').append(ProductMaterialityRules.PUBLISH_THRESHOLD)
                .append('|').append(writerVersion.providerModel())
                .append('|').append(writerVersion.promptSha256())
                .append('|').append(writerVersion.schemaVersion())
                .append('|').append(publicationQualitySignature)
                .append('|').append(start).append('|').append(end);
        for (ScoredEvent se : scored) {
            input.append('|').append(se.event().eventKey())
                    .append(':').append(se.event().clusterKey())
                    .append(':').append(se.event().conflictState())
                    .append(':').append(se.event().temporalStatus())
                    .append(':').append(se.event().futureActionEligible())
                    .append(':').append(se.event().effectiveDate())
                    .append(':').append(se.event().expiryDate())
                    .append(':').append(se.score().total())
                    .append(':').append(se.score().publishEligible());
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.toString().getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> splitCodes(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private record ScoredEvent(MarketEventIntelligenceView event,
                               ProductMaterialityRules.Score score) {}
    private record PreparedInsight(ProductBriefSynthesisRules.Draft draft,
                                   ProductInsightWriter.WrittenInsight written,
                                   Set<String> resolvedEvidenceIds) {}

    public record BriefView(ProductBriefEdition edition, List<ProductBriefInsight> insights,
                            Map<Long, List<EvidenceFact>> evidenceByInsight) {}
}
