package com.marketradar.pipeline;

import com.marketradar.classify.ClassificationInputPolicy;
import com.marketradar.domain.ClaimVerification;
import com.marketradar.domain.Classification;
import com.marketradar.domain.FactExtractionRun;
import com.marketradar.domain.InterpretedClaim;
import com.marketradar.domain.RawDoc;
import com.marketradar.repo.ClaimVerificationRepository;
import com.marketradar.repo.ClassificationRepository;
import com.marketradar.repo.EvidenceFactRepository;
import com.marketradar.repo.FactExtractionRunRepository;
import com.marketradar.repo.InterpretedClaimRepository;
import com.marketradar.repo.RawDocRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Read-only funnel snapshot. It never mutates, approves or publishes anything. */
@Service
public class PipelineCheckpointService {

    public record Corpus(long documents, long usableDocuments, long shallowDocuments,
                         long datedDocuments, long duplicateDocuments) {}
    public record Curation(long classifications, long confirmed, long outOfScope,
                           long uncertainReview, long noLabelReview) {}
    public record Evidence(long activeFacts, long activeFactDocuments,
                           long latestAttempts, long successfulDocuments,
                           long emptyResults, long llmErrors, long schemaRejected) {}
    public record Analysis(long activePipelineClaims, long gateL1Passed,
                           Map<InterpretedClaim.GateStatus, Long> gateStatuses,
                           Map<InterpretedClaim.ReviewStatus, Long> reviewStatuses) {}
    public record Verification(long latestVerdicts, long entailed, long neutral,
                               long contradicted, long verifierErrors,
                               long reportEligibleClaims) {}
    public record Snapshot(Instant generatedAt, Corpus corpus, Curation curation,
                           Evidence evidence, Analysis analysis, Verification verification,
                           List<PipelineCheckpointRules.Checkpoint> checkpoints) {}

    private final RawDocRepository docs;
    private final ClassificationRepository classifications;
    private final EvidenceFactRepository facts;
    private final FactExtractionRunRepository extractionRuns;
    private final InterpretedClaimRepository claims;
    private final ClaimVerificationRepository verifications;

    public PipelineCheckpointService(RawDocRepository docs,
                                     ClassificationRepository classifications,
                                     EvidenceFactRepository facts,
                                     FactExtractionRunRepository extractionRuns,
                                     InterpretedClaimRepository claims,
                                     ClaimVerificationRepository verifications) {
        this.docs = docs;
        this.classifications = classifications;
        this.facts = facts;
        this.extractionRuns = extractionRuns;
        this.claims = claims;
        this.verifications = verifications;
    }

