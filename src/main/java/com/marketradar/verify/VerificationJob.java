package com.marketradar.verify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.marketradar.domain.ClaimVerification;
import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.InterpretedClaim;
import com.marketradar.domain.InterpretedClaim.ReviewStatus;
import com.marketradar.domain.PipelineItemLog;
import com.marketradar.pipeline.PipelineRunStatusService;
import com.marketradar.repo.ClaimVerificationRepository;
import com.marketradar.repo.EvidenceFactRepository;
import com.marketradar.repo.InterpretedClaimRepository;
import com.marketradar.repo.PipelineItemLogRepository;
import com.marketradar.review.ReviewRules;
import com.marketradar.review.EntityAttributionGuard;
import com.marketradar.llm.ProviderSafetyRules;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Bước 6 pipeline (Gate L2): với mỗi claim PENDING_VERIFICATION
 * → entailment độc lập → quyết định route:
 *
 *   ENTAILED                                → AUTO_APPROVED
 *   CONTRADICTED / NEUTRAL / VERIFIER_ERROR → PENDING_REVIEW (fail loud —
 *     không bao giờ silent-default sang publish)
 *
 * Risk tier (RiskTierRouter) không còn tham gia quyết định route — claim đã qua Gate L1
 * (exact-match) rồi Gate L2 (verifier độc lập khác họ model) là đủ 2 lớp kiểm; tier vẫn được
 * lưu trên claim để hiển thị/sắp ưu tiên trong Reviewer Queue (xem ReviewRules.autoPublishable).
 *
 * Mọi verdict được LƯU append-only vào claim_verifications (audit).
 */
@Service
public class VerificationJob {

    private static final Logger log = LoggerFactory.getLogger(VerificationJob.class);

    private final InterpretedClaimRepository claims;
    private final ClaimVerificationRepository verifications;
    private final EvidenceFactRepository facts;
    private final EntailmentVerifier verifier;
    private final com.marketradar.alert.HotAlertService alerts;
    private final PipelineRunStatusService progress;
    private final PipelineItemLogRepository itemLogs;
    private final EntityAttributionGuard entityGuard;
    private final VerificationPersistenceService persistence;

    public VerificationJob(InterpretedClaimRepository claims,
                           ClaimVerificationRepository verifications,
                           EvidenceFactRepository facts,
                           EntailmentVerifier verifier,
                           com.marketradar.alert.HotAlertService alerts,
                           PipelineRunStatusService progress, PipelineItemLogRepository itemLogs,
                           EntityAttributionGuard entityGuard,
                           VerificationPersistenceService persistence) {
        this.claims = claims;
        this.verifications = verifications;
        this.facts = facts;
        this.verifier = verifier;
        this.alerts = alerts;
        this.progress = progress;
        this.itemLogs = itemLogs;
        this.entityGuard = entityGuard;
        this.persistence = persistence;
    }

