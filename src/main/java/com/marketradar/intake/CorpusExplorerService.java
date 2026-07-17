package com.marketradar.intake;

import com.marketradar.domain.ClaimVerification;
import com.marketradar.domain.Classification;
import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.InterpretedClaim;
import com.marketradar.domain.RawDoc;
import com.marketradar.repo.ClaimVerificationRepository;
import com.marketradar.repo.ClassificationRepository;
import com.marketradar.repo.EvidenceFactRepository;
import com.marketradar.repo.InterpretedClaimRepository;
import com.marketradar.repo.RawDocRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only admin projection of the evidence corpus and its real processing state. */
@Service
public class CorpusExplorerService {
    private final RawDocRepository rawDocs;
    private final ClassificationRepository classifications;
    private final EvidenceFactRepository facts;
    private final InterpretedClaimRepository claims;
    private final ClaimVerificationRepository verifications;

    public CorpusExplorerService(RawDocRepository rawDocs,
                                 ClassificationRepository classifications,
                                 EvidenceFactRepository facts,
                                 InterpretedClaimRepository claims,
                                 ClaimVerificationRepository verifications) {
        this.rawDocs = rawDocs;
        this.classifications = classifications;
        this.facts = facts;
        this.claims = claims;
        this.verifications = verifications;
    }

