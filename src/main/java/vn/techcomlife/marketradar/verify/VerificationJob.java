package vn.techcomlife.marketradar.verify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.techcomlife.marketradar.domain.ClaimVerification;
import vn.techcomlife.marketradar.domain.EvidenceFact;
import vn.techcomlife.marketradar.domain.InterpretedClaim;
import vn.techcomlife.marketradar.domain.InterpretedClaim.ReviewStatus;
import vn.techcomlife.marketradar.repo.ClaimVerificationRepository;
import vn.techcomlife.marketradar.repo.EvidenceFactRepository;
import vn.techcomlife.marketradar.repo.InterpretedClaimRepository;
import vn.techcomlife.marketradar.review.ReviewRules;

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
    private final vn.techcomlife.marketradar.alert.HotAlertService alerts;

    public VerificationJob(InterpretedClaimRepository claims,
                           ClaimVerificationRepository verifications,
                           EvidenceFactRepository facts,
                           EntailmentVerifier verifier,
                           vn.techcomlife.marketradar.alert.HotAlertService alerts) {
        this.claims = claims;
        this.verifications = verifications;
        this.facts = facts;
        this.verifier = verifier;
        this.alerts = alerts;
    }

    @Transactional
    public String runOnce() {
        List<InterpretedClaim> pending =
                claims.findByReviewStatusFetched(ReviewStatus.PENDING_VERIFICATION);
        if (pending.isEmpty()) return "Không có claim nào chờ verify (PENDING_VERIFICATION).\n";

        Map<String, EvidenceFact> factByCode = facts.findAllForReport().stream()
                .collect(Collectors.toMap(EvidenceFact::getFactCode, Function.identity()));

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
        }
        sb.insert(0, "Verify xong " + pending.size() + " claim: "
                + auto + " AUTO_APPROVED, " + toReview + " → review.\n");
        return sb.toString();
    }

    private static List<EvidenceFact> resolve(String csv, Map<String, EvidenceFact> byCode) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::strip).map(byCode::get).filter(Objects::nonNull).toList();
    }
}
