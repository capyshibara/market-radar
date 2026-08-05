package com.marketradar.pipeline;

import java.util.List;

/**
 * Pure firebreak rules for the five-stage Strategy pipeline.
 *
 * <p>STOP is deliberately reserved for systemic collapse: no usable corpus, no
 * classifications, no facts, no grounded claims, or a majority of technical
 * errors. Low yield, NEUTRAL verdicts and a thin publishable set are WARN/WAITING
 * states. They remain visible for human curation instead of being deleted by an
 * over-tight publication gate.</p>
 */
public final class PipelineCheckpointRules {

    public enum Decision { PASS, WARN, WAITING, STOP }

    public record Metrics(
            long documents,
            long usableDocuments,
            long classifications,
            long confirmedClassifications,
            long latestExtractionAttempts,
            long successfulExtractionDocuments,
            long technicalExtractionFailures,
            long activeFacts,
            long activeFactDocuments,
            long coreDimensionCompleteFacts,
            long entityQuarantinedFacts,
            long activePipelineClaims,
            long activeClaimDocuments,
            long gateL1PassedClaims,
            long latestVerifications,
            long entailedVerifications,
            long neutralVerifications,
            long contradictedVerifications,
            long verifierErrors,
            long reportEligibleClaims,
            long reviewedAnalysisClaims,
            long editorialWatchClaims) {}

    public record Checkpoint(String stage, String label, Decision decision, String message) {}

    private PipelineCheckpointRules() {}

    public static List<Checkpoint> evaluate(Metrics m) {
        return List.of(
                scout(m),
                librarianRouter(m),
                researcherConnector(m),
                analystFactChecker(m),
                independentVerifier(m),
                humanEditor(m));
    }

    private static Checkpoint scout(Metrics m) {
        if (m.documents() == 0 || m.usableDocuments() == 0) {
            return checkpoint("ingest", "Scout", Decision.STOP,
                    "Corpus has no usable full-text documents; downstream stages must not run.");
        }
        double ratio = ratio(m.usableDocuments(), m.documents());
        if (ratio < 0.75d) {
            return checkpoint("ingest", "Scout", Decision.WARN,
                    percent(ratio) + " of documents are usable; keep shallow records for audit, "
                            + "but classify only eligible full text.");
        }
        return checkpoint("ingest", "Scout", Decision.PASS,
                m.usableDocuments() + "/" + m.documents()
                        + " documents are eligible full text; shallow records remain auditable.");
    }

    private static Checkpoint librarianRouter(Metrics m) {
        if (m.classifications() == 0) {
            return checkpoint("classify", "Librarian + Router", Decision.WAITING,
                    "No curation result yet; run only with a configured real classifier.");
        }
        double coverage = ratio(m.classifications(), m.usableDocuments());
        if (coverage < 0.50d || m.confirmedClassifications() == 0) {
            return checkpoint("classify", "Librarian + Router", Decision.STOP,
                    "Systemic curation collapse: " + m.classifications() + "/"
                            + m.usableDocuments() + " usable documents processed and "
                            + m.confirmedClassifications() + " confirmed.");
        }
        if (coverage < 0.90d) {
            return checkpoint("classify", "Librarian + Router", Decision.WARN,
                    percent(coverage) + " curation coverage; preserve uncertain/out-of-scope "
                            + "rows and inspect errors before relying on the report.");
        }
        return checkpoint("classify", "Librarian + Router", Decision.PASS,
                m.classifications() + " documents curated; "
                        + m.confirmedClassifications() + " confirmed for evidence extraction.");
    }

    private static Checkpoint researcherConnector(Metrics m) {
        if (m.confirmedClassifications() == 0) {
            return checkpoint("extract", "Researcher + Connector", Decision.WAITING,
                    "No confirmed documents are available from curation.");
        }
        if (m.latestExtractionAttempts() == 0) {
            return checkpoint("extract", "Researcher + Connector", Decision.WAITING,
                    "Evidence extraction has not run on the confirmed set.");
        }
        double extractionCoverage = ratio(
                m.latestExtractionAttempts(), m.confirmedClassifications());
        if (extractionCoverage < 0.90d) {
            return checkpoint("extract", "Researcher + Connector", Decision.WAITING,
                    "Pilot/incomplete extraction: " + m.latestExtractionAttempts() + "/"
                            + m.confirmedClassifications() + " confirmed documents attempted ("
                            + percent(extractionCoverage)
                            + "). Inspect the pilot, then explicitly run the remaining set; "
                            + "do not treat this stage as complete.");
        }
        if (m.activeFacts() == 0 || m.activeFactDocuments() == 0) {
            return checkpoint("extract", "Researcher + Connector", Decision.STOP,
                    "Extraction attempted but produced zero active evidence facts.");
        }
        double technicalFailureRate = ratio(
                m.technicalExtractionFailures(), m.latestExtractionAttempts());
        if (technicalFailureRate >= 0.50d) {
            return checkpoint("extract", "Researcher + Connector", Decision.STOP,
                    percent(technicalFailureRate)
                            + " of latest extraction attempts failed technically (LLM/schema).");
        }
        double metadataCoverage = ratio(m.coreDimensionCompleteFacts(), m.activeFacts());
        if (m.coreDimensionCompleteFacts() == 0) {
            return checkpoint("extract", "Researcher + Connector", Decision.STOP,
                    "Facts exist but none carry the authority/topic/market/time dimensions required downstream.");
        }
        if (extractionCoverage < 1d || technicalFailureRate >= 0.15d || metadataCoverage < 0.90d) {
            return checkpoint("extract", "Researcher + Connector", Decision.WARN,
                    percent(extractionCoverage) + " extraction coverage; "
                            + m.activeFacts() + " facts retained; " + percent(metadataCoverage)
                            + " have complete core dimensions, " + m.entityQuarantinedFacts()
                            + " have ambiguous/conflicting entity attribution, and "
                            + percent(technicalFailureRate) + " of latest attempts failed technically.");
        }
        return checkpoint("extract", "Researcher + Connector", Decision.PASS,
                m.activeFacts() + " active facts across " + m.activeFactDocuments()
                        + " documents; core dimensions are complete. " + m.entityQuarantinedFacts()
                        + " entity-risk facts remain quarantined for review.");
    }

