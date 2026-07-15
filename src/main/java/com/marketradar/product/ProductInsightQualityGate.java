package com.marketradar.product;

import com.marketradar.domain.ClaimVerification;
import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.InterpretedClaim;
import com.marketradar.interpret.GroundingGateL1;
import com.marketradar.verify.EntailmentVerifier;
import com.marketradar.prompt.PromptKey;
import com.marketradar.prompt.PromptService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** L1 grounding plus the explicit independent-verifier integration boundary. */
@Service
public class ProductInsightQualityGate {

    public static final String QUALITY_VERSION = "product-quality-v1-l1-bilingual+l2-independent";

    public enum Status { VERIFIED, L1_REJECTED, VERIFIER_REQUIRED, L2_REJECTED }
    public record Result(Status status, String detail) {
        public boolean publishable() { return status == Status.VERIFIED; }
    }

    private final GroundingGateL1 grounding;
    private final EntailmentVerifier verifier;
    private final PromptService prompts;

    public ProductInsightQualityGate(GroundingGateL1 grounding, EntailmentVerifier verifier,
                                     PromptService prompts) {
        this.grounding = grounding;
        this.verifier = verifier;
        this.prompts = prompts;
    }

    public record Version(String contractVersion, String verifierProvider,
                          String verifyPromptSha256, String signature) {}

    public Version version() {
        String provider = verifier.providerName();
        String promptHash = sha256(prompts.body(PromptKey.VERIFY));
        return new Version(QUALITY_VERSION, provider, promptHash,
                sha256(QUALITY_VERSION + "\n" + provider + "\n" + promptHash));
    }

    public Result evaluate(ProductInsightWriter.WrittenInsight insight,
                           List<EvidenceFact> citedFacts, Set<String> availableCodes) {
        List<String> citedCodes = insight.citedFactCodes();
        List<String[]> factualPairs = List.of(
                new String[]{insight.headlineVi() + ". " + insight.whatVi(),
                        insight.headlineEn() + ". " + insight.whatEn()},
                new String[]{insight.patternVi(), insight.patternEn()},
                new String[]{insight.soWhatVi(), insight.soWhatEn()},
                new String[]{insight.caveatVi(), insight.caveatEn()});
        for (String[] pair : factualPairs) {
            GroundingGateL1.GateResult l1 = grounding.checkBilingual(
                    pair[0], pair[1], citedCodes, citedFacts, availableCodes);
            if (l1.status() != InterpretedClaim.GateStatus.PASS) {
                return new Result(Status.L1_REJECTED, l1.detailJson());
            }
        }

        // L2 checks the factual core. So-what/now-what remain explicitly identified
        // inference/action fields and are not misrepresented as entailed source facts.
        EntailmentVerifier.VerifyResult vi = verifier.verify(
                insight.headlineVi() + ". " + insight.whatVi() + " " + insight.patternVi(),
                citedFacts);
        EntailmentVerifier.VerifyResult en = verifier.verify(
                insight.headlineEn() + ". " + insight.whatEn() + " " + insight.patternEn(),
                citedFacts);
        if (vi.verdict() == ClaimVerification.Verdict.VERIFIER_ERROR
                || en.verdict() == ClaimVerification.Verdict.VERIFIER_ERROR
                || vi.verdict() == ClaimVerification.Verdict.NEUTRAL
                || en.verdict() == ClaimVerification.Verdict.NEUTRAL) {
            return new Result(Status.VERIFIER_REQUIRED,
                    "vi=" + vi.verdict() + "; en=" + en.verdict());
        }
        if (vi.verdict() != ClaimVerification.Verdict.ENTAILED
                || en.verdict() != ClaimVerification.Verdict.ENTAILED) {
            return new Result(Status.L2_REJECTED,
                    "vi=" + vi.verdict() + "; en=" + en.verdict());
        }
        return new Result(Status.VERIFIED, "L1 PASS; independent L2 ENTAILED (vi,en)");
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
