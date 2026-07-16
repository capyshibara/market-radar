import com.marketradar.intake.ManualDocumentRules;
import java.time.LocalDate;

/** Standalone boundary regression for operator-supplied content. */
public class ManualDocumentRulesTest {
    public static void main(String[] args) {
        String text = "A".repeat(ManualDocumentRules.MIN_TEXT_CHARS);
        var accepted = ManualDocumentRules.validate("BCG report", "BCG", "https://www.bcg.com/report",
                LocalDate.now(), "en", text);
        check(accepted.text().length() == ManualDocumentRules.MIN_TEXT_CHARS, "accepts full text");
        expect(() -> ManualDocumentRules.validate("Title", "Publisher", "http://example.com",
                LocalDate.now(), "en", text));
        expect(() -> ManualDocumentRules.validate("Title", "Publisher", "https://example.com",
                LocalDate.now(), "en", "short"));
        expect(() -> ManualDocumentRules.validate("Title", "Publisher", "https://example.com",
                LocalDate.now(), "en", "A".repeat(ManualDocumentRules.MAX_TEXT_CHARS + 1)));
        System.out.println("ManualDocumentRulesTest: ALL PASS");
    }
    private static void expect(Runnable runnable) { try { runnable.run(); throw new AssertionError("Expected validation error"); } catch (ManualDocumentRules.ValidationException expected) {} }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
