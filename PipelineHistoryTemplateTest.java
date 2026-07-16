import java.nio.file.Files;
import java.nio.file.Path;

/** Static contract for a scalable, compact pipeline-cycle history. */
public class PipelineHistoryTemplateTest {
    public static void main(String[] args) throws Exception {
        String template = Files.readString(Path.of("src/main/resources/templates/pipeline-history.html"));
        check(template.contains("id=\"ph-cycle-table\""), "cycle history uses a compact table");
        check(template.contains("data-cycle-row"), "cycle rows remain selectable");
        check(template.contains("Load older cycles"), "long histories reveal older cycles on demand");
        check(template.contains("visibleCycles = 12"), "initial history is bounded");
        check(!template.contains("ph-cycle-grid"), "large cycle-card grid is removed");
        System.out.println("PipelineHistoryTemplateTest: ALL PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
