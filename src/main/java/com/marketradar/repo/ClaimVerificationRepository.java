package com.marketradar.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import com.marketradar.domain.ClaimVerification;
import com.marketradar.domain.InterpretedClaim;

import java.util.Optional;

public interface ClaimVerificationRepository extends JpaRepository<ClaimVerification, Long> {
    /** Verdict hiện hành = record mới nhất của claim. */
    Optional<ClaimVerification> findFirstByClaimOrderByCreatedAtDescIdDesc(InterpretedClaim claim);

    boolean existsByClaim(InterpretedClaim claim);
}
