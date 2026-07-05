package vn.techcomlife.marketradar.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.techcomlife.marketradar.domain.LabelLog;

import java.util.List;

public interface LabelLogRepository extends JpaRepository<LabelLog, Long> {
    List<LabelLog> findAllByOrderByCreatedAtDescIdDesc();
}