    private static Checkpoint analystFactChecker(Metrics m) {
        if (m.activeFacts() == 0) {
            return checkpoint("interpret", "Analyst + Fact-checker (Gate L1)", Decision.WAITING,
                    "No evidence facts are available for analysis.");
        }
        if (m.activePipelineClaims() == 0) {
            return checkpoint("interpret", "Analyst + Fact-checker (Gate L1)", Decision.WAITING,
                    "Analysis has not produced a claim edition yet.");
        }
        double interpretationCoverage = ratio(
                m.activeClaimDocuments(), m.activeFactDocuments());
        if (interpretationCoverage < 0.90d) {
            return checkpoint("interpret", "Analyst + Fact-checker (Gate L1)", Decision.WAITING,
                    "Pilot/incomplete analysis: claims exist for " + m.activeClaimDocuments()
                            + "/" + m.activeFactDocuments() + " evidence documents ("
                            + percent(interpretationCoverage)
                            + "). Complete document-level analysis before relying on global narratives.");
        }
        if (m.gateL1PassedClaims() == 0) {
            return checkpoint("interpret", "Analyst + Fact-checker (Gate L1)", Decision.STOP,
                    "Claims were generated but none passed deterministic grounding.");
        }
        double passRate = ratio(m.gateL1PassedClaims(), m.activePipelineClaims());
        if (interpretationCoverage < 1d || passRate < 0.35d) {
            return checkpoint("interpret", "Analyst + Fact-checker (Gate L1)", Decision.WARN,
                    percent(interpretationCoverage) + " document coverage; "
                            + percent(passRate) + " of active claims passed L1. Failed claims remain in the "
                            + "audit/review trail; do not delete them or loosen citation integrity.");
        }
        return checkpoint("interpret", "Analyst + Fact-checker (Gate L1)", Decision.PASS,
                m.gateL1PassedClaims() + "/" + m.activePipelineClaims()
                        + " active claims passed deterministic grounding.");
    }

    private static Checkpoint independentVerifier(Metrics m) {
        if (m.gateL1PassedClaims() == 0) {
            return checkpoint("verify", "Independent Verifier (Gate L2)", Decision.WAITING,
                    "No L1-passed claims are available for independent verification.");
        }
        if (m.latestVerifications() == 0) {
            return checkpoint("verify", "Independent Verifier (Gate L2)", Decision.WAITING,
                    "No independent verdict has been appended yet.");
        }
        double coverage = ratio(m.latestVerifications(), m.gateL1PassedClaims());
        double errorRate = ratio(m.verifierErrors(), m.latestVerifications());
        if (coverage < 0.50d || errorRate >= 0.50d) {
            return checkpoint("verify", "Independent Verifier (Gate L2)", Decision.STOP,
                    "Systemic verification failure: " + percent(coverage) + " coverage, "
                            + percent(errorRate) + " verifier errors.");
        }
        if (coverage < 0.90d || errorRate >= 0.10d || m.entailedVerifications() == 0) {
            return checkpoint("verify", "Independent Verifier (Gate L2)", Decision.WARN,
                    m.entailedVerifications() + " entailed, " + m.neutralVerifications()
                            + " neutral, " + m.contradictedVerifications() + " contradicted, "
                            + m.verifierErrors() + " errors. Neutral claims go to human review; "
                            + "they are not erased from the intelligence funnel.");
        }
        return checkpoint("verify", "Independent Verifier (Gate L2)", Decision.PASS,
                m.entailedVerifications() + " entailed verdicts; non-entailed claims remain reviewable.");
    }

    private static Checkpoint humanEditor(Metrics m) {
        if (m.latestVerifications() == 0) {
            return checkpoint("review", "Human Editor", Decision.WAITING,
                    "Verification must finish before editorial sign-off.");
        }
        if (m.reportEligibleClaims() == 0) {
            return checkpoint("review", "Human Editor", Decision.WAITING,
                    "No decision-grade claim is ready yet. " + m.editorialWatchClaims()
                            + " watch signal(s) and " + m.reviewedAnalysisClaims()
                            + " human-reviewed analysis item(s) remain visibly separated; review evidence "
                            + "instead of weakening entity or citation gates.");
        }
        return checkpoint("review", "Human Editor", Decision.PASS,
                m.reportEligibleClaims() + " decision-grade claim(s) can feed management sections; "
                        + m.reviewedAnalysisClaims() + " reviewed analysis item(s) and "
                        + m.editorialWatchClaims() + " watch signal(s) stay visibly separated.");
    }

    private static Checkpoint checkpoint(String stage, String label,
                                         Decision decision, String message) {
        return new Checkpoint(stage, label, decision, message);
    }

    private static double ratio(long numerator, long denominator) {
        return denominator <= 0 ? 0d : (double) numerator / (double) denominator;
    }

    private static String percent(double ratio) {
        return Math.round(ratio * 1000d) / 10d + "%";
    }
}