    @Transactional(readOnly = true)
    public Snapshot inspect() {
        List<RawDoc> allDocs = docs.findAllWithSource();
        long usable = allDocs.stream().filter(d -> d.getParseStatus() == RawDoc.ParseStatus.OK)
                .filter(d -> d.getDuplicateOfId() == null)
                .filter(d -> ClassificationInputPolicy.assess(d).eligible()).count();
        long dated = allDocs.stream().filter(d -> d.getPublishedAt() != null).count();
        long duplicates = allDocs.stream().filter(d -> d.getDuplicateOfId() != null).count();
        Corpus corpus = new Corpus(allDocs.size(), usable, allDocs.size() - usable,
                dated, duplicates);

        List<Classification> allClassifications = classifications.findAllForDisplay();
        Map<Classification.Status, Long> classificationStatuses = counts(
                allClassifications, Classification::getStatus, Classification.Status.class);
        Curation curation = new Curation(allClassifications.size(),
                classificationStatuses.getOrDefault(Classification.Status.CONFIRMED, 0L),
                classificationStatuses.getOrDefault(Classification.Status.OUT_OF_SCOPE, 0L),
                classificationStatuses.getOrDefault(Classification.Status.UNCERTAIN_REVIEW, 0L),
                classificationStatuses.getOrDefault(Classification.Status.NO_LABEL_REVIEW, 0L));

        List<FactExtractionRun> latestExtractionRuns = latestExtractionRuns(
                extractionRuns.findAll());
        Map<FactExtractionRun.Status, Long> extractionStatuses = counts(
                latestExtractionRuns, FactExtractionRun::getStatus, FactExtractionRun.Status.class);
        var activeFacts = facts.findAllForReport();
        long factDocuments = activeFacts.stream().map(f -> f.getRawDoc().getId()).distinct().count();
        Evidence evidence = new Evidence(activeFacts.size(), factDocuments,
                latestExtractionRuns.size(),
                extractionStatuses.getOrDefault(FactExtractionRun.Status.SUCCESS, 0L),
                extractionStatuses.getOrDefault(FactExtractionRun.Status.EMPTY_RESULT, 0L),
                extractionStatuses.getOrDefault(FactExtractionRun.Status.LLM_ERROR, 0L),
                extractionStatuses.getOrDefault(FactExtractionRun.Status.SCHEMA_REJECTED, 0L));

        List<InterpretedClaim> activeClaims = claims.findAllForAudit().stream()
                .filter(c -> !c.isSuperseded())
                .filter(c -> c.getOrigin() == InterpretedClaim.Origin.PIPELINE)
                .toList();
        Map<InterpretedClaim.GateStatus, Long> gateStatuses = counts(
                activeClaims, InterpretedClaim::getGateStatus, InterpretedClaim.GateStatus.class);
        Map<InterpretedClaim.ReviewStatus, Long> reviewStatuses = counts(
                activeClaims, InterpretedClaim::getReviewStatus, InterpretedClaim.ReviewStatus.class);
        long l1Passed = gateStatuses.getOrDefault(InterpretedClaim.GateStatus.PASS, 0L);
        Analysis analysis = new Analysis(activeClaims.size(), l1Passed,
                gateStatuses, reviewStatuses);

        Map<Long, ClaimVerification> latestByClaim = new LinkedHashMap<>();
        verifications.findAll().stream()
                .filter(v -> v.getClaim() != null && v.getClaim().getId() != null)
                .filter(v -> !v.getClaim().isSuperseded())
                .filter(v -> v.getClaim().getOrigin() == InterpretedClaim.Origin.PIPELINE)
                .sorted(java.util.Comparator.comparing(ClaimVerification::getCreatedAt)
                        .thenComparing(ClaimVerification::getId))
                .forEach(v -> latestByClaim.put(v.getClaim().getId(), v));
        Map<ClaimVerification.Verdict, Long> verdicts = counts(
                List.copyOf(latestByClaim.values()), ClaimVerification::getVerdict,
                ClaimVerification.Verdict.class);
        long reportEligible = claims.findForBiReport().size();
        Verification verification = new Verification(latestByClaim.size(),
                verdicts.getOrDefault(ClaimVerification.Verdict.ENTAILED, 0L),
                verdicts.getOrDefault(ClaimVerification.Verdict.NEUTRAL, 0L),
                verdicts.getOrDefault(ClaimVerification.Verdict.CONTRADICTED, 0L),
                verdicts.getOrDefault(ClaimVerification.Verdict.VERIFIER_ERROR, 0L),
                reportEligible);

        PipelineCheckpointRules.Metrics metrics = new PipelineCheckpointRules.Metrics(
                corpus.documents(), corpus.usableDocuments(), curation.classifications(),
                curation.confirmed(), evidence.latestAttempts(), evidence.successfulDocuments(),
                evidence.llmErrors() + evidence.schemaRejected(), evidence.activeFacts(),
                evidence.activeFactDocuments(), analysis.activePipelineClaims(),
                analysis.gateL1Passed(), verification.latestVerdicts(), verification.entailed(),
                verification.neutral(), verification.contradicted(), verification.verifierErrors(),
                verification.reportEligibleClaims());
        return new Snapshot(Instant.now(), corpus, curation, evidence, analysis, verification,
                PipelineCheckpointRules.evaluate(metrics));
    }

    private static List<FactExtractionRun> latestExtractionRuns(List<FactExtractionRun> all) {
        Map<Long, FactExtractionRun> latest = new LinkedHashMap<>();
        all.stream().filter(r -> r.getRawDoc() != null && r.getRawDoc().getId() != null)
                .sorted(java.util.Comparator.comparing(FactExtractionRun::getStartedAt)
                        .thenComparing(FactExtractionRun::getId))
                .forEach(r -> latest.put(r.getRawDoc().getId(), r));
        return List.copyOf(latest.values());
    }

    private static <T, E extends Enum<E>> Map<E, Long> counts(
            List<T> values, Function<T, E> classifier, Class<E> enumType) {
        Map<E, Long> counted = values.stream().map(classifier)
                .collect(Collectors.groupingBy(Function.identity(),
                        () -> new EnumMap<>(enumType), Collectors.counting()));
        return Map.copyOf(counted);
    }
}