    public String runOnce() {
        if (ProviderSafetyRules.isStub(verifier.providerName())) {
            return "Verification refused: verifier provider is STUB/missing. "
                    + "No verdict was appended; configure an independent verifier.\n";
        }
        List<InterpretedClaim> pending =
                claims.findByReviewStatusFetched(ReviewStatus.PENDING_VERIFICATION);
        if (pending.isEmpty()) return "No claims awaiting verification (PENDING_VERIFICATION).\n";

        // Claims are immutable audit editions. A later extraction can supersede an
        // active fact, but must not make an older claim unverifiable. Resolve every
        // cited code through the audit query, including inactive evidence editions.
        List<String> citedCodes = pending.stream()
                .flatMap(c -> splitCodes(c.getFactCodesCsv()).stream())
                .distinct().toList();
        Map<String, EvidenceFact> factByCode = (citedCodes.isEmpty()
                ? List.<EvidenceFact>of() : facts.findAllByFactCodeInForAudit(citedCodes)).stream()
                .collect(Collectors.toMap(EvidenceFact::getFactCode, f -> f, (first, ignored) -> first));

        progress.startProgress("verify", pending.size());
        Long runLogId = progress.currentRunLogId("verify");
        StringBuilder sb = new StringBuilder("Verifier: " + verifier.providerName() + "\n");
        int auto = 0, toReview = 0, technicalErrors = 0;
        for (InterpretedClaim c : pending) {
            List<EvidenceFact> cited = resolve(c.getFactCodesCsv(), factByCode);
            EntailmentVerifier.VerifyResult r;
            try {
                if (cited.isEmpty()) {
                    r = new EntailmentVerifier.VerifyResult(ClaimVerification.Verdict.VERIFIER_ERROR,
                            "No cited evidence could be resolved from the immutable audit corpus.", "");
                } else {
                    EntailmentVerifier.VerifyResult verified = verifier.verifyBilingual(
                            c.getTextVi(), c.getTextEn(), cited);
                    List<EntityAttributionGuard.Warning> attributionWarnings = entityGuard.check(c, cited);
                    boolean unsafeAttribution = entityGuard.blocksAutoApproval(attributionWarnings);
                    r = unsafeAttribution && verified.verdict() == ClaimVerification.Verdict.ENTAILED
                            ? new EntailmentVerifier.VerifyResult(ClaimVerification.Verdict.NEUTRAL,
                                verified.rationale() + " Entity attribution requires human review: "
                                        + attributionWarnings.stream().map(w -> w.code().name()).distinct().toList(),
                                verified.rawResponse())
                            : verified;
                }
            } catch (RuntimeException e) {
                technicalErrors++;
                log.error("Verifier failed for {}; preserving the rest of the batch", c.getClaimCode(), e);
                r = new EntailmentVerifier.VerifyResult(ClaimVerification.Verdict.VERIFIER_ERROR,
                        "Verifier technical error: " + safeMessage(e), "");
            }
            boolean autoOk = ReviewRules.autoPublishable(r.verdict().name());
            ReviewStatus nextStatus = autoOk ? ReviewStatus.AUTO_APPROVED : ReviewStatus.PENDING_REVIEW;
            try {
                persistence.persist(c, r.verdict(), r.rationale(), verifier.providerName(),
                        r.rawResponse(), nextStatus);
            } catch (RuntimeException e) {
                technicalErrors++;
                log.error("Could not persist verification for {}; continuing batch", c.getClaimCode(), e);
                sb.append(c.getClaimCode()).append(" → PERSISTENCE_ERROR — ")
                        .append(safeMessage(e)).append('\n');
                progress.stepProgress("verify");
                continue;
            }
            if (autoOk) auto++; else toReview++;

            // Batch 5: Hot Alert — chỉ bắn khi đã *_APPROVED (service tự check tier;
            // với rule tier hiện tại T3+ luôn cần người nên đường auto này thực tế
            // chưa bắn — hook sẵn để rule tier tương lai không phải sửa chỗ gọi)
            if (autoOk) alerts.maybeAlert(c, cited, "AUTO_APPROVED");

            sb.append(c.getClaimCode()).append(" [").append(c.getRiskTier()).append("] → ")
              .append(r.verdict()).append(" → ").append(c.getReviewStatus()).append('\n');
            log.info("Gate L2 {} [{}] → {} → {}", c.getClaimCode(), c.getRiskTier(),
                    r.verdict(), c.getReviewStatus());
            if (runLogId != null) {
                Long rawDocId = c.getRawDoc() == null ? null : c.getRawDoc().getId();
                itemLogs.save(new PipelineItemLog(runLogId, PipelineItemLog.ItemType.CLAIM,
                        c.getClaimCode(), c.getClaimCode(), rawDocId,
                        r.verdict().name(), c.getReviewStatus().name()));
            }
            progress.stepProgress("verify");
        }
        sb.insert(0, "Verified " + pending.size() + " claim(s): "
                + auto + " AUTO_APPROVED, " + toReview + " → review, "
                + technicalErrors + " technical error(s).\n");
        return sb.toString();
    }

    private static List<EvidenceFact> resolve(String csv, Map<String, EvidenceFact> byCode) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::strip).map(byCode::get).filter(Objects::nonNull).toList();
    }

    private static List<String> splitCodes(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::strip)
                .filter(code -> !code.isBlank()).toList();
    }

    private static String safeMessage(Throwable error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) {
            return error == null ? "unknown error" : error.getClass().getSimpleName();
        }
        String message = error.getMessage().strip();
        return message.length() <= 400 ? message : message.substring(0, 400) + "…";
    }
}
