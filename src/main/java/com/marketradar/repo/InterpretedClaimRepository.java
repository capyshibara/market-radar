package com.marketradar.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.marketradar.domain.InterpretedClaim;
import com.marketradar.domain.ClaimVerification;
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

    boolean existsByRawDocAndOriginAndInterpretationSignatureAndInterpretationInputHashAndSupersededFalse(
            RawDoc rawDoc, InterpretedClaim.Origin origin, String interpretationSignature,
            String interpretationInputHash);

    /** Batch 8: live queue-count badge on the ops sidebar (see OpsSidebarAdvice). */
    long countByReviewStatusAndSupersededFalse(InterpretedClaim.ReviewStatus status);

    default long countByReviewStatus(InterpretedClaim.ReviewStatus status) {
        return countByReviewStatusAndSupersededFalse(status);
    }

    // ---- Batch 4 ----

    /**
     * Truy vấn theo reviewStatus qua THAM SỐ (không dùng enum literal trong JPQL —
     * cú pháp enum lồng nhau dễ vỡ giữa các phiên bản Hibernate).
     * Sắp xếp tier desc để hàng đợi review ưu tiên T3 trước T1 (so sánh chuỗi
     * "T0".."T4" trùng với thứ tự mong muốn).
     */
    @Query("select c from InterpretedClaim c left join fetch c.rawDoc " +
           "where c.reviewStatus = :status and c.superseded = false " +
           "order by c.riskTier desc, c.id asc")
    List<InterpretedClaim> findByReviewStatusFetched(
            @Param("status") InterpretedClaim.ReviewStatus status);

    /**
     * Claim được phép vào report: L1 PASS + trạng thái *_APPROVED + verdict MỚI NHẤT
     * ENTAILED. Human/force approval không được biến NEUTRAL/CONTRADICTED hoặc claim
     * chưa verify thành evidence-backed content. Subquery dùng createdAt rồi id làm
     * tie-break, đúng cùng định nghĩa "latest" của ClaimVerificationRepository.
     */
    /** join fetch rawDoc.source (chained qua alias rd) — không chỉ rawDoc: BI report cần
     *  source.language/allowedHost để phân loại thị trường VN/quốc tế (BiFinding.scope),
     *  và adapt() chạy ngoài transaction (không OSIV) nên không fetch trước sẽ ném
     *  LazyInitializationException khi chạm rawDoc.getSource(). */
    @Query("select c from InterpretedClaim c left join fetch c.rawDoc rd left join fetch rd.source " +
           "where c.reviewStatus in :statuses and c.gateStatus = :gateStatus " +
           "and c.superseded = false " +
           "and exists (select v.id from ClaimVerification v where v.claim = c " +
           "  and v.verdict = :verdict " +
           "  and not exists (select newer.id from ClaimVerification newer " +
           "    where newer.claim = c and (newer.createdAt > v.createdAt " +
           "      or (newer.createdAt = v.createdAt and newer.id > v.id)))) " +
           "order by c.id asc")
    List<InterpretedClaim> findPublishableVerified(
            @Param("statuses") List<InterpretedClaim.ReviewStatus> statuses,
            @Param("gateStatus") InterpretedClaim.GateStatus gateStatus,
            @Param("verdict") ClaimVerification.Verdict verdict);

    /** Keeps legacy callers on one fail-closed path without duplicating enum lists in JPQL. */
    default List<InterpretedClaim> findPublishable(List<InterpretedClaim.ReviewStatus> statuses) {
        return findPublishableVerified(statuses, InterpretedClaim.GateStatus.PASS,
                ClaimVerification.Verdict.ENTAILED);
    }

    /**
     * Đường CHỌN LỌC TAY cho báo cáo BI: khác findPublishableVerified ở đúng một điểm —
     * KHÔNG đòi verdict ENTAILED mới nhất, chỉ CHẶN khi verdict mới nhất là CONTRADICTED.
     *
     * Lý do (nguyên tắc "máy lọc thô, người là cổng cuối" của luồng BI curated):
     * người duyệt ở /review đã đọc claim CẠNH bằng chứng rồi mới bấm Duyệt — với các
     * trạng thái duyệt tay (APPROVED/EDITED_APPROVED/FORCE_APPROVED), đòi thêm verifier
     * ENTAILED nghĩa là máy phủ quyết con người, và khi verifier chạy stub (mọi verdict
     * NEUTRAL) thì không claim nào ra được báo cáo dù đã duyệt — chính là lỗi "pipeline
     * quá khắt khe đến mức báo cáo trống". CONTRADICTED vẫn chặn cứng: bằng chứng mới
     * mâu thuẫn thì không trạng thái duyệt nào cứu được (fail-closed đúng chỗ).
     *
     * Vẫn giữ: L1 PASS (Invariant #1 — không claim nào thiếu citation), superseded=false,
     * origin lọc ở tham số (PIPELINE — DEMO_INJECT không bao giờ vào báo cáo thật).
     * Weekly/monthly report KHÔNG đổi — vẫn đi findPublishable (ENTAILED bắt buộc).
     */
    /** join fetch rawDoc.source — cùng lý do với findPublishableVerified ở trên. */
    @Query("select c from InterpretedClaim c left join fetch c.rawDoc rd left join fetch rd.source " +
           "where c.reviewStatus in :statuses and c.gateStatus = :gateStatus " +
           "and c.origin = :origin and c.superseded = false " +
           "and not exists (select v.id from ClaimVerification v where v.claim = c " +
           "  and v.verdict = :blockedVerdict " +
           "  and not exists (select newer.id from ClaimVerification newer " +
           "    where newer.claim = c and (newer.createdAt > v.createdAt " +
           "      or (newer.createdAt = v.createdAt and newer.id > v.id)))) " +
           "order by c.id asc")
    List<InterpretedClaim> findApprovedNotContradicted(
            @Param("statuses") List<InterpretedClaim.ReviewStatus> statuses,
            @Param("gateStatus") InterpretedClaim.GateStatus gateStatus,
            @Param("origin") InterpretedClaim.Origin origin,
            @Param("blockedVerdict") ClaimVerification.Verdict blockedVerdict);

    /** Claim đủ điều kiện vào báo cáo BI: duyệt tay (không cần ENTAILED, chặn
     *  CONTRADICTED) + AUTO_APPROVED (đường máy — vốn dĩ chỉ được set khi ENTAILED). */
    default List<InterpretedClaim> findForBiReport() {
        java.util.LinkedHashMap<Long, InterpretedClaim> byId = new java.util.LinkedHashMap<>();
        findApprovedNotContradicted(
                List.of(InterpretedClaim.ReviewStatus.APPROVED,
                        InterpretedClaim.ReviewStatus.EDITED_APPROVED,
                        InterpretedClaim.ReviewStatus.FORCE_APPROVED),
                InterpretedClaim.GateStatus.PASS, InterpretedClaim.Origin.PIPELINE,
                ClaimVerification.Verdict.CONTRADICTED)
                .forEach(c -> byId.put(c.getId(), c));
        findPublishable(List.of(InterpretedClaim.ReviewStatus.AUTO_APPROVED))
                .forEach(c -> byId.putIfAbsent(c.getId(), c));
        return List.copyOf(byId.values());
    }

    @Query("select c from InterpretedClaim c left join fetch c.rawDoc where c.id = :id")
    java.util.Optional<InterpretedClaim> findByIdFetched(@Param("id") Long id);

    /**
     * Fix 2026-07-13: nextCode() cũ dùng count()+1 — SAI khi có row bị xoá (vd dọn
     * claim SCHEMA_REJECTED cũ để chạy lại), vì count() giảm nhưng code lớn nhất đã
     * cấp phát trước đó không giảm theo → code mới trùng code cũ, vỡ unique constraint.
     * Lấy hết code hiện có, tính max ở tầng Java (đơn giản, đủ nhanh ở quy mô này,
     * tránh phụ thuộc cú pháp SUBSTRING/CAST khác nhau giữa các bản Hibernate/DB).
     */
    @Query("select c.claimCode from InterpretedClaim c")
    List<String> findAllClaimCodes();

    // ---- Append-only interpretation edition lifecycle ----

    /** Activate a newly persisted edition by superseding every prior active PIPELINE edition. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("update InterpretedClaim c set c.superseded = true " +
           "where c.rawDoc.id = :rawDocId and c.origin = :origin and c.superseded = false " +
           "and (c.interpretationEditionId is null or c.interpretationEditionId <> :keepEditionId)")
    int supersedePriorByRawDocIdAndOrigin(@Param("rawDocId") Long rawDocId,
                                          @Param("origin") InterpretedClaim.Origin origin,
                                          @Param("keepEditionId") String keepEditionId);

    @org.springframework.data.jpa.repository.Modifying
    @Query("update InterpretedClaim c set c.superseded = true " +
           "where c.slot = :slot and c.origin = :origin and c.superseded = false " +
           "and (c.interpretationEditionId is null or c.interpretationEditionId <> :keepEditionId)")
    int supersedePriorBySlotAndOrigin(@Param("slot") InterpretedClaim.Slot slot,
                                      @Param("origin") InterpretedClaim.Origin origin,
                                      @Param("keepEditionId") String keepEditionId);

    // ---- Batch 10 (chapter narrative) ----

    boolean existsBySlotAndChapterCodeAndOriginAndInterpretationSignatureAndInterpretationInputHashAndSupersededFalse(
            InterpretedClaim.Slot slot, String chapterCode, InterpretedClaim.Origin origin,
            String interpretationSignature, String interpretationInputHash);

    boolean existsBySlotAndOriginAndInterpretationSignatureAndInterpretationInputHashAndSupersededFalse(
            InterpretedClaim.Slot slot, InterpretedClaim.Origin origin,
            String interpretationSignature, String interpretationInputHash);

    @org.springframework.data.jpa.repository.Modifying
    @Query("update InterpretedClaim c set c.superseded = true " +
           "where c.slot = :slot and c.chapterCode = :chapterCode " +
           "and c.origin = :origin and c.superseded = false " +
           "and (c.interpretationEditionId is null or c.interpretationEditionId <> :keepEditionId)")
    int supersedePriorBySlotAndChapterCodeAndOrigin(
            @Param("slot") InterpretedClaim.Slot slot,
            @Param("chapterCode") String chapterCode,
            @Param("origin") InterpretedClaim.Origin origin,
            @Param("keepEditionId") String keepEditionId);

    /** No fresh inputs means an old chapter must disappear rather than masquerade as current. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("update InterpretedClaim c set c.superseded = true " +
           "where c.slot = :slot and c.chapterCode = :chapterCode " +
           "and c.origin = :origin and c.superseded = false")
    int supersedeStaleChapter(@Param("slot") InterpretedClaim.Slot slot,
                              @Param("chapterCode") String chapterCode,
                              @Param("origin") InterpretedClaim.Origin origin);
}
