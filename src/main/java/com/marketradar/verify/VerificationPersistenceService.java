package com.marketradar.verify;

import com.marketradar.domain.ClaimVerification;
import com.marketradar.domain.InterpretedClaim;
import com.marketradar.repo.ClaimVerificationRepository;
import com.marketradar.repo.InterpretedClaimRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Commits one verifier result atomically; no external API call runs inside this transaction. */
@Service
public class VerificationPersistenceService {
    private final ClaimVerificationRepository verifications;
    private final InterpretedClaimRepository claims;

    public VerificationPersistenceService(ClaimVerificationRepository verifications,
                                          InterpretedClaimRepository claims) {
        this.verifications = verifications;
        this.claims = claims;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persist(InterpretedClaim claim, ClaimVerification.Verdict verdict,
                        String rationale, String provider, String rawResponse,
                        InterpretedClaim.ReviewStatus reviewStatus) {
        verifications.save(new ClaimVerification(claim, verdict, rationale, provider, rawResponse));
        claim.setReviewStatus(reviewStatus);
        claims.save(claim);
    }
}
