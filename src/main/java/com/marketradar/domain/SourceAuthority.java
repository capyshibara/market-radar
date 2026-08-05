package com.marketradar.domain;

/**
 * Editorial authority of a source, independent from the market it covers.
 *
 * <p>This is the CFO's "weighting of source quality" axis. A global regulator
 * and an official Vietnam insurer page can both be authoritative even though
 * they belong to different geographies. Conversely, a Vietnamese social post
 * does not become primary evidence merely because it is domestic.</p>
 */
public enum SourceAuthority {
    REGULATOR(100, true),
    STATUTORY_DISCLOSURE(98, true),
    OFFICIAL_COMPANY(92, true),
    INDUSTRY_BODY(88, true),
    SPECIALIST_RESEARCH(84, true),
    ESTABLISHED_MEDIA(78, false),
    PROFESSIONAL_SERVICES(76, false),
    OTHER_PUBLISHER(58, false),
    SOCIAL_OR_BLOG(35, false),
    UNKNOWN(45, false);

    private final int credibilityScore;
    private final boolean primaryEvidence;

    SourceAuthority(int credibilityScore, boolean primaryEvidence) {
        this.credibilityScore = credibilityScore;
        this.primaryEvidence = primaryEvidence;
    }

    public int credibilityScore() { return credibilityScore; }
    public boolean isPrimaryEvidence() { return primaryEvidence; }
}
