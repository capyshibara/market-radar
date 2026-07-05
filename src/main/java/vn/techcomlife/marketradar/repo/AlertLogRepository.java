package vn.techcomlife.marketradar.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.techcomlife.marketradar.domain.AlertLog;

import java.util.List;

public interface AlertLogRepository extends JpaRepository<AlertLog, Long> {

    /** Idempotency: mỗi claim chỉ alert THÀNH CÔNG một lần. */
    boolean existsByClaimCodeAndStatus(String claimCode, AlertLog.Status status);

    List<AlertLog> findAllByOrderByCreatedAtDescIdDesc();
}
