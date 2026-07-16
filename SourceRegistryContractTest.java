import java.nio.file.Files;
import java.nio.file.Path;

/** Static contract for the bilingual Add source demonstration. */
public class SourceRegistryContractTest {
    public static void main(String[] args) throws Exception {
        String template = Files.readString(Path.of("src/main/resources/templates/sources.html"));
        String messages = Files.readString(Path.of("src/main/resources/messages.properties"));
        String messagesVi = Files.readString(Path.of("src/main/resources/messages_vi.properties"));

        check(template.contains("data-role-show=\"admin\""), "add flow is admin-only");
        check(template.contains("id=\"source-modal\""), "add modal exists");
        for (String type : new String[]{"RSS", "HTML", "PDF", "JSON"}) {
            check(template.contains("value=\"" + type + "\""), type + " parser option exists");
        }
        check(template.contains("fetch('/sources/test'"), "safe test endpoint is wired");
        check(template.contains("fetch('/sources'"), "save endpoint is wired");
        check(template.contains("id=\"test-size\""), "response size is shown");
        check(template.contains("th:if=\"${s.active}\""), "active status is shown separately");
        check(template.contains("id=\"source-save\"") && template.contains("disabled"),
                "save begins locked until a successful test");

        for (String key : new String[]{
                "ops.sources.addSource=", "ops.sources.formTitle=", "ops.sources.testAction=",
                "ops.sources.testSize=", "ops.sources.parserNeeded="}) {
            check(messages.contains(key), "English key exists: " + key);
            check(messagesVi.contains(key), "Vietnamese key exists: " + key);
        }
        System.out.println("SourceRegistryContractTest: ALL PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
