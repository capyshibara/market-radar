import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.InterpretedClaim.GateStatus;
import com.marketradar.interpret.GroundingGateL1;
import java.util.List;
import java.util.Set;

/** Regression tests for quoted generic references versus actual proper names. */
public class GroundingGateL1QuotedReferenceTest {
    public static void main(String[] args) {
        GroundingGateL1 gate = new GroundingGateL1();
        EvidenceFact fact = new EvidenceFact("F-1", null, EvidenceFact.FactType.REGULATION,
                "Bộ Tài chính ban hành Thông tư số 151/2025/TT-BTC.", "vi");

        var genericVi = gate.check("Việc áp dụng \"thủ tục này\" thay đổi."
                        , List.of("F-1"), List.of(fact), Set.of("F-1"));
        check(genericVi.status() == GateStatus.PASS,
                "Vietnamese generic demonstrative is not treated as a name");
        check(genericVi.detailJson().contains("ignoredQuotedReferences"),
                "ignored reference remains auditable");

        var genericEn = gate.check("Applying \"this procedure\" changes."
                        , List.of("F-1"), List.of(fact), Set.of("F-1"));
        check(genericEn.status() == GateStatus.PASS,
                "English generic demonstrative is not treated as a name");

        var missingCompany = gate.check("\"BIC Bình An\" launched a change."
                        , List.of("F-1"), List.of(fact), Set.of("F-1"));
        check(missingCompany.status() == GateStatus.FAIL_NAME_NOT_IN_EVIDENCE,
                "unsupported company-like name remains blocked");

        var missingRegulation = gate.check("\"Thông tư số 999/2026/TT-BTC\" applies."
                        , List.of("F-1"), List.of(fact), Set.of("F-1"));
        check(missingRegulation.status() == GateStatus.FAIL_NAME_NOT_IN_EVIDENCE,
                "unsupported regulation name remains blocked");

        System.out.println("GroundingGateL1QuotedReferenceTest: ALL PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
