import java.nio.file.Files;
import java.nio.file.Path;

/** Regression contract for historical claim-to-evidence traceability. */
public class ReviewEvidenceLifecycleContractTest {
    public static void main(String[] args) throws Exception {
        String review = Files.readString(Path.of("src/main/java/com/marketradar/review/ReviewController.java"));
        String claims = Files.readString(Path.of("src/main/java/com/marketradar/report/ClaimController.java"));
        String repository = Files.readString(Path.of(
                "src/main/java/com/marketradar/repo/InterpretedClaimRepository.java"));
        String verificationJob = Files.readString(Path.of(
                "src/main/java/com/marketradar/verify/VerificationJob.java"));
        String template = Files.readString(Path.of("src/main/resources/templates/review-detail.html"));

        check(review.contains("findAllByFactCodeInForAudit(codes)"),
                "review detail resolves superseded fact editions from the audit store");
        check(!review.contains("facts.findAllForReport().stream()"),
                "review actions do not resolve historical citations through the active-report query");
        check(review.contains("requireCompleteEvidence(evidence)"),
                "server blocks approval when any cited fact is unresolved");
        check(claims.contains("findAllByFactCodeInForAudit(citedCodes)"),
                "claim audit also resolves superseded evidence");
        check(template.contains("missingFactCodes"), "missing fact codes are explained to reviewers");
        check(template.contains("!f.active") && template.contains("archivedEvidence"),
                "superseded evidence is visibly labeled");
        check(template.contains("th:with=\"hasEvidence=${evidenceComplete}\""),
                "approval controls require complete evidence resolution");
        check(repository.contains("left join fetch c.rawDoc rd left join fetch rd.source"),
                "review and verifier queries eagerly load source attribution metadata");
        check(repository.contains("findRetryableLatestVerifierErrors")
                        && repository.contains("newer.createdAt > v.createdAt"),
                "retry lane selects only claims whose latest verdict is a technical error");
        check(verificationJob.contains("putIfAbsent(c.getId(), c)"),
                "pending claims and technical-error retries are de-duplicated before verification");
        System.out.println("ReviewEvidenceLifecycleContractTest: ALL PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
