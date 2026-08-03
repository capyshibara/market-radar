package com.marketradar.research;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WebSearchRunRepository extends JpaRepository<WebSearchRun, Long> {

    List<WebSearchRun> findAllByOrderByCreatedAtDesc();
}
