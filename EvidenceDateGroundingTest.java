import com.marketradar.extract.EvidenceDateGrounding;

import java.time.LocalDate;

public class EvidenceDateGroundingTest {
    public static void main(String[] args) {
        checkGrounded("2026-07-16", "Effective on July 16, 2026.");
        checkGrounded("2026-07-16", "Có hiệu lực từ ngày 16 tháng 7 năm 2026.");
        checkGrounded("2026-07-16", "Áp dụng từ 16/07/2026 đến 31.12.2026.");
        check(EvidenceDateGrounding.datesIn("Ends 31.12.2026").contains(
                LocalDate.of(2026, 12, 31)), "dot date normalized");

        var hallucinated = EvidenceDateGrounding.parseAndGround(
                "2030-01-01", "The company described a future target for 2030.");
        check(hallucinated.status() == EvidenceDateGrounding.Status.UNGROUNDED,
                "year-only source cannot ground fabricated day/month precision");
        check(!EvidenceDateGrounding.criticalFieldAcceptable(hallucinated),
                "ungrounded effective/expiry date must reject the fact");
        var invalid = EvidenceDateGrounding.parseAndGround("16/07/2026", "16/07/2026");
        check(invalid.status() == EvidenceDateGrounding.Status.INVALID_FORMAT,
                "model output must remain ISO even when source representation differs");
        System.out.println("EvidenceDateGroundingTest: ALL PASS");
    }

    private static void checkGrounded(String modelDate, String span) {
        var result = EvidenceDateGrounding.parseAndGround(modelDate, span);
        check(result.status() == EvidenceDateGrounding.Status.GROUNDED,
                modelDate + " grounded by " + span);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError("Failed: " + message);
    }
}
