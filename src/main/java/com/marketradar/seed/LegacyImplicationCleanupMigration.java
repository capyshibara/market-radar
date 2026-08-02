package com.marketradar.seed;

import com.marketradar.domain.InterpretedClaim;
import com.marketradar.repo.InterpretedClaimRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 2026-08-02 (feedback vận hành): Interpreter không còn sinh slot IMPLICATION (khuyến nghị
 * nội bộ "chúng ta nên...") — sau khi phát hiện prompt cũ vừa (a) không bắt buộc nêu tên công
 * ty gây ra động thái, khiến câu đọc rời rạc vô nghĩa trong Reviewer Queue, vừa (b) ép AI phải
 * bịa khuyến nghị cho MỌI fact dù không có hành động nội bộ nào hợp lý. Xem Interpreter.java.
 *
 * Migration này dọn các claim IMPLICATION SINH RA TRƯỚC thay đổi trên, còn nằm ở Reviewer
 * Queue (PENDING_VERIFICATION/PENDING_REVIEW) — đánh dấu superseded thay vì xoá (giữ audit
 * trail), để operator không phải duyệt tay hàng loạt claim theo logic đã bị coi là lỗi thiết
 * kế. Claim IMPLICATION đã duyệt/từ chối trước đó (APPROVED/REJECTED/...) KHÔNG bị đụng tới —
 * đã là quyết định của con người, giữ nguyên cho audit.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 23)
public class LegacyImplicationCleanupMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LegacyImplicationCleanupMigration.class);

    private static final List<InterpretedClaim.ReviewStatus> PENDING_STATUSES = List.of(
            InterpretedClaim.ReviewStatus.PENDING_VERIFICATION,
            InterpretedClaim.ReviewStatus.PENDING_REVIEW);

    private final InterpretedClaimRepository claims;

    public LegacyImplicationCleanupMigration(InterpretedClaimRepository claims) {
        this.claims = claims;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int superseded = claims.supersedeBySlotAndReviewStatusIn(
                InterpretedClaim.Slot.IMPLICATION, PENDING_STATUSES);
        if (superseded > 0) {
            log.warn("Đã supersede {} claim IMPLICATION cũ đang chờ duyệt (prompt sinh chúng đã "
                    + "bị coi là lỗi thiết kế — xem javadoc LegacyImplicationCleanupMigration). "
                    + "Không đụng claim IMPLICATION đã duyệt/từ chối trước đó.", superseded);
        }
    }
}
