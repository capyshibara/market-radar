import com.marketradar.domain.Category;
import com.marketradar.domain.Department;

import java.nio.file.Files;
import java.nio.file.Path;

/** Standalone contract for the department-desk console surfaces. */
public class DeskConsoleContractTest {
    public static void main(String[] args) throws Exception {
        check(Department.valueOf("COMPLIANCE").getTen().contains("Pháp chế"),
                "Compliance desk exists with a Vietnamese display name");
        check(Category.valueOf("PRODUCT_REGULATION") != null,
                "regulation category backing the Compliance desk still exists");

        String hub = Files.readString(Path.of("src/main/resources/templates/desks.html"));
        check(hub.contains("ops-sidebar :: sidebar('desks'"),
                "desk hub lives inside the ops console shell");
        check(hub.contains("d.dept.name()") && hub.contains("ops.desks.dept."),
                "every department renders from the enum with an i18n name");
        check(hub.contains("d.newThisWeek") && hub.contains("d.total"),
                "desk cards surface routed volume and freshness");
        check(hub.contains("/desks/"), "each desk links to its detail feed");

        String detail = Files.readString(Path.of("src/main/resources/templates/desk-detail.html"));
        check(detail.contains("ops-sidebar :: sidebar('desks'"),
                "desk detail lives inside the ops console shell");
        check(detail.contains("item.routingNote"),
                "each routed story shows the router's recorded reason");
        check(detail.contains("item.labels"),
                "each routed story shows the categories that triggered routing");
        check(detail.contains("/report/story/"),
                "desk items bridge to the explained source-story reader when a fact exists");

        String sidebar = Files.readString(Path.of(
                "src/main/resources/templates/fragments/ops-sidebar.html"));
        check(sidebar.contains("href=\"/desks\""), "sidebar links to the desk hub");

        for (String bundle : new String[]{"src/main/resources/messages.properties",
                "src/main/resources/messages_vi.properties"}) {
            String messages = Files.readString(Path.of(bundle));
            for (Department dept : Department.values()) {
                check(messages.contains("ops.desks.dept." + dept.name() + "="),
                        bundle + " names every department desk (" + dept.name() + ")");
            }
        }
        System.out.println("DeskConsoleContractTest: ALL PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
