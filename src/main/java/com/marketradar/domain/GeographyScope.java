package com.marketradar.domain;

/**
 * Geographic coverage of a source or fact. It never implies credibility.
 * Market code carries the concrete country/region when the scope requires one.
 */
public enum GeographyScope {
    VIETNAM,
    COUNTRY,
    REGIONAL,
    GLOBAL,
    MULTI_MARKET,
    UNKNOWN
}
