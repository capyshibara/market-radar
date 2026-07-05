package com.marketradar.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import com.marketradar.domain.DedupDecision;

import java.util.List;

public interface DedupDecisionRepository extends JpaRepository<DedupDecision, Long> {

    /** Cặp đã quyết rồi thì lần chạy sau bỏ qua (docAId < docBId — thứ tự chuẩn hoá). */
    boolean existsByDocAIdAndDocBId(Long docAId, Long docBId);

    List<DedupDecision> findAllByOrderByCreatedAtDescIdDesc();
}
