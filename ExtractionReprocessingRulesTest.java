import com.marketradar.domain.RawDoc;
import com.marketradar.domain.Source;
import com.marketradar.domain.FactExtractionRun;
import com.marketradar.extract.ExtractionContentDiagnostics;
import com.marketradar.extract.ExtractionVersioning;
import com.marketradar.extract.ExtractionPersistenceService;

import java.time.Instant;
import java.util.List;

/** Standalone pure regression checks; no Spring context, database or network. */
public class ExtractionReprocessingRulesTest {

    public static void main(String[] args) {
        Source source = new Source("TEST_VN", "Test", "https://test.vn/news", "test.vn",
                Source.SourceType.HTML, 2, "vi");

        RawDoc titleOnly = doc(source, "Short title");
        check(ExtractionContentDiagnostics.assess(titleOnly, true, false)
                        == ExtractionContentDiagnostics.State.NEEDS_FULL_TEXT,
                "title-only document is diagnosed before length");
        check("TITLE_ONLY_OR_UNVERIFIED_FULL_TEXT".equals(
                        ExtractionContentDiagnostics.assessDetailed(titleOnly, true, false).reasonCode()),
                "title-only rejection reason is explicit");

        RawDoc shortDoc = doc(source, "short body");
        shortDoc.upgradeToFullText("b".repeat(64), "short body", null);
        check(ExtractionContentDiagnostics.assess(shortDoc, true, false)
                        == ExtractionContentDiagnostics.State.SHORT_TEXT,
                "short full-text document is blocked");
        check(ExtractionContentDiagnostics.assessDetailed(shortDoc, true, false)
                        .reasonCode().startsWith("ARTICLE_BELOW_MINIMUM_"),
                "short-text rejection reason is explicit");

        RawDoc complete = doc(source, "x".repeat(900));
        complete.upgradeToFullText("c".repeat(64), "x".repeat(900), null);
        check(ExtractionContentDiagnostics.assess(complete, true, false)
                        == ExtractionContentDiagnostics.State.READY_STALE,
                "complete stale document is eligible");
        check(ExtractionContentDiagnostics.assess(complete, true, true)
                        == ExtractionContentDiagnostics.State.CURRENT,
                "current signature is skipped");

        var v1 = ExtractionVersioning.current("gpt-5-mini", "prompt one");
        var v2 = ExtractionVersioning.current("gpt-5-mini", "prompt two");
        String signature1 = ExtractionVersioning.signature(v1, complete);
        String signature1Again = ExtractionVersioning.signature(v1, complete);
        String signature2 = ExtractionVersioning.signature(v2, complete);
        check(signature1.equals(signature1Again), "signature is deterministic");
        check(!signature1.equals(signature2), "prompt change makes output stale");

        complete.upgradeToFullText("d".repeat(64), "y".repeat(900), null);
        check(!signature1.equals(ExtractionVersioning.signature(v1, complete)),
                "content backfill makes output stale");

        var otherModel = ExtractionVersioning.current("another-model", "prompt one");
        check(!signature1.equals(ExtractionVersioning.signature(otherModel, docWithHash(
                        source, "x".repeat(900), "c".repeat(64)))),
                "model change makes output stale");

        var lengths = ExtractionContentDiagnostics.summarizeLengths(
                List.of(600, 1_000, 2_000, 30_000));
        check(lengths.medianChars() == 2_000, "length median is deterministic");
        check(lengths.inputTruncatedDocuments() == 1,
                "documents beyond extractor cap are counted");

        FactExtractionRun empty = new FactExtractionRun(complete, v1.pipelineVersion(),
                v1.modelVersion(), v1.promptSha256(), complete.getContentHash(),
                ExtractionVersioning.signature(v1, complete));
        empty.fail(FactExtractionRun.Status.EMPTY_RESULT, "no accepted facts");
        check(empty.getStatus() == FactExtractionRun.Status.EMPTY_RESULT,
                "empty extraction has an explicit non-success status");
        check(!empty.isCurrentEdition(), "empty extraction cannot become current");
        boolean activationRejected = false;
        try {
            new ExtractionPersistenceService(null, null).succeed(1L, List.of(), 0);
        } catch (IllegalArgumentException expected) {
            activationRejected = true;
        }
        check(activationRejected, "persistence layer rejects empty edition activation");

        System.out.println("ExtractionReprocessingRulesTest: ALL PASS");
    }

    private static RawDoc doc(Source source, String text) {
        return docWithHash(source, text, "a".repeat(64));
    }

    private static RawDoc docWithHash(Source source, String text, String hash) {
        return new RawDoc(source, "https://test.vn/article", "Title",
                Instant.parse("2026-07-15T00:00:00Z"),
                Instant.parse("2026-07-15T01:00:00Z"), hash, text, "vi",
                RawDoc.ParseStatus.OK, null);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError("Failed: " + message);
    }
}
