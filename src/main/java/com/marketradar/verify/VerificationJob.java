package com.marketradar.verify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import com.marketradar.llm.ProviderSafetyRules;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Bước 6 pipeline (Gate L2): với mỗi claim PENDING_VERIFICATION
 * → entailment độc lập → quyết định route:
 *
 *   ENTAILED  + tier T0/T1  → AUTO_APPROVED (diện sample theo E1)
 *   ENTAILED  + tier >= T2  → PENDING_REVIEW (một người trở lên phải nhìn)
 *   CONTRADICTED / NEUTRAL / VERIFIER_ERROR → PENDING_REVIEW (fail loud —
 *     không bao giờ silent-default sang publish)
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

    public VerificationJob(InterpretedClaimRepository claims,
                           ClaimVerificationRepository verifications,
                           EvidenceFactRepository facts,
                           EntailmentVerifier verifier,
                           com.marketradar.alert.HotAlertService alerts,
                           PipelineRunStatusService progress, PipelineItemLogRepository itemLogs) {
        this.claims = claims;
        this.verifications = verifications;
        this.facts = facts;
        this.verifier = verifier;
        this.alerts = alerts;
        this.progress = progress;
        this.itemLogs = itemLogs;
    }

    @Transactional
    public String runOnce() {
        if (ProviderSafetyRules.isStub(verifier.providerName())) {
            return "Verification refused: verifier provider is STUB/missing. "
                    + "No verdict was appended; configure an independent verifier.\n";
        }
        List<InterpretedClaim> pending =
                claims.findByReviewStatusFetched(ReviewStatus.PENDING_VERIFICATION);
        if (pending.isEmpty()) return "No claims awaiting verification (PENDING_VERIFICATION).\n";

        Map<String, EvidenceFact> factByCode = facts.findAllForReport().stream()
                .collect(Collectors.toMap(EvidenceFact::getFactCode, Function.identity()));

        progress.startProgress("verify", pending.size());
        Long runLogId = progress.currentRunLogId("verify");
        StringBuilder sb = new StringBuilder("Verifier: " + verifier.providerName() + "\n");
        int auto = 0, toReview = 0;
        for (InterpretedClaim c : pending) {
            List<EvidenceFact> cited = resolve(c.getFactCodesCsv(), factByCode);
            EntailmentVerifier.VerifyResult r = verifier.verify(c.getTextVi(), cited);
            verifications.save(new ClaimVerification(
                    c, r.verdict(), r.rationale(), verifier.providerName(), r.rawResponse()));

            boolean autoOk = ReviewRules.autoPublishable(r.verdict().name(), c.getRiskTier());
            c.setReviewStatus(autoOk ? ReviewStatus.AUTO_APPROVED : ReviewStatus.PENDING_REVIEW);
            claims.save(c);
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
                + auto + " AUTO_APPROVED, " + toReview + " → review.\n");
        return sb.toString();
    }

    private static List<EvidenceFact> resolve(String csv, Map<String, EvidenceFact> byCode) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::strip).map(byCode::get).filter(Objects::nonNull).toList();
    }
}
