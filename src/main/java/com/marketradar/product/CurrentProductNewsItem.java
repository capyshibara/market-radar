package com.marketradar.product;

import java.time.LocalDate;

/** Read model for a source-backed item; every displayed sentence is a source field. */
public record CurrentProductNewsItem(
        String factCode,
        long rawDocId,
        String title,
        String sourceCode,
        String sourceName,
        int sourceTier,
        String sourceUrl,
        LocalDate publishedDate,
        String factType,
        String verbatimEvidenceSpan) {}
