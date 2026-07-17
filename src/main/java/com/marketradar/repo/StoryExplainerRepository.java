package com.marketradar.repo;

import com.marketradar.domain.StoryExplainer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StoryExplainerRepository extends JpaRepository<StoryExplainer, Long> {
    Optional<StoryExplainer> findByFactCode(String factCode);
}
