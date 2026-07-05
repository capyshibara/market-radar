package com.marketradar.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.marketradar.domain.InterpretedClaim;
import com.marketradar.domain.RawDoc;

import java.util.List;

public interface InterpretedClaimRepository extends JpaRepository<InterpretedClaim, Long> {

    /** LEFT JOIN FETCH vì rawDoc nullable (EXEC_SUMMARY) — render ngoài transaction. */
    @Query("select c from InterpretedClaim c left join fetch c.rawDoc " +
           "order by c.createdAt desc, c.id desc")
    List<InterpretedClaim> findAllForAudit();

    @Query("select c from InterpretedClaim c left join fetch c.rawDoc " +
           "where c.gateStatus = :status order by c.id asc")
    List<InterpretedClaim> findByGateStatusFetched(@Param("status") InterpretedClaim.GateStatus status);

    boolean existsByRawDocAndOrigin(RawDoc rawDoc, InterpretedClaim.Origin origin);

    boolean existsBySlotAndOrigin(InterpretedClaim.Slot slot, InterpretedClaim.Origin origin);

    // ---- Batch 4 ----

    /**
     * Truy vấn theo reviewStatus qua THAM SỐ (không dùng enum literal trong JPQL —
     * cú pháp enum lồng nhau dễ vỡ giữa các phiên bản Hibernate).
     * Sắp xếp tier desc để hàng đợi review ưu tiên T3 trước T1 (so sánh chuỗi
     * "T0".."T4" trùng với thứ tự mong muốn).
     */
    @Query("select c from InterpretedClaim c left join fetch c.rawDoc " +
           "where c.reviewStatus = :status order by c.riskTier desc, c.id asc")
    List<InterpretedClaim> findByReviewStatusFetched(
            @Param("status") InterpretedClaim.ReviewStatus status);

    /** Claim được phép vào report (4 trạng thái *_APPROVED). */
    @Query("select c from InterpretedClaim c left join fetch c.rawDoc " +
           "where c.reviewStatus in :statuses order by c.id asc")
    List<InterpretedClaim> findPublishable(
            @Param("statuses") List<InterpretedClaim.ReviewStatus> statuses);

    @Query("select c from InterpretedClaim c left join fetch c.rawDoc where c.id = :id")
    java.util.Optional<InterpretedClaim> findByIdFetched(@Param("id") Long id);
}
