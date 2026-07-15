import com.marketradar.interpret.InterpretationVersioning;
import com.marketradar.interpret.NarrativeInputSelection;

import java.time.LocalDate;
import java.util.List;

/** Standalone regression suite for append-only interpretation edition identity. */
public class InterpretationVersioningTest {
    private static int checks;

    record Candidate(String id, LocalDate date) {}

    public static void main(String[] args) {
        var base = InterpretationVersioning.key("OPENAI_COMPAT(gpt-5-mini)",
                "INTERPRET_DOC", "prompt-v1", "evidence A");
        var same = InterpretationVersioning.key("OPENAI_COMPAT(gpt-5-mini)",
                "INTERPRET_DOC", "prompt-v1", "evidence A");
        check(base.equals(same), "same contract and input has stable key");
        check(InterpretationVersioning.isCurrent(base.signature(), base.inputHash(), same), "current edition matches");

        var modelChanged = InterpretationVersioning.key("ANTHROPIC(claude-sonnet)",
                "INTERPRET_DOC", "prompt-v1", "evidence A");
        var promptChanged = InterpretationVersioning.key("OPENAI_COMPAT(gpt-5-mini)",
                "INTERPRET_DOC", "prompt-v2", "evidence A");
        var inputChanged = InterpretationVersioning.key("OPENAI_COMPAT(gpt-5-mini)",
                "INTERPRET_DOC", "prompt-v1", "evidence B");
        check(!base.signature().equals(modelChanged.signature()), "model changes signature");
        check(!base.signature().equals(promptChanged.signature()), "prompt changes signature");
        check(base.signature().equals(inputChanged.signature()), "input does not masquerade as prompt/model version");
        check(!base.inputHash().equals(inputChanged.inputHash()), "changed evidence creates a new input edition");
        check(!InterpretationVersioning.isCurrent(base.signature(), base.inputHash(), inputChanged), "old input is not current");
        check(!InterpretationVersioning.shouldActivate(true, 0), "schema-rejected attempt keeps prior edition active");
        check(!InterpretationVersioning.shouldActivate(false, 0), "empty attempt keeps prior edition active");
        check(InterpretationVersioning.shouldActivate(false, 2), "persistable sentences can atomically activate new edition");

        LocalDate start = LocalDate.of(2026, 4, 17);
        LocalDate end = LocalDate.of(2026, 7, 15);
        var staleOnly = List.of(new Candidate("old", start.minusDays(1)));
        var selectedStale = NarrativeInputSelection.freshOnly(staleOnly,
                c -> !c.date().isBefore(start) && !c.date().isAfter(end));
        check(selectedStale.isEmpty(), "stale-only corpus yields no narrative, never fallback");
        var mixed = List.of(new Candidate("old", start.minusDays(1)), new Candidate("fresh", end.minusDays(2)));
        var selectedMixed = NarrativeInputSelection.freshOnly(mixed,
                c -> !c.date().isBefore(start) && !c.date().isAfter(end));
        check(selectedMixed.size() == 1 && selectedMixed.get(0).id().equals("fresh"), "only fresh input survives");

        System.out.println("InterpretationVersioningTest: " + checks + " checks passed");
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }
}
