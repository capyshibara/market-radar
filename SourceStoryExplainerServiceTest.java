import com.marketradar.report.SourceStoryExplainerService;

/** Standalone regression for the bilingual explained-rewrite layer. */
public class SourceStoryExplainerServiceTest {
    public static void main(String[] args) throws Exception {
        var parse = SourceStoryExplainerService.class.getDeclaredMethod("parseAndValidate", String.class);
        parse.setAccessible(true);
        var buildPrompt = SourceStoryExplainerService.class.getDeclaredMethod(
                "buildUserPrompt", String.class, String.class, String.class, String.class);
        buildPrompt.setAccessible(true);

        String good = """
                ```json
                {"rewriteEn":"An insurer in Vietnam announced a new rider. A rider is an optional add-on to a policy. It covers hospital cash. The announcement quotes the company statement.",
                 "rewriteVi":"Một doanh nghiệp bảo hiểm tại Việt Nam công bố một quyền lợi bổ sung mới. Quyền lợi bổ sung là phần mở rộng tùy chọn của hợp đồng. Nó chi trả tiền mặt nằm viện.",
                 "termsEn":["\\"rider\\" — an optional benefit attached to a base policy"],
                 "termsVi":["\\"rider\\" — quyền lợi tùy chọn gắn với hợp đồng gốc"]}
                ```""";
        var parsed = (Record) parse.invoke(null, good);
        check(component(parsed, "rewriteEn").startsWith("An insurer"),
                "code fences are stripped and rewriteEn parsed");
        check(component(parsed, "termsVi").contains("quyền lợi tùy chọn"),
                "terms arrays are joined into displayable lines");

        check(rejected(parse, "not json at all"), "non-JSON responses are rejected");
        check(rejected(parse, "{\"rewriteEn\":\"Only one language present.\"}"),
                "a missing rewriteVi is rejected, never silently stored");
        check(rejected(parse, """
                {"rewriteEn":"Sản phẩm của doanh nghiệp bảo hiểm được công bố trong tháng và cần đánh giá thị trường với khách hàng.",
                 "rewriteVi":"Một doanh nghiệp bảo hiểm tại Việt Nam công bố sản phẩm mới cần được đánh giá.",
                 "termsEn":[],"termsVi":[]}"""),
                "Vietnamese prose under the English label is rejected");
        check(rejected(parse, """
                {"rewriteEn":"An insurer announced a new product this month and the market should review the evidence for this signal and plan a launch.",
                 "rewriteVi":"The insurer launched a new product and the market should review this signal within the plan and the launch process for the customer.",
                 "termsEn":[],"termsVi":[]}"""),
                "English prose under the Vietnamese label is rejected");

        String longBody = "x".repeat(20_000);
        String prompt = (String) buildPrompt.invoke(null, "Title", "Publisher", "Span text", longBody);
        check(prompt.length() < 14_000, "oversized article text is capped before the writer call");
        check(prompt.contains("Span text"), "the cited evidence span always reaches the writer");

        System.out.println("SourceStoryExplainerServiceTest: ALL PASS");
    }

    private static boolean rejected(java.lang.reflect.Method parse, String raw) throws Exception {
        try {
            parse.invoke(null, raw);
            return false;
        } catch (java.lang.reflect.InvocationTargetException e) {
            return e.getCause() instanceof SourceStoryExplainerService.ExplainerRejectedException;
        }
    }

    private static String component(Record record, String name) throws Exception {
        var accessor = record.getClass().getDeclaredMethod(name);
        accessor.setAccessible(true);
        return (String) accessor.invoke(record);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
