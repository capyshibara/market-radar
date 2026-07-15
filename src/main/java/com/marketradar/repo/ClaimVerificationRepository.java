package com.marketradar.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.marketradar.domain.ClaimVerification;
import com.marketradar.domain.InterpretedClaim;

import java.util.Optional;

public interface ClaimVerificationRepository extends JpaRepository<ClaimVerification, Long> {
    /** Verdict hiện hành = record mới nhất của claim. */
    Optional<ClaimVerification> findFirstByClaimOrderByCreatedAtDescIdDesc(InterpretedClaim claim);

    boolean existsByClaim(InterpretedClaim claim);

    /**
     * Batch 10: xoá verification của các claim NARRATIVE 1 chương TRƯỚC khi xoá chính
     * các claim đó (force-retry-narrative) — nếu không, FK claim_verifications→interpreted_claims
     * chặn việc xoá (claim narrative ĐÃ qua Gate L2 nên có verification, khác các nhánh
     * force-retry SCHEMA_REJECTED vốn chưa bao giờ được verify).
     */
    @Modifying
    @Query("delete from ClaimVerification v where v.claim.id in "
         + "(select c.id from InterpretedClaim c where c.slot = :slot and c.chapterCode = :chapterCode and c.origin = :origin)")
    void deleteByClaimSlotAndChapterCodeAndOrigin(@Param("slot") InterpretedClaim.Slot slot,
                                                  @Param("chapterCode") String chapterCode,
                                                  @Param("origin") InterpretedClaim.Origin origin);
}
