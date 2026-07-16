import java.nio.file.Files;
import java.nio.file.Path;

/** Regression: intake stays a two-action experience, not a metadata form. */
public class ManualIntakeUiContractTest {
    public static void main(String[] args) throws Exception {
        String html = Files.readString(Path.of("src/main/resources/templates/manual-intake.html"));
        check(html.contains("action=\"/documents/intake/url\""), "URL import action exists");
        check(html.contains("name=\"sourceUrl\""), "single URL input exists");
        check(html.contains("action=\"/documents/intake/upload\""), "file upload action exists");
        check(html.contains("name=\"file\""), "single file input exists");
        check(!html.contains("name=\"publisher\""), "publisher is not requested");
        check(!html.contains("name=\"publishedDate\""), "publication date is not requested");
        check(!html.contains("name=\"language\""), "language is not requested");
        check(!html.contains("name=\"title\""), "title is not requested");
        System.out.println("ManualIntakeUiContractTest: ALL PASS");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
