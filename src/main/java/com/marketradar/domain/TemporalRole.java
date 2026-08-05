package com.marketradar.domain;

/** The business meaning of a date; publication time is kept separately on RawDoc. */
public enum TemporalRole {
    OCCURRED,
    EFFECTIVE,
    EXPIRING,
    FORECAST,
    SCHEDULED,
    PUBLICATION_ONLY,
    UNDATED
}
