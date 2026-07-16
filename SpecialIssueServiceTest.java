import com.marketradar.specialissue.SpecialIssueService;

import java.util.Locale;

/** Regression check for the curated Special Issue reader model and Topic Lab readiness rules. */
public class SpecialIssueServiceTest {
    public static void main(String[] args) {
        SpecialIssueService service = new SpecialIssueService();
        var wellness = service.candidate("wellness-linked-life");
        check("READY".equals(wellness.readiness()), "wellness topic is eligible to commission");
        check(wellness.evidenceDocuments() >= 7 && wellness.primarySources() >= 3,
                "wellness topic exposes evidence-depth metrics");

        var issue = service.issue("wellness-linked-life", Locale.ENGLISH);
        check(issue.sections().size() == 7, "issue has the full Product Academy learning structure");
        check(issue.sources().size() >= 5, "issue keeps a traceable source register");

        try {
            service.commission("cancer-prevention-benefits");
            throw new AssertionError("incomplete research packs cannot be commissioned");
        } catch (SpecialIssueService.IssueNotReadyException expected) {
            check(expected.getMessage().contains("cannot be commissioned"), "clear readiness reason");
        }
        System.out.println("SpecialIssueServiceTest: ALL PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

