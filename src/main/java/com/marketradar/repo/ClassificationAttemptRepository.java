package com.marketradar.repo;

import com.marketradar.domain.ClassificationAttempt;
import com.marketradar.domain.RawDoc;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ClassificationAttemptRepository extends JpaRepository<ClassificationAttempt, Long> {

    Optional<ClassificationAttempt> findFirstByRawDocAndVersionSignatureOrderByCreatedAtDescIdDesc(
            RawDoc rawDoc, String versionSignature);
}
