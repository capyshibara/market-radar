package com.marketradar.product;

import jakarta.persistence.*;

/** A structured What → Pattern → So what → Now what insight in an immutable edition. */
@Entity
@Table(name = "product_brief_insights",
        uniqueConstraints = @UniqueConstraint(name = "uk_product_brief_rank", columnNames = {"edition_id", "rankOrder"}))
public class ProductBriefInsight {

    public enum Confidence { HIGH, MEDIUM, LOW }
    public enum PublicationDisposition { DECISION_READY, WATCH, REJECT }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private ProductBriefEdition edition;

    @Column(nullable = false)
    private int rankOrder;

    @Column(nullable = false, length = 48)
    private String kiqCode;

    @Column(nullable = false, length = 48)
    private String themeCode;

    @Column(nullable = false, length = 512)
    private String headlineVi;

    @Column(nullable = false, length = 512)
    private String headlineEn;

    @Lob @Column(nullable = false, columnDefinition = "CLOB")
    private String whatVi;
    @Lob @Column(nullable = false, columnDefinition = "CLOB")
    private String whatEn;
    @Lob @Column(nullable = false, columnDefinition = "CLOB")
    private String patternVi;
    @Lob @Column(nullable = false, columnDefinition = "CLOB")
    private String patternEn;
    @Lob @Column(nullable = false, columnDefinition = "CLOB")
    private String soWhatVi;
    @Lob @Column(nullable = false, columnDefinition = "CLOB")
    private String soWhatEn;
    @Lob @Column(nullable = false, columnDefinition = "CLOB")
    private String nowWhatVi;
    @Lob @Column(nullable = false, columnDefinition = "CLOB")
    private String nowWhatEn;
    @Lob @Column(nullable = false, columnDefinition = "CLOB")
    private String caveatVi;
    @Lob @Column(nullable = false, columnDefinition = "CLOB")
    private String caveatEn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Confidence confidence;

    @Column(nullable = false)
    private int materialityScore;

    @Column(nullable = false, length = 2048)
    private String factCodesCsv;

    /** Corroboration is event-cluster based, not fact/span based. */
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int independentClusterCount;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int independentDocumentCount;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int independentSourceCount;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean conflictFree;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean futureActionEligible;

    @Column(length = 2048)
    private String clusterKeysCsv;

    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private PublicationDisposition publicationDisposition;

    @Column(length = 2048)
    private String publicationFailureCodesCsv;

    private Double resolvedEvidenceRatio;

    @Column(length = 64)
    private String publicationGateVersion;

    protected ProductBriefInsight() {}

    public ProductBriefInsight(ProductBriefEdition edition, int rankOrder, String kiqCode,
                               String themeCode, String headlineVi, String headlineEn,
                               String whatVi, String whatEn, String patternVi, String patternEn,
                               String soWhatVi, String soWhatEn, String nowWhatVi, String nowWhatEn,
                               String caveatVi, String caveatEn, Confidence confidence,
                               int materialityScore, String factCodesCsv,
                               int independentClusterCount, int independentDocumentCount,
                               int independentSourceCount, boolean conflictFree,
                               boolean futureActionEligible, String clusterKeysCsv,
                               PublicationDisposition publicationDisposition,
                               String publicationFailureCodesCsv, double resolvedEvidenceRatio,
                               String publicationGateVersion) {
        this.edition = edition;
        this.rankOrder = rankOrder;
        this.kiqCode = kiqCode;
        this.themeCode = themeCode;
        this.headlineVi = headlineVi;
        this.headlineEn = headlineEn;
        this.whatVi = whatVi;
        this.whatEn = whatEn;
        this.patternVi = patternVi;
        this.patternEn = patternEn;
        this.soWhatVi = soWhatVi;
        this.soWhatEn = soWhatEn;
        this.nowWhatVi = nowWhatVi;
        this.nowWhatEn = nowWhatEn;
        this.caveatVi = caveatVi;
        this.caveatEn = caveatEn;
        this.confidence = confidence;
        this.materialityScore = materialityScore;
        this.factCodesCsv = factCodesCsv;
        this.independentClusterCount = independentClusterCount;
        this.independentDocumentCount = independentDocumentCount;
        this.independentSourceCount = independentSourceCount;
        this.conflictFree = conflictFree;
        this.futureActionEligible = futureActionEligible;
        this.clusterKeysCsv = clusterKeysCsv;
        this.publicationDisposition = publicationDisposition;
        this.publicationFailureCodesCsv = publicationFailureCodesCsv;
        this.resolvedEvidenceRatio = resolvedEvidenceRatio;
        this.publicationGateVersion = publicationGateVersion;
    }

    public Long getId() { return id; }
    public ProductBriefEdition getEdition() { return edition; }
    public int getRankOrder() { return rankOrder; }
    public String getKiqCode() { return kiqCode; }
    public String getThemeCode() { return themeCode; }
    public String getHeadlineVi() { return headlineVi; }
    public String getHeadlineEn() { return headlineEn; }
    public String getWhatVi() { return whatVi; }
    public String getWhatEn() { return whatEn; }
    public String getPatternVi() { return patternVi; }
    public String getPatternEn() { return patternEn; }
    public String getSoWhatVi() { return soWhatVi; }
    public String getSoWhatEn() { return soWhatEn; }
    public String getNowWhatVi() { return nowWhatVi; }
    public String getNowWhatEn() { return nowWhatEn; }
    public String getCaveatVi() { return caveatVi; }
    public String getCaveatEn() { return caveatEn; }
    public Confidence getConfidence() { return confidence; }
    public int getMaterialityScore() { return materialityScore; }
    public String getFactCodesCsv() { return factCodesCsv; }
    public int getIndependentClusterCount() { return independentClusterCount; }
    public int getIndependentDocumentCount() { return independentDocumentCount; }
    public int getIndependentSourceCount() { return independentSourceCount; }
    public boolean isConflictFree() { return conflictFree; }
    public boolean isFutureActionEligible() { return futureActionEligible; }
    public String getClusterKeysCsv() { return clusterKeysCsv; }
    public PublicationDisposition getPublicationDisposition() { return publicationDisposition; }
    public String getPublicationFailureCodesCsv() { return publicationFailureCodesCsv; }
    public Double getResolvedEvidenceRatio() { return resolvedEvidenceRatio; }
    public String getPublicationGateVersion() { return publicationGateVersion; }

    public String headline(boolean vi) { return vi ? headlineVi : headlineEn; }
    public String what(boolean vi) { return vi ? whatVi : whatEn; }
    public String pattern(boolean vi) { return vi ? patternVi : patternEn; }
    public String soWhat(boolean vi) { return vi ? soWhatVi : soWhatEn; }
    public String nowWhat(boolean vi) { return vi ? nowWhatVi : nowWhatEn; }
    public String caveat(boolean vi) { return vi ? caveatVi : caveatEn; }
}
