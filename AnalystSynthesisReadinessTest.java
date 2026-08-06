import com.marketradar.interpret.AnalystSynthesisReadiness;

public class AnalystSynthesisReadinessTest {
    public static void main(String[] args) {
        var complete = AnalystSynthesisReadiness.evaluate(102, 102, 0, 0, 0.90);
        assert complete.ready();

        var auditedTail = AnalystSynthesisReadiness.evaluate(102, 95, 7, 0, 0.90);
        assert auditedTail.ready();
        assert auditedTail.residualDocuments() == 7;

        var unattemptedTail = AnalystSynthesisReadiness.evaluate(102, 95, 6, 0, 0.90);
        assert !unattemptedTail.ready();

        var partialBatch = AnalystSynthesisReadiness.evaluate(102, 95, 7, 1, 0.90);
        assert !partialBatch.ready();

        var lowCoverage = AnalystSynthesisReadiness.evaluate(102, 56, 46, 0, 0.90);
        assert !lowCoverage.ready();

        System.out.println("AnalystSynthesisReadinessTest passed");
    }
}
