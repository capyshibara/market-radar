package com.marketradar.repo;

import com.marketradar.domain.TargetedRefetchAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TargetedRefetchAttemptRepository
        extends JpaRepository<TargetedRefetchAttempt, Long> {
}
