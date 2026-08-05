package com.marketradar.review;

import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.InterpretedClaim;
import com.marketradar.intelligence.CompetitorRegistry;
import com.marketradar.intelligence.EntityResolutionRules;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic legal-entity attribution gate. Severe findings prevent
 * AUTO_APPROVED but remain visible for human correction/review.
 */
@Component
public class EntityAttributionGuard {

    public enum Code {
        CONFUSABLE_ENTITY,
        AMBIGUOUS_ENTITY,
        ENTITY_MISMATCH,
        MULTIPLE_ENTITIES,
        UNRESOLVED_NAMED_ENTITY,
        SUBJECT_NOT_IN_EVIDENCE
    }

    public record Warning(Code code, String competitor, String messageVi, String messageEn) {}

    private final CompetitorRegistry registry;

    public EntityAttributionGuard(CompetitorRegistry registry) { this.registry = registry; }

    public List<Warning> check(InterpretedClaim claim, List<EvidenceFact> citedFacts) {
        if (claim == null) return List.of();
        String claimText = value(claim.getTextVi()) + "\n" + value(claim.getTextEn());
        StringBuilder evidence = new StringBuilder();
        if (citedFacts != null) {
            for (EvidenceFact fact : citedFacts) {
                if (fact == null) continue;
                if (fact.getSpanText() != null) evidence.append(fact.getSpanText()).append('\n');
            }
        }
        // A surrounding headline is useful discovery context, but it is not part of
        // the exact cited span. Counting it here would let a claim attribute a number
        // to the headline company even when the cited paragraph names another entity.
        String evidenceText = evidence.toString();
        String sourceMarket = claim.getRawDoc() == null || claim.getRawDoc().getSource() == null
                ? null : claim.getRawDoc().getSource().getDefaultMarketCode();
        EntityResolutionRules.Resolution claimResolution = registry.resolveEntity(claimText, sourceMarket);

        List<Warning> warnings = new ArrayList<>();
        switch (claimResolution.status()) {
            case AMBIGUOUS -> warnings.add(new Warning(Code.AMBIGUOUS_ENTITY, null,
                    "Claim chỉ nêu tên thương hiệu, chưa đủ để xác định pháp nhân/thị trường.",
                    "The claim names a brand but not a unique legal entity/market."));
            case CONFLICT -> warnings.add(new Warning(Code.CONFUSABLE_ENTITY, null,
                    "Claim trộn các pháp nhân dễ nhầm cùng thương hiệu.",
                    "The claim mixes confusable legal entities sharing a brand."));
            case MULTIPLE -> warnings.add(new Warning(Code.MULTIPLE_ENTITIES, null,
                    "Claim nhắc nhiều pháp nhân; cần xác nhận đây là so sánh có chủ đích.",
                    "The claim names multiple legal entities; confirm this is an intentional comparison."));
            default -> { }
        }

        Set<String> evidenceEntityKeys = new LinkedHashSet<>();
        if (citedFacts != null) {
            for (EvidenceFact fact : citedFacts) {
                if (fact == null) continue;
                if (fact.getEntityResolutionStatus() == EntityResolutionRules.Status.CONFLICT) {
                    warnings.add(new Warning(Code.CONFUSABLE_ENTITY, fact.getSubjectEntityName(),
                            "Fact " + fact.getFactCode() + " chứa marker của các pháp nhân dễ nhầm.",
                            "Fact " + fact.getFactCode() + " contains markers for confusable legal entities."));
                } else if (fact.getEntityResolutionStatus() == EntityResolutionRules.Status.AMBIGUOUS) {
                    warnings.add(new Warning(Code.AMBIGUOUS_ENTITY, fact.getSubjectEntityName(),
                            "Fact " + fact.getFactCode() + " chưa xác định được pháp nhân duy nhất.",
                            "Fact " + fact.getFactCode() + " does not resolve to one legal entity."));
                } else if (fact.getEntityResolutionStatus() == EntityResolutionRules.Status.MULTIPLE) {
                    warnings.add(new Warning(Code.MULTIPLE_ENTITIES, fact.getSubjectEntityName(),
                            "Fact " + fact.getFactCode() + " nhắc nhiều pháp nhân; không được tự động quy về một bên.",
                            "Fact " + fact.getFactCode() + " names multiple entities and cannot be auto-attributed."));
                } else if (fact.getEntityResolutionStatus() == EntityResolutionRules.Status.UNRESOLVED
                        && fact.getCompany() != null && !fact.getCompany().isBlank()) {
                    warnings.add(new Warning(Code.UNRESOLVED_NAMED_ENTITY, fact.getCompany(),
                            "Fact " + fact.getFactCode() + " có tên công ty do model trích nhưng span chưa resolve được pháp nhân.",
                            "Fact " + fact.getFactCode() + " has an extracted company name but the cited span does not resolve a legal entity."));
                }
                if (fact.getSubjectEntityKey() != null) evidenceEntityKeys.add(fact.getSubjectEntityKey());
            }
        }
        EntityResolutionRules.Entity claimEntity = claimResolution.singleEntity();
        if (claimEntity != null && !evidenceEntityKeys.isEmpty()
                && !evidenceEntityKeys.contains(claimEntity.key())) {
            warnings.add(new Warning(Code.ENTITY_MISMATCH, claimEntity.canonicalName(),
                    "Claim quy kết cho " + claimEntity.canonicalName()
                            + " nhưng evidence resolve sang pháp nhân khác: " + evidenceEntityKeys + ".",
                    "The claim attributes " + claimEntity.canonicalName()
                            + " but evidence resolves to different entities: " + evidenceEntityKeys + "."));
        }

        // Compatibility warning for facts created before deterministic entity metadata existed.
        for (String competitor : registry.detectAllCompetitors(claimText)) {
            for (CompetitorRegistry.Confusable confusable : registry.confusableMarkersIn(competitor, evidenceText)) {
                warnings.add(new Warning(Code.CONFUSABLE_ENTITY, competitor,
                        "Bằng chứng có dấu hiệu nói về \"" + confusable.confusableName()
                                + "\" — không phải " + competitor + ".",
                        "Evidence appears to reference \"" + confusable.confusableName()
                                + "\" — not " + competitor + "."));
            }
            if (!evidenceText.isBlank() && !registry.mentions(competitor, evidenceText)) {
                warnings.add(new Warning(Code.SUBJECT_NOT_IN_EVIDENCE, competitor,
                        "Claim nhắc " + competitor + " nhưng không có bí danh nào trong evidence.",
                        "The claim mentions " + competitor + " but no alias appears in evidence."));
            }
        }
        return List.copyOf(warnings);
    }

    public boolean blocksAutoApproval(List<Warning> warnings) {
        // A named competitor absent from the cited evidence is exactly the CFO's
        // high-risk failure mode (for example Prudential plc vs Prudential USA).
        // Route it to a human; do not silently auto-publish it.
        return warnings != null && !warnings.isEmpty();
    }

    /** Shared fail-closed rule for publication/checkpoint code. Unresolved is safe
     * only for genuinely non-entity facts such as a macro market metric. */
    public static boolean isFactAttributionSafe(EvidenceFact fact) {
        if (fact == null) return false;
        EntityResolutionRules.Status status = fact.getEntityResolutionStatus();
        if (status == EntityResolutionRules.Status.RESOLVED) return true;
        if (status == EntityResolutionRules.Status.UNRESOLVED) {
            return (fact.getCompany() == null || fact.getCompany().isBlank())
                    && fact.getSubjectEntityKey() == null;
        }
        return false;
    }

    private static String value(String text) { return text == null ? "" : text; }
}
