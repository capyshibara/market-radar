package com.marketradar.pipeline;

import com.marketradar.classify.ClassificationInputPolicy;
import com.marketradar.domain.ClaimVerification;
import com.marketradar.domain.Classification;
import com.marketradar.domain.FactExtractionRun;
import com.marketradar.domain.InterpretedClaim;
import com.marketradar.domain.RawDoc;
import com.marketradar.review.PublicationEligibilityRules;
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

    public record Corpus(long documents, long usableDocuments, long analysisEligibleDocuments,
                         long shallowDocuments, long datedDocuments, long duplicateDocuments) {}
    public record Curation(long classifications, long analysisEligibleClassifications,
                           long confirmed, long confirmedAnalysisEligible,
                           long outOfScope,
                           long uncertainReview, long noLabelReview) {}
    public record Evidence(long activeFacts, long activeFactDocuments,
                           long latestAttempts, long successfulDocuments,
                           long emptyResults, long llmErrors, long schemaRejected,
                           long coreDimensionCompleteFacts, long entityQuarantinedFacts) {}
    public record Analysis(long activePipelineClaims, long activeClaimDocuments, long gateL1Passed,
                           Map<InterpretedClaim.GateStatus, Long> gateStatuses,
                           Map<InterpretedClaim.ReviewStatus, Long> reviewStatuses) {}
    public record Verification(long latestVerdicts, long entailed, long neutral,
                               long contradicted, long verifierErrors,
                               long reportEligibleClaims, long reviewedAnalysisClaims,
                               long editorialWatchClaims,
                               long excludedClaims) {}
    public record Snapshot(Instant generatedAt, Corpus corpus, Curation curation,
                           Evidence evidence, Analysis analysis, Verification verification,
                           List<PipelineCheckpointRules.Checkpoint> checkpoints) {}

    private final RawDocRepository docs;
    private final ClassificationRepository classifications;
    private final EvidenceFactRepository facts;
    private final FactExtractionRunRepository extractionRuns;
    private final InterpretedClaimRepository claims;
    private final ClaimVerificationRepository verifications;
    private final int analysisMaxAgeDays;
    private final boolean requirePublicationDate;

    public PipelineCheckpointService(RawDocRepository docs,
                                     ClassificationRepository classifications,
                                     EvidenceFactRepository facts,
                                     FactExtractionRunRepository extractionRuns,
                                     InterpretedClaimRepository claims,
                                     ClaimVerificationRepository verifications,
                                     @org.springframework.beans.factory.annotation.Value(
                                             "${marketradar.curation.max-age-days:400}")
                                     int analysisMaxAgeDays,
                                     @org.springframework.beans.factory.annotation.Value(
                                             "${marketradar.curation.require-publication-date:true}")
                                     boolean requirePublicationDate) {
        this.docs = docs;
        this.classifications = classifications;
        this.facts = facts;
        this.extractionRuns = extractionRuns;
        this.claims = claims;
        this.verifications = verifications;
        this.analysisMaxAgeDays = analysisMaxAgeDays;
        this.requirePublicationDate = requirePublicationDate;
    }

    @Transactional(readOnly = true)
    public Snapshot inspect() {
        List<RawDoc> allDocs = docs.findAllWithSource();
        Instant scopeTime = Instant.now();
        long usable = allDocs.stream().filter(d -> d.getParseStatus() == RawDoc.ParseStatus.OK)
                .filter(d -> d.getDuplicateOfId() == null)
                .filter(d -> ClassificationInputPolicy.assess(d).eligible()).count();
        long analysisEligible = allDocs.stream()
                .filter(d -> d.getParseStatus() == RawDoc.ParseStatus.OK)
                .filter(d -> d.getDuplicateOfId() == null)
                .filter(d -> d.getSource().getUsePolicy().allowsAnalysis())
                .filter(d -> currentInput(d, scopeTime).eligible()).count();
        java.util.Set<Long> analysisEligibleDocIds = allDocs.stream()
                .filter(d -> d.getParseStatus() == RawDoc.ParseStatus.OK)
                .filter(d -> d.getDuplicateOfId() == null)
                .filter(d -> d.getSource().getUsePolicy().allowsAnalysis())
                .filter(d -> currentInput(d, scopeTime).eligible())
                .map(RawDoc::getId).collect(Collectors.toSet());
        long dated = allDocs.stream().filter(d -> d.getPublishedAt() != null).count();
        long duplicates = allDocs.stream().filter(d -> d.getDuplicateOfId() != null).count();
        Corpus corpus = new Corpus(allDocs.size(), usable, analysisEligible,
                allDocs.size() - usable,
                dated, duplicates);

        List<Classification> allClassifications = classifications.findAllForDisplay();
        List<Classification> analysisClassifications = allClassifications.stream()
                .filter(c -> c.getRawDoc().getDuplicateOfId() == null)
                .filter(c -> c.getRawDoc().getSource().getUsePolicy().allowsAnalysis())
                .filter(c -> analysisEligibleDocIds.contains(c.getRawDoc().getId()))
                .toList();
        Map<Classification.Status, Long> classificationStatuses = counts(
                analysisClassifications, Classification::getStatus, Classification.Status.class);
        java.util.Set<Long> confirmedAnalysisEligibleIds = allClassifications.stream()
                .filter(c -> c.getStatus() == Classification.Status.CONFIRMED)
                .map(Classification::getRawDoc)
                .filter(d -> d.getDuplicateOfId() == null)
                .filter(d -> d.getSource().getUsePolicy().allowsAnalysis())
                .filter(d -> analysisEligibleDocIds.contains(d.getId()))
                .map(RawDoc::getId).collect(Collectors.toSet());
        Curation curation = new Curation(allClassifications.size(), analysisClassifications.size(),
                classificationStatuses.getOrDefault(Classification.Status.CONFIRMED, 0L),
                confirmedAnalysisEligibleIds.size(),
                classificationStatuses.getOrDefault(Classification.Status.OUT_OF_SCOPE, 0L),
                classificationStatuses.getOrDefault(Classification.Status.UNCERTAIN_REVIEW, 0L),
                classificationStatuses.getOrDefault(Classification.Status.NO_LABEL_REVIEW, 0L));

        List<FactExtractionRun> latestExtractionRuns = latestExtractionRuns(
                extractionRuns.findAll()).stream()
                .filter(run -> confirmedAnalysisEligibleIds.contains(run.getRawDoc().getId()))
                .toList();
        Map<FactExtractionRun.Status, Long> extractionStatuses = counts(
                latestExtractionRuns, FactExtractionRun::getStatus, FactExtractionRun.Status.class);
        var activeFacts = facts.findAllForReport().stream()
                .filter(f -> analysisEligibleDocIds.contains(f.getRawDoc().getId()))
                .toList();
        long factDocuments = activeFacts.stream().map(f -> f.getRawDoc().getId()).distinct().count();
        long coreComplete = activeFacts.stream().filter(f -> f.getSourceAuthority() != null)
                .filter(f -> f.getIntelligenceTopic() != null)
                .filter(f -> f.getGeographyScope() != null)
                .filter(f -> f.getTemporalRole() != null).count();
        long entityQuarantined = activeFacts.stream().filter(f ->
                !com.marketradar.review.EntityAttributionGuard.isFactAttributionSafe(f)).count();
        Evidence evidence = new Evidence(activeFacts.size(), factDocuments,
                latestExtractionRuns.size(),
                extractionStatuses.getOrDefault(FactExtractionRun.Status.SUCCESS, 0L),
                extractionStatuses.getOrDefault(FactExtractionRun.Status.EMPTY_RESULT, 0L),
                extractionStatuses.getOrDefault(FactExtractionRun.Status.LLM_ERROR, 0L),
                extractionStatuses.getOrDefault(FactExtractionRun.Status.SCHEMA_REJECTED, 0L),
                coreComplete, entityQuarantined);

        List<InterpretedClaim> activeClaims = claims.findAllForAudit().stream()
                .filter(c -> !c.isSuperseded())
                .filter(c -> c.getOrigin() == InterpretedClaim.Origin.PIPELINE)
                .filter(c -> c.getRawDoc() == null
                        || analysisEligibleDocIds.contains(c.getRawDoc().getId()))
                .toList();
        Map<InterpretedClaim.GateStatus, Long> gateStatuses = counts(
                activeClaims, InterpretedClaim::getGateStatus, InterpretedClaim.GateStatus.class);
        Map<InterpretedClaim.ReviewStatus, Long> reviewStatuses = counts(
                activeClaims, InterpretedClaim::getReviewStatus, InterpretedClaim.ReviewStatus.class);
        long l1Passed = gateStatuses.getOrDefault(InterpretedClaim.GateStatus.PASS, 0L);
        long activeClaimDocuments = activeClaims.stream()
                .filter(c -> c.getRawDoc() != null)
                .map(c -> c.getRawDoc().getId()).distinct().count();
        Analysis analysis = new Analysis(activeClaims.size(), activeClaimDocuments, l1Passed,
                gateStatuses, reviewStatuses);

        Map<Long, ClaimVerification> latestByClaim = new LinkedHashMap<>();
        java.util.Set<Long> activeClaimIds = activeClaims.stream().map(InterpretedClaim::getId)
                .collect(Collectors.toSet());
        verifications.findAll().stream()
                .filter(v -> v.getClaim() != null && v.getClaim().getId() != null)
                .filter(v -> activeClaimIds.contains(v.getClaim().getId()))
                .filter(v -> !v.getClaim().isSuperseded())
                .filter(v -> v.getClaim().getOrigin() == InterpretedClaim.Origin.PIPELINE)
                .sorted(java.util.Comparator.comparing(ClaimVerification::getCreatedAt)
                        .thenComparing(ClaimVerification::getId))
                .forEach(v -> latestByClaim.put(v.getClaim().getId(), v));
        Map<ClaimVerification.Verdict, Long> verdicts = counts(
                List.copyOf(latestByClaim.values()), ClaimVerification::getVerdict,
                ClaimVerification.Verdict.class);
        Map<String, com.marketradar.domain.EvidenceFact> factByCode = activeFacts.stream()
                .collect(Collectors.toMap(com.marketradar.domain.EvidenceFact::getFactCode,
                        Function.identity(), (left, right) -> left));
        long decisionGrade = 0;
        long reviewedAnalysis = 0;
        long editorialWatch = 0;
        long excluded = 0;
        for (InterpretedClaim claim : activeClaims) {
            ClaimVerification latest = latestByClaim.get(claim.getId());
            String verdict = latest == null ? null : latest.getVerdict().name();
            List<com.marketradar.domain.EvidenceFact> cited = citedFacts(claim, factByCode);
            boolean entitySafe = !cited.isEmpty()
                    && cited.stream().allMatch(com.marketradar.review.EntityAttributionGuard::isFactAttributionSafe);
            PublicationEligibilityRules.Disposition disposition = PublicationEligibilityRules.disposition(
                    claim.getGateStatus().name(), claim.getReviewStatus().name(), verdict,
                    claim.isSuperseded(), entitySafe, isAnalyticalContent(claim.getSlot()));
            if (disposition == PublicationEligibilityRules.Disposition.DECISION_GRADE
                    && cited.stream().anyMatch(f -> !f.getRawDoc().getSource().getUsePolicy()
                    .allowsDecisionPublication())) {
                disposition = PublicationEligibilityRules.Disposition.EDITORIAL_WATCH;
            }
            switch (disposition) {
                case DECISION_GRADE -> decisionGrade++;
                case REVIEWED_ANALYSIS -> reviewedAnalysis++;
                case EDITORIAL_WATCH -> editorialWatch++;
                case EXCLUDE -> excluded++;
            }
        }
        Verification verification = new Verification(latestByClaim.size(),
                verdicts.getOrDefault(ClaimVerification.Verdict.ENTAILED, 0L),
                verdicts.getOrDefault(ClaimVerification.Verdict.NEUTRAL, 0L),
                verdicts.getOrDefault(ClaimVerification.Verdict.CONTRADICTED, 0L),
                verdicts.getOrDefault(ClaimVerification.Verdict.VERIFIER_ERROR, 0L),
                decisionGrade, reviewedAnalysis, editorialWatch, excluded);

        PipelineCheckpointRules.Metrics metrics = new PipelineCheckpointRules.Metrics(
                corpus.documents(), corpus.analysisEligibleDocuments(), curation.analysisEligibleClassifications(),
                curation.confirmedAnalysisEligible(), evidence.latestAttempts(), evidence.successfulDocuments(),
                evidence.llmErrors() + evidence.schemaRejected(), evidence.activeFacts(),
                evidence.activeFactDocuments(), evidence.coreDimensionCompleteFacts(),
                evidence.entityQuarantinedFacts(), analysis.activePipelineClaims(),
                analysis.activeClaimDocuments(),
                analysis.gateL1Passed(), verification.latestVerdicts(), verification.entailed(),
                verification.neutral(), verification.contradicted(), verification.verifierErrors(),
                verification.reportEligibleClaims(), verification.reviewedAnalysisClaims(),
                verification.editorialWatchClaims());
        return new Snapshot(Instant.now(), corpus, curation, evidence, analysis, verification,
                PipelineCheckpointRules.evaluate(metrics));
    }

    private ClassificationInputPolicy.Assessment currentInput(RawDoc doc, Instant now) {
        return ClassificationInputPolicy.assessForCurrentAnalysis(
                doc, now, analysisMaxAgeDays, requirePublicationDate);
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

    private static List<com.marketradar.domain.EvidenceFact> citedFacts(
            InterpretedClaim claim,
            Map<String, com.marketradar.domain.EvidenceFact> factByCode) {
        if (claim.getFactCodesCsv() == null || claim.getFactCodesCsv().isBlank()) return List.of();
        return java.util.Arrays.stream(claim.getFactCodesCsv().split(","))
                .map(String::strip).map(factByCode::get).filter(java.util.Objects::nonNull).toList();
    }

    private static boolean isAnalyticalContent(InterpretedClaim.Slot slot) {
        return slot == InterpretedClaim.Slot.IMPLICATION
                || slot == InterpretedClaim.Slot.NARRATIVE
                || slot == InterpretedClaim.Slot.DEEP_DIVE;
    }
}
