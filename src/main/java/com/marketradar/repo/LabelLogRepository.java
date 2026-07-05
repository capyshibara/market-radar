package com.marketradar.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import com.marketradar.domain.LabelLog;

import java.util.List;

public interface LabelLogRepository extends JpaRepository<LabelLog, Long> {
    List<LabelLog> findAllByOrderByCreatedAtDescIdDesc();

    /** Batch 6 (report redesign): log gần nhất theo claimCode — dùng để hiện lý do
     * override khi claim ở trạng thái FORCE_APPROVED (không giới hạn action, log mới nhất thắng). */
    List<LabelLog> findByClaimCodeOrderByCreatedAtDescIdDesc(String claimCode);
}
