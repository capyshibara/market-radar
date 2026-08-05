import com.marketradar.pipeline.PipelineCheckpointRules;

import java.util.List;

/** Standalone regression test: javac + java -ea, matching the repository test convention. */
public class PipelineCheckpointRulesTest {

    public static void main(String[] args) {
        healthyFunnelPasses();
        systemicCollapseStops();
        pilotsCannotMasqueradeAsCompletedStages();
        lowL1YieldWarnsWithoutDeletingClaims();
        neutralVerdictsStayForHumanReview();
        System.out.println("PipelineCheckpointRulesTest: ALL PASS");
    }

    private static void healthyFunnelPasses() {
        var m = metrics(804, 711, 711, 420, 420, 360, 8,
                1800, 350, 700, 350, 560, 560, 430, 90, 25, 15, 430);
        List<PipelineCheckpointRules.Checkpoint> out = PipelineCheckpointRules.evaluate(m);
        assert decision(out, "ingest") == PipelineCheckpointRules.Decision.PASS;
        assert decision(out, "classify") == PipelineCheckpointRules.Decision.PASS;
        assert decision(out, "extract") == PipelineCheckpointRules.Decision.PASS;
        assert decision(out, "interpret") == PipelineCheckpointRules.Decision.PASS;
        assert decision(out, "verify") == PipelineCheckpointRules.Decision.PASS;
        assert decision(out, "review") == PipelineCheckpointRules.Decision.PASS;
    }

    private static void systemicCollapseStops() {
        var empty = metrics(0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        assert decision(PipelineCheckpointRules.evaluate(empty), "ingest")
                == PipelineCheckpointRules.Decision.STOP;

        var noFacts = metrics(804, 711, 711, 420, 420, 0, 210,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        assert decision(PipelineCheckpointRules.evaluate(noFacts), "extract")
                == PipelineCheckpointRules.Decision.STOP;
    }

    private static void pilotsCannotMasqueradeAsCompletedStages() {
        var extractionPilot = metrics(804, 711, 711, 420, 10, 10, 0,
                60, 10, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        var extraction = checkpoint(PipelineCheckpointRules.evaluate(extractionPilot), "extract");
        assert extraction.decision() == PipelineCheckpointRules.Decision.WAITING;
        assert extraction.message().contains("Pilot/incomplete extraction");

        var interpretationPilot = metrics(804, 711, 711, 420, 420, 350, 5,
                1400, 300, 40, 12, 30, 0, 0, 0, 0, 0, 0);
        var interpretation = checkpoint(PipelineCheckpointRules.evaluate(interpretationPilot), "interpret");
        assert interpretation.decision() == PipelineCheckpointRules.Decision.WAITING;
        assert interpretation.message().contains("Pilot/incomplete analysis");
    }

    private static void lowL1YieldWarnsWithoutDeletingClaims() {
        var m = metrics(804, 711, 711, 420, 420, 300, 10,
                1200, 280, 500, 280, 120, 0, 0, 0, 0, 0, 0);
        var checkpoint = checkpoint(PipelineCheckpointRules.evaluate(m), "interpret");
        assert checkpoint.decision() == PipelineCheckpointRules.Decision.WARN;
        assert checkpoint.message().contains("remain in the audit/review trail");
    }

    private static void neutralVerdictsStayForHumanReview() {
        var m = metrics(804, 711, 711, 420, 420, 300, 10,
                1200, 280, 500, 280, 400, 400, 0, 390, 5, 5, 0);
        var verification = checkpoint(PipelineCheckpointRules.evaluate(m), "verify");
        var review = checkpoint(PipelineCheckpointRules.evaluate(m), "review");
        assert verification.decision() == PipelineCheckpointRules.Decision.WARN;
        assert verification.message().contains("not erased");
        assert review.decision() == PipelineCheckpointRules.Decision.WAITING;
    }

    private static PipelineCheckpointRules.Metrics metrics(
            long documents, long usable, long classifications, long confirmed,
            long extractionAttempts, long extractionSuccess, long extractionFailures,
            long facts, long factDocs, long claims, long claimDocs, long l1Pass,
            long verifications, long entailed, long neutral, long contradicted,
            long verifierErrors, long reportEligible) {
        return new PipelineCheckpointRules.Metrics(documents, usable, classifications, confirmed,
                extractionAttempts, extractionSuccess, extractionFailures, facts, factDocs,
                facts, 0, claims, claimDocs, l1Pass, verifications, entailed, neutral, contradicted, verifierErrors,
                reportEligible, 0, 0);
    }

    private static PipelineCheckpointRules.Decision decision(
            List<PipelineCheckpointRules.Checkpoint> checkpoints, String stage) {
        return checkpoint(checkpoints, stage).decision();
    }

    private static PipelineCheckpointRules.Checkpoint checkpoint(
            List<PipelineCheckpointRules.Checkpoint> checkpoints, String stage) {
        return checkpoints.stream().filter(c -> c.stage().equals(stage)).findFirst().orElseThrow();
    }
}
