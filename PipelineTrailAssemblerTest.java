import com.marketradar.pipeline.PipelineTrailAssembler;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Standalone regression test: status history must never leak across pipeline cycles. */
public class PipelineTrailAssemblerTest {
    public static void main(String[] args) {
        var events = List.of(
                new PipelineTrailAssembler.Event(12, 101L, "ingest", "NEW"),
                new PipelineTrailAssembler.Event(12, 101L, "classify", "CONFIRMED"),
                new PipelineTrailAssembler.Event(13, 101L, "ingest", "UNCHANGED"),
                new PipelineTrailAssembler.Event(13, 101L, "classify", "HELD_FOR_REVIEW"),
                new PipelineTrailAssembler.Event(13, 202L, "ingest", "NEW"),
                new PipelineTrailAssembler.Event(13, 202L, "extract", "EXTRACTED"));

        Map<PipelineTrailAssembler.DocCycleKey, Map<String, String>> cycle12 =
                PipelineTrailAssembler.statusesByDocumentAndCycle(events, Set.of(12));
        check(cycle12.size() == 1, "selected cycle contains only its document membership");
        var doc12 = cycle12.get(new PipelineTrailAssembler.DocCycleKey(12, 101L));
        check("NEW".equals(PipelineTrailAssembler.statusFor(doc12, "ingest")), "cycle 12 ingest kept");
        check("CONFIRMED".equals(PipelineTrailAssembler.statusFor(doc12, "classify")), "cycle 12 classification kept");
        check(PipelineTrailAssembler.NOT_PROCESSED.equals(PipelineTrailAssembler.statusFor(doc12, "extract")),
                "missing stage is not inferred from current document state");

        Map<PipelineTrailAssembler.DocCycleKey, Map<String, String>> all =
                PipelineTrailAssembler.statusesByDocumentAndCycle(events, Set.of(12, 13));
        check(all.size() == 3, "all cycles are one row per document plus cycle");
        var doc13 = all.get(new PipelineTrailAssembler.DocCycleKey(13, 101L));
        check("UNCHANGED".equals(PipelineTrailAssembler.statusFor(doc13, "ingest")), "new cycle ingest is separate");
        check("HELD_FOR_REVIEW".equals(PipelineTrailAssembler.statusFor(doc13, "classify")),
                "new cycle classification cannot merge with prior CONFIRMED");
        check("EXTRACTED".equals(PipelineTrailAssembler.statusFor(
                all.get(new PipelineTrailAssembler.DocCycleKey(13, 202L)), "extract")),
                "second document belongs only to its cycle");

        System.out.println("PipelineTrailAssemblerTest: ALL PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
