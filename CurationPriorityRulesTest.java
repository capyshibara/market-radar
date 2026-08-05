import com.marketradar.domain.Department;
import com.marketradar.domain.IntelligenceTopic;
import com.marketradar.intelligence.CurationPriorityRules;

import java.util.Set;

public class CurationPriorityRulesTest {
    public static void main(String[] args) {
        var domesticRegulation = CurationPriorityRules.score(new CurationPriorityRules.Input(
                Department.STRATEGY, Set.of(IntelligenceTopic.REGULATION_POLICY), "VN", Set.of("VN"),
                100, 2, 3, true, true));
        var foreignBrand = CurationPriorityRules.score(new CurationPriorityRules.Input(
                Department.STRATEGY, Set.of(IntelligenceTopic.BRAND_REPUTATION), "VN", Set.of("US"),
                78, 1, 40, false, true));
        check(domesticRegulation.total() > foreignBrand.total(), "right information should surface first");
        check(domesticRegulation.band().equals("SURFACE_NOW"), "high-value domestic regulation surfaces now");

        var product = CurationPriorityRules.score(new CurationPriorityRules.Input(
                Department.PRODUCT, Set.of(IntelligenceTopic.PRODUCT_OFFER), "VN", Set.of("VN"),
                92, 1, 8, false, true));
        var compliance = CurationPriorityRules.score(new CurationPriorityRules.Input(
                Department.COMPLIANCE, Set.of(IntelligenceTopic.PRODUCT_OFFER), "VN", Set.of("VN"),
                92, 1, 8, false, true));
        check(product.audienceRelevance() > compliance.audienceRelevance(),
                "the same evidence can have a different departmental priority");

        var unsafe = CurationPriorityRules.score(new CurationPriorityRules.Input(
                Department.STRATEGY, Set.of(IntelligenceTopic.FINANCIAL_PERFORMANCE), "VN", Set.of("VN"),
                100, 3, 0, true, false));
        check(unsafe.total() == 0 && unsafe.band().equals("HOLD_ENTITY_REVIEW"),
                "wrong legal entity is never rescued by intelligent analysis");
        System.out.println("CurationPriorityRulesTest: ALL PASS");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
