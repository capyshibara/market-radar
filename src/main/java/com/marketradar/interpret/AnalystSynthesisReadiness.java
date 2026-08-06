package com.marketradar.interpret;

import java.util.Locale;

/**
 * Fail-closed readiness rule for corpus-wide synthesis.
 *
 * <p>A small, fully audited parser-error tail must not suppress a well-covered corpus,
 * but an unattempted tail must never be silently treated as low value.  The caller
 * therefore supplies the number of residual documents whose current signature/input
 * already has a durable failed attempt.</p>
 */
public final class AnalystSynthesisReadiness {

    public record Decision(boolean ready, double coverage, int residualDocuments,
                           int auditedFailedDocuments, String message) {}

    private AnalystSynthesisReadiness() {}

    public static Decision evaluate(int eligibleDocuments, int representedDocuments,
                                    int auditedFailedDocuments, int deferredDocuments,
                                    double minimumCoverage) {
        if (eligibleDocuments <= 0) {
            return new Decision(false, 0.0, 0, 0,
                    "No eligible documents are available for synthesis.");
        }
        if (minimumCoverage <= 0.0 || minimumCoverage > 1.0) {
            throw new IllegalArgumentException("minimumCoverage must be in (0, 1]");
        }
        int represented = Math.max(0, Math.min(eligibleDocuments, representedDocuments));
        int residual = Math.max(0, eligibleDocuments - represented);
        int audited = Math.max(0, Math.min(residual, auditedFailedDocuments));
        int deferred = Math.max(0, deferredDocuments);
        double coverage = (double) represented / eligibleDocuments;
        boolean fullTailAudit = deferred == 0 && audited == residual;
        boolean ready = coverage >= minimumCoverage && fullTailAudit;

        String message;
        if (ready && residual == 0) {
            message = String.format(Locale.ROOT,
                    "Synthesis ready: %.1f%% document coverage; no residual documents.",
                    coverage * 100.0);
        } else if (ready) {
            message = String.format(Locale.ROOT,
                    "Synthesis ready: %.1f%% document coverage; %d residual document(s) "
                            + "have durable failed attempts and remain quarantined.",
                    coverage * 100.0, residual);
        } else if (deferred > 0) {
            message = String.format(Locale.ROOT,
                    "Synthesis withheld: %d document(s) remain deferred to a later Analyst batch.",
                    deferred);
        } else if (coverage < minimumCoverage) {
            message = String.format(Locale.ROOT,
                    "Synthesis withheld: %.1f%% document coverage is below the configured %.1f%% minimum.",
                    coverage * 100.0, minimumCoverage * 100.0);
        } else {
            message = String.format(Locale.ROOT,
                    "Synthesis withheld: %d residual document(s) have not produced either a current edition "
                            + "or a durable failed attempt for the current input.",
                    Math.max(0, residual - audited));
        }
        return new Decision(ready, coverage, residual, audited, message);
    }
}
