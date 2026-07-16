import java.nio.file.Files;
import java.nio.file.Path;

/** Static contract: reviewers can open each cited article without weakening link safety. */
public class ReviewerEvidenceLinkContractTest {
    public static void main(String[] args) throws Exception {
        String template = Files.readString(Path.of("src/main/resources/templates/review-detail.html"));
        String messages = Files.readString(Path.of("src/main/resources/messages.properties"));
        String messagesVi = Files.readString(Path.of("src/main/resources/messages_vi.properties"));

        check(template.contains("th:href=\"${f.rawDoc.url}\""), "evidence links to the original document");
        check(template.contains("#strings.startsWith(f.rawDoc.url, 'https://')"), "only HTTPS document links render");
        check(template.contains("target=\"_blank\" rel=\"noopener noreferrer\""), "external link is isolated safely");
        check(template.contains("ops.review.detail.publishedAt"), "publication date is displayed when available");
        check(messages.contains("ops.review.detail.openOriginalSource="), "English source-link label exists");
        check(messagesVi.contains("ops.review.detail.openOriginalSource="), "Vietnamese source-link label exists");
        System.out.println("ReviewerEvidenceLinkContractTest: ALL PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