    @Transactional(readOnly = true)
    public Snapshot snapshot() {
        List<RawDoc> documents = rawDocs.findAllWithSource();
        Map<Long, Classification> classificationByDoc = new HashMap<>();
        for (Classification item : classifications.findAllForDisplay()) {
            classificationByDoc.put(item.getRawDoc().getId(), item);
        }
        Map<Long, List<EvidenceFact>> factsByDoc = new HashMap<>();
        for (EvidenceFact fact : facts.findAllActiveOrderById()) {
            factsByDoc.computeIfAbsent(fact.getRawDoc().getId(), ignored -> new ArrayList<>()).add(fact);
        }
        Map<Long, List<InterpretedClaim>> claimsByDoc = new HashMap<>();
        List<InterpretedClaim> activeClaims = claims.findAllForAudit().stream()
                .filter(claim -> !claim.isSuperseded() && claim.getRawDoc() != null).toList();
        for (InterpretedClaim claim : activeClaims) {
            claimsByDoc.computeIfAbsent(claim.getRawDoc().getId(), ignored -> new ArrayList<>()).add(claim);
        }
        Map<Long, ClaimVerification> latestVerificationByClaim = new HashMap<>();
        for (ClaimVerification verification : verifications.findAll()) {
            Long claimId = verification.getClaim().getId();
            ClaimVerification current = latestVerificationByClaim.get(claimId);
            if (current == null || verification.getCreatedAt().isAfter(current.getCreatedAt())
                    || verification.getCreatedAt().equals(current.getCreatedAt())
                    && verification.getId() > current.getId()) {
                latestVerificationByClaim.put(claimId, verification);
            }
        }

        List<DocumentRow> rows = new ArrayList<>();
        int fullText = 0;
        int manual = 0;
        int verifiedClaims = 0;
        for (RawDoc doc : documents) {
            if (doc.isFullTextFetched()) fullText++;
            if (doc.getIntakeMethod() != RawDoc.IntakeMethod.CRAWLED) manual++;
            Classification classification = classificationByDoc.get(doc.getId());
            List<EvidenceFact> documentFacts = factsByDoc.getOrDefault(doc.getId(), List.of());
            List<InterpretedClaim> documentClaims = claimsByDoc.getOrDefault(doc.getId(), List.of());
            int entailed = (int) documentClaims.stream()
                    .map(claim -> latestVerificationByClaim.get(claim.getId()))
                    .filter(v -> v != null && v.getVerdict() == ClaimVerification.Verdict.ENTAILED).count();
            verifiedClaims += entailed;
            String state = state(doc, classification, documentFacts.size(), documentClaims.size(), entailed);
            rows.add(new DocumentRow(doc.getId(), safeTitle(doc), doc.getPublisherName() == null
                    ? doc.getSource().getName() : doc.getPublisherName(), doc.getSource().getCode(),
                    doc.getUrl(), doc.getOriginalFilename(), doc.getLanguage(), doc.getFetchedAt(),
                    doc.getPublishedAt(), doc.getIntakeMethod().name(), doc.getParseStatus().name(),
                    doc.isFullTextFetched(), doc.getRawText() == null ? 0 : doc.getRawText().length(),
                    doc.getDuplicateOfId(), classification == null ? null : classification.getStatus().name(),
                    classification == null ? "" : classification.getLabels().toString(),
                    classification == null ? "" : classification.getDepartments().toString(),
                    documentFacts.size(), documentClaims.size(), entailed, state));
        }
        rows.sort(Comparator.comparing(DocumentRow::fetchedAt,
                Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(DocumentRow::id, Comparator.reverseOrder()));
        Summary summary = new Summary(rows.size(), fullText, manual, factsByDoc.values().stream()
                .mapToInt(List::size).sum(), activeClaims.size(), verifiedClaims);
        return new Snapshot(summary, List.copyOf(rows));
    }

    @Transactional(readOnly = true)
    public DocumentDetail detail(long id) {
        Snapshot snapshot = snapshot();
        DocumentRow row = snapshot.documents().stream().filter(item -> item.id() == id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Document not found: #" + id));
        RawDoc doc = rawDocs.findByIdWithSource(id)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: #" + id));
        List<FactView> factViews = facts.findAllActiveOrderById().stream()
                .filter(fact -> fact.getRawDoc().getId() == id)
                .map(fact -> new FactView(fact.getFactCode(), fact.getFactType().name(),
                        fact.getEventDate(), fact.getSpanText(), fact.getSummaryVi(), fact.getSummaryEn()))
                .toList();
        List<ClaimView> claimViews = claims.findAllForAudit().stream()
                .filter(claim -> !claim.isSuperseded() && claim.getRawDoc() != null
                        && claim.getRawDoc().getId() == id)
                .map(claim -> new ClaimView(claim.getClaimCode(), claim.getSlot().name(),
                        claim.getGateStatus().name(), claim.getReviewStatus().name(),
                        claim.getTextVi(), claim.getTextEn(), claim.getFactCodesCsv(),
                        verifications.findFirstByClaimOrderByCreatedAtDescIdDesc(claim)
                                .map(v -> v.getVerdict().name()).orElse("NOT_RUN")))
                .toList();
        String text = doc.getRawText() == null ? "" : doc.getRawText();
        return new DocumentDetail(row, text, factViews, claimViews, doc.getNote());
    }

    public List<DocumentRow> recentManual(int limit) {
        return snapshot().documents().stream()
                .filter(row -> !RawDoc.IntakeMethod.CRAWLED.name().equals(row.intakeMethod()))
                .limit(Math.max(1, Math.min(limit, 25))).toList();
    }

    private static String state(RawDoc doc, Classification classification, int factCount,
                                int claimCount, int entailedCount) {
        if (doc.getParseStatus() != RawDoc.ParseStatus.OK) return "IMPORT_FAILED";
        if (doc.getDuplicateOfId() != null) return "DUPLICATE";
        if (classification == null) return "WAITING_FOR_CLASSIFICATION";
        if (classification.getStatus() == Classification.Status.OUT_OF_SCOPE) return "OUT_OF_SCOPE";
        if (classification.getStatus() != Classification.Status.CONFIRMED) return "HELD_FOR_REVIEW";
        if (factCount == 0) return "WAITING_FOR_EXTRACTION";
        if (claimCount == 0) return "EVIDENCE_EXTRACTED";
        if (entailedCount == 0) return "WAITING_FOR_VERIFICATION";
        if (entailedCount < claimCount) return "PARTIALLY_VERIFIED";
        return "REPORT_ELIGIBLE";
    }

    private static String safeTitle(RawDoc doc) {
        if (doc.getTitle() != null && !doc.getTitle().isBlank()) return doc.getTitle();
        if (doc.getOriginalFilename() != null && !doc.getOriginalFilename().isBlank()) return doc.getOriginalFilename();
        return "Document #" + doc.getId();
    }

    public record Summary(int documents, int fullTextDocuments, int manualDocuments,
                          int activeFacts, int activeClaims, int verifiedClaims) {}
    public record Snapshot(Summary summary, List<DocumentRow> documents) {}
    public record DocumentRow(long id, String title, String publisher, String sourceCode,
                              String url, String filename, String language, Instant fetchedAt,
                              Instant publishedAt, String intakeMethod, String parseStatus,
                              boolean fullText, int textCharacters, Long duplicateOfId,
                              String classificationStatus, String labels, String departments,
                              int factCount, int claimCount, int verifiedClaimCount, String state) {}
    public record FactView(String code, String type, java.time.LocalDate eventDate,
                           String evidence, String summaryVi, String summaryEn) {}
    public record ClaimView(String code, String slot, String gate, String review,
                            String textVi, String textEn, String factCodes, String verdict) {}
    public record DocumentDetail(DocumentRow document, String fullText, List<FactView> facts,
                                 List<ClaimView> claims, String auditNote) {}
}
