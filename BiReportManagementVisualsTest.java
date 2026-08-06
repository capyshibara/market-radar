import com.marketradar.product.ProductMarketScope;
import com.marketradar.report.bi.BiFinding;
import com.marketradar.report.bi.BiPage;
import com.marketradar.report.bi.BiReportContent;
import com.marketradar.report.bi.BiReportPageBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Standalone regression for the CFO-facing dashboard, observed calendar and dossier layout. */
public class BiReportManagementVisualsTest {
    public static void main(String[] args) throws Exception {
        BiFinding event = finding(BiFinding.COMPANY_EVENT, "AIA Việt Nam",
                "Doanh nghiệp công bố hoạt động mới; (Pattern: two facts)",
                "The company announced a digital#first initiative; (Pattern: two facts)",
                LocalDate.of(2026, 7, 20));
        BiFinding deepOne = finding(BiFinding.DEEP_DIVE, "AIA Việt Nam",
                "Hàm ý: cần theo dõi khả năng triển khai.",
                "Implication: monitor execution capacity.", null);
        BiFinding deepTwo = finding(BiFinding.DEEP_DIVE, "AIA Việt Nam",
                "Giới hạn: chưa có số liệu kết quả.",
                "Caveat: outcome data is not yet available.", null);
        BiReportContent content = new BiReportContent("Report", "Jul 2026", "Techcom Life",
                "06 Aug 2026", 3, List.of(event, deepOne, deepTwo), List.of(), List.of());

        Map<String, Object> model = BiReportPageBuilder.toTemplateModel(content, false);
        @SuppressWarnings("unchecked")
        List<BiPage> pages = (List<BiPage>) model.get("pages");
        check(pages.stream().anyMatch(p -> "SIGNAL_DASHBOARD".equals(p.type())),
                "management dashboard is always planned for a non-empty report");
        check(pages.stream().anyMatch(p -> "ACTIVITY_CALENDAR".equals(p.type())),
                "dated company evidence creates an observed activity calendar");
        check(pages.stream().filter(p -> "DEEP_DIVE".equals(p.type())).count() == 1,
                "deep-dive sentences sharing a subject form one dossier page");

        @SuppressWarnings("unchecked")
        Map<String, List<List<BiFinding>>> rows =
                (Map<String, List<List<BiFinding>>>) model.get("deepDiveRows");
        check(rows.size() == 1 && rows.values().iterator().next().get(0).size() == 2,
                "dossier retains both separately verified sentences");
        check(event.text(false).contains("digital-first") && !event.text(false).contains("Pattern:"),
                "reader copy restores token dashes and removes prompt scaffolding");

        BiFinding untranslated = new BiFinding(BiFinding.MACRO_ECONOMIC, null,
                "Nội dung tiếng Việt", null, false, List.of(), ProductMarketScope.VIETNAM, "Vietnam");
        check(!untranslated.text(false).contains("Nội dung tiếng Việt"),
                "English edition never silently falls back to a Vietnamese sentence");
        BiFinding mixedVi = finding(BiFinding.DEEP_DIVE, "AIA Việt Nam",
                "Caveat: các facts chưa có evidence từ verifier.",
                "Caveat: independent outcome evidence is not yet available.", null);
        check(mixedVi.text(true).startsWith("Giới hạn bằng chứng:")
                        && mixedVi.text(true).contains("các dữ kiện")
                        && mixedVi.text(true).contains("bằng chứng từ bộ kiểm chứng"),
                "Vietnamese edition localizes leaked workflow vocabulary without changing facts");

        String template = Files.readString(Path.of("src/main/resources/templates/bi-report.html"));
        check(template.contains("SIGNAL_DASHBOARD") && template.contains("ACTIVITY_CALENDAR")
                        && template.contains("deepDiveRows"),
                "PDF/web template renders all management visual structures");
        System.out.println("BiReportManagementVisualsTest: ALL PASS");
    }

    private static BiFinding finding(String bucket, String subject, String vi, String en,
                                     LocalDate date) {
        return new BiFinding(bucket, subject, vi, en, true, List.of(), null, null,
                ProductMarketScope.VIETNAM, "Vietnam", null, null, null, null, null,
                date, date, "DECISION_GRADE");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
