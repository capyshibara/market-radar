package com.marketradar.product;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductBriefInsightRepository extends JpaRepository<ProductBriefInsight, Long> {
    List<ProductBriefInsight> findByEditionOrderByRankOrderAsc(ProductBriefEdition edition);
}
