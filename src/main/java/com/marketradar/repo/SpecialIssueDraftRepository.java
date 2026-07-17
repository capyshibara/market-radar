package com.marketradar.repo;

import com.marketradar.specialissue.SpecialIssueDraft;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpecialIssueDraftRepository extends JpaRepository<SpecialIssueDraft, Long> {
    Optional<SpecialIssueDraft> findBySlugAndLanguage(String slug, String language);
}
