package com.marketradar.quality;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic last-mile gate for Product intelligence. This class deliberately has
 * no framework or model dependency so the same rules can run in tests, backfills and
 * immediately before publication.
 */
public final class ProductPublicationQualityGate {

    public enum FailureCode {
        NO_CITATIONS,
        UNRESOLVED_EVIDENCE,
        WRONG_DEPARTMENT_AUDIENCE,
        EXTERNAL_ENTITY_AS_ACTION_OWNER,
        CHAPTER_CATEGORY_CONTAMINATION,
        ACTION_DEADLINE_PASSED,
        EFFECTIVE_DATE_PASSED,
        MISSING_PRODUCT_KIQ,
        TREND_INSUFFICIENT_EVENTS,
        TREND_INSUFFICIENT_SOURCES,
        MISSING_EVENT_PROVENANCE,
        MISSING_SOURCE_PROVENANCE,
        GENERIC_MONITOR_ACTION,
        MISSING_ACTION,
        LEGACY_VERSION_MIX,
        STALE_INPUT,
        SINGLE_SOURCE_WATCH,
        INSUFFICIENT_DECISION_READY_INSIGHTS
    }

    public enum Severity { WATCH, REJECT }
    public enum Disposition { DECISION_READY, WATCH, REJECT }
    /**
     * READY is the full decision brief threshold. WATCH_BRIEF is intentionally a
     * different editorial product: it may surface one or two safe, grounded
     * signals but must not be represented as a corroborated market conclusion.
     */
    public enum MagazineStatus { READY, WATCH_BRIEF, INSUFFICIENT_EVIDENCE }

    public record Finding(FailureCode code, Severity severity, String field, String detail) {
        public Finding {
            if (code == null || severity == null) throw new IllegalArgumentException("code and severity are required");
            field = value(field);
            detail = value(detail);
        }
    }

    public record InsightCandidate(
            String insightId,
            String department,
            String audience,
            String actionOwner,
            Set<String> externalEntityNames,
            Set<String> kiqCodes,
            String themeCode,
            String eventType,
            Set<String> citedEvidenceIds,
            Set<String> resolvedEvidenceIds,
            Set<String> eventIds,
            Set<String> sourceIds,
            String patternText,
            String nowWhat,
            boolean claimsTrend,
            boolean futureAction,
            LocalDate actionDeadline,
            LocalDate effectiveDate,
            LocalDate asOfDate,
            LocalDate windowStart,
            LocalDate windowEnd,
            Set<LocalDate> publishedDates,
            Set<String> modelVersions,
            Set<String> pipelineVersions) {
        public InsightCandidate {
            insightId = value(insightId);
            department = value(department);
            audience = value(audience);
            actionOwner = value(actionOwner);
            externalEntityNames = strings(externalEntityNames);
            kiqCodes = strings(kiqCodes);
            themeCode = value(themeCode);
            eventType = value(eventType);
            citedEvidenceIds = strings(citedEvidenceIds);
            resolvedEvidenceIds = strings(resolvedEvidenceIds);
            eventIds = strings(eventIds);
            sourceIds = strings(sourceIds);
            patternText = value(patternText);
            nowWhat = value(nowWhat);
            publishedDates = publishedDates == null ? Set.of() : Set.copyOf(publishedDates);
            modelVersions = strings(modelVersions);
            pipelineVersions = strings(pipelineVersions);
        }
    }

    public record InsightResult(
            String insightId,
            Disposition disposition,
            double resolvedEvidenceRatio,
            List<Finding> findings) {
        public InsightResult {
            findings = findings == null ? List.of() : List.copyOf(findings);
        }

        public Set<FailureCode> failureCodes() {
            LinkedHashSet<FailureCode> codes = new LinkedHashSet<>();
            findings.forEach(finding -> codes.add(finding.code()));
            return Set.copyOf(codes);
        }
    }

    public record MagazineResult(
            MagazineStatus status,
            int totalInsights,
            int decisionReadyInsights,
            int watchInsights,
            int rejectedInsights,
            List<Finding> findings,
            List<InsightResult> insightResults) {
        public MagazineResult {
            findings = findings == null ? List.of() : List.copyOf(findings);
            insightResults = insightResults == null ? List.of() : List.copyOf(insightResults);
        }
    }

    private static final Set<String> PRODUCT_AUDIENCES = Set.of("PRODUCT", "OUR_COMPANY", "INTERNAL_PRODUCT");
    private static final Set<String> PRODUCT_KIQS = Set.of(
            "KIQ_1_OFFER_CHANGE", "KIQ_2_MARKET_PATTERN", "KIQ_3_REGULATORY_RESPONSE",
            "KIQ_4_TRANSFERABLE_INNOVATION", "KIQ_5_CUSTOMER_NEED", "KIQ_6_CHANGE_OVER_TIME",
            "KIQ_7_COUNTER_EVIDENCE", "P-KIQ-01", "P-KIQ-02", "P-KIQ-03", "P-KIQ-04",
            "P-KIQ-05", "P-KIQ-06", "P-KIQ-07");
    private static final Map<String, Set<String>> ALLOWED_EVENT_TYPES = Map.of(
            "VN_PRODUCT_OFFER", Set.of("PRODUCT_LAUNCH", "PRODUCT_CHANGE", "BENEFIT_CHANGE", "PRICING_CHANGE", "PRODUCT_WITHDRAWAL"),
            "VN_REGULATORY_CHANGE", Set.of("REGULATORY_CHANGE"),
            "DISTRIBUTION_INNOVATION", Set.of("DISTRIBUTION_CHANGE", "SERVICE_EXPERIENCE_CHANGE"),
            "REGIONAL_TRANSFER", Set.of("PRODUCT_LAUNCH", "PRODUCT_CHANGE", "BENEFIT_CHANGE", "PRICING_CHANGE",
                    "PRODUCT_WITHDRAWAL", "DISTRIBUTION_CHANGE", "SERVICE_EXPERIENCE_CHANGE", "CUSTOMER_NEED_SIGNAL"),
            "MARKET_PATTERN", Set.of("PRODUCT_LAUNCH", "PRODUCT_CHANGE", "BENEFIT_CHANGE", "PRICING_CHANGE",
                    "PRODUCT_WITHDRAWAL", "CUSTOMER_NEED_SIGNAL", "COMPETITIVE_PERFORMANCE"));
    private static final Set<String> DIRECTIVE_TERMS = Set.of(
            " should ", " must ", " needs to ", " need to ", " nen ", " can ", " phai ");
    private static final Set<String> MONITOR_TERMS = Set.of(
            " monitor", "watch", " track", "theo doi", "quan sat");
    private static final Set<String> DECISION_TERMS = Set.of(
            "investigate", "validate", "prototype", "compare", "decide", "stop", "quantify", "map ",
            "review", "assess", "test ", "audit", "calculate", "evaluate", "pilot", "measure",
            "danh gia", "xac dinh", "lap ", "ra soat", "thu nghiem", "so sanh", "quyet dinh",
            "kiem tra", "do luong", "tinh toan");

    private ProductPublicationQualityGate() {}

    public static InsightResult evaluate(InsightCandidate candidate) {
        if (candidate == null) throw new IllegalArgumentException("candidate is required");
        List<Finding> findings = new ArrayList<>();

        double resolutionRatio = evidence(candidate, findings);
        audience(candidate, findings);
        chapter(candidate, findings);
        temporal(candidate, findings);
        kiq(candidate, findings);
        provenance(candidate, findings);
        action(candidate, findings);
        freshnessAndVersions(candidate, findings);

        Disposition disposition = findings.stream().anyMatch(f -> f.severity() == Severity.REJECT)
                ? Disposition.REJECT
                : findings.stream().anyMatch(f -> f.severity() == Severity.WATCH)
                ? Disposition.WATCH : Disposition.DECISION_READY;
        return new InsightResult(candidate.insightId(), disposition, resolutionRatio, findings);
    }

    public static MagazineResult evaluateMagazine(List<InsightCandidate> candidates) {
        List<InsightResult> results = candidates == null ? List.of() : candidates.stream()
                .map(ProductPublicationQualityGate::evaluate).toList();
        int ready = count(results, Disposition.DECISION_READY);
        int watch = count(results, Disposition.WATCH);
        int rejected = count(results, Disposition.REJECT);
        int safe = ready + watch;
        MagazineStatus status = ready >= 3 ? MagazineStatus.READY
                : safe >= 1 ? MagazineStatus.WATCH_BRIEF
                : MagazineStatus.INSUFFICIENT_EVIDENCE;
        List<Finding> findings = status == MagazineStatus.READY ? List.of() : List.of(new Finding(
                FailureCode.INSUFFICIENT_DECISION_READY_INSIGHTS,
                status == MagazineStatus.WATCH_BRIEF ? Severity.WATCH : Severity.REJECT,
                "magazine.insights",
                status == MagazineStatus.WATCH_BRIEF
                        ? "publishes as a Current Watch Brief: " + safe
                        + " safe grounded insight(s), " + ready + " decision-ready; requires 3 decision-ready insights for a Decision Brief"
                        : "requires at least 1 safe grounded WATCH or DECISION_READY insight; found 0"));
        return new MagazineResult(status,
                results.size(), ready, watch, rejected, findings, results);
    }

    private static double evidence(InsightCandidate candidate, List<Finding> findings) {
        if (candidate.citedEvidenceIds().isEmpty()) {
            reject(findings, FailureCode.NO_CITATIONS, "citedEvidenceIds", "at least one citation is required");
            return 0.0;
        }
        long resolved = candidate.citedEvidenceIds().stream().filter(candidate.resolvedEvidenceIds()::contains).count();
        double ratio = resolved / (double) candidate.citedEvidenceIds().size();
        if (resolved != candidate.citedEvidenceIds().size()) {
            Set<String> missing = new LinkedHashSet<>(candidate.citedEvidenceIds());
            missing.removeAll(candidate.resolvedEvidenceIds());
            reject(findings, FailureCode.UNRESOLVED_EVIDENCE, "citedEvidenceIds", "unresolved=" + missing);
        }
        return ratio;
    }

    private static void audience(InsightCandidate candidate, List<Finding> findings) {
        String department = upper(candidate.department());
        String audience = upper(candidate.audience());
        if (!"PRODUCT".equals(department) || !PRODUCT_AUDIENCES.contains(audience)) {
            reject(findings, FailureCode.WRONG_DEPARTMENT_AUDIENCE, "audience",
                    "department=" + candidate.department() + ", audience=" + candidate.audience());
        }
        String owner = normalize(candidate.actionOwner());
        String action = " " + normalize(candidate.nowWhat()) + " ";
        for (String external : candidate.externalEntityNames()) {
            String normalizedExternal = normalize(external);
            if (normalizedExternal.isBlank()) continue;
            if (owner.equals(normalizedExternal)
                    || (action.contains(normalizedExternal) && containsAny(action, DIRECTIVE_TERMS))) {
                reject(findings, FailureCode.EXTERNAL_ENTITY_AS_ACTION_OWNER, "nowWhat",
                        "external entity cannot own an internal Product recommendation: " + external);
                break;
            }
        }
    }

    private static void chapter(InsightCandidate candidate, List<Finding> findings) {
        Set<String> allowed = ALLOWED_EVENT_TYPES.get(upper(candidate.themeCode()));
        if (allowed == null || !allowed.contains(upper(candidate.eventType()))) {
            reject(findings, FailureCode.CHAPTER_CATEGORY_CONTAMINATION, "themeCode/eventType",
                    candidate.themeCode() + " cannot contain " + candidate.eventType());
        }
    }

    private static void temporal(InsightCandidate candidate, List<Finding> findings) {
        if (!candidate.futureAction() || candidate.asOfDate() == null) return;
        if (before(candidate.actionDeadline(), candidate.asOfDate())) {
            reject(findings, FailureCode.ACTION_DEADLINE_PASSED, "actionDeadline",
                    "deadline " + candidate.actionDeadline() + " is before as-of " + candidate.asOfDate());
        }
        if (before(candidate.effectiveDate(), candidate.asOfDate())) {
            reject(findings, FailureCode.EFFECTIVE_DATE_PASSED, "effectiveDate",
                    "effective date " + candidate.effectiveDate() + " is before as-of " + candidate.asOfDate());
        }
    }

    private static void kiq(InsightCandidate candidate, List<Finding> findings) {
        boolean productKiq = candidate.kiqCodes().stream().map(ProductPublicationQualityGate::upper)
                .anyMatch(PRODUCT_KIQS::contains);
        if (!productKiq) reject(findings, FailureCode.MISSING_PRODUCT_KIQ, "kiqCodes", "a Product KIQ is required");
    }

    private static void provenance(InsightCandidate candidate, List<Finding> findings) {
        if (candidate.eventIds().isEmpty()) {
            reject(findings, FailureCode.MISSING_EVENT_PROVENANCE, "eventIds", "at least one market event is required");
        }
        if (candidate.sourceIds().isEmpty()) {
            reject(findings, FailureCode.MISSING_SOURCE_PROVENANCE, "sourceIds", "at least one source is required");
        }
        // The synthesis adapter must set this structured flag. Inferring it from prose
        // would turn "not a trend" caveats into false trend claims.
        boolean trend = candidate.claimsTrend();
        if (trend && candidate.eventIds().size() < 2) {
            reject(findings, FailureCode.TREND_INSUFFICIENT_EVENTS, "eventIds", "a trend requires at least 2 independent events");
        }
        if (trend && candidate.sourceIds().size() < 2) {
            reject(findings, FailureCode.TREND_INSUFFICIENT_SOURCES, "sourceIds", "a trend requires at least 2 independent sources");
        } else if (!candidate.sourceIds().isEmpty() && candidate.sourceIds().size() < 2) {
            watch(findings, FailureCode.SINGLE_SOURCE_WATCH, "sourceIds", "single-source insight must remain WATCH");
        }
    }

    private static void action(InsightCandidate candidate, List<Finding> findings) {
        String action = normalize(candidate.nowWhat());
        if (action.isBlank()) {
            reject(findings, FailureCode.MISSING_ACTION, "nowWhat", "a decision-oriented Product action is required");
            return;
        }
        if (containsAny(action, MONITOR_TERMS) && !containsAny(action, DECISION_TERMS)) {
            reject(findings, FailureCode.GENERIC_MONITOR_ACTION, "nowWhat", "monitor/watch/track alone is not decision-ready");
        }
    }

    private static void freshnessAndVersions(InsightCandidate candidate, List<Finding> findings) {
        if (candidate.publishedDates().isEmpty()) {
            reject(findings, FailureCode.STALE_INPUT, "publishedDates", "publication dates are required for freshness checks");
        } else if (candidate.windowStart() != null && candidate.windowEnd() != null
                && candidate.publishedDates().stream().anyMatch(date -> date == null
                || date.isBefore(candidate.windowStart()) || date.isAfter(candidate.windowEnd()))) {
            reject(findings, FailureCode.STALE_INPUT, "publishedDates",
                    "all evidence must be inside " + candidate.windowStart() + " through " + candidate.windowEnd());
        }
        if (invalidVersions(candidate.modelVersions()) || invalidVersions(candidate.pipelineVersions())) {
            reject(findings, FailureCode.LEGACY_VERSION_MIX, "modelVersions/pipelineVersions",
                    "versions must be present, current, and homogeneous");
        }
    }

    private static boolean invalidVersions(Set<String> versions) {
        if (versions.isEmpty() || versions.size() > 1) return true;
        String normalized = normalize(versions.iterator().next());
        return normalized.contains("legacy") || normalized.contains("unknown") || normalized.contains("gpt-4o-mini");
    }

    private static int count(List<InsightResult> results, Disposition disposition) {
        return (int) results.stream().filter(result -> result.disposition() == disposition).count();
    }

    private static boolean before(LocalDate candidate, LocalDate reference) {
        return candidate != null && candidate.isBefore(reference);
    }

    private static void reject(List<Finding> findings, FailureCode code, String field, String detail) {
        findings.add(new Finding(code, Severity.REJECT, field, detail));
    }

    private static void watch(List<Finding> findings, FailureCode code, String field, String detail) {
        findings.add(new Finding(code, Severity.WATCH, field, detail));
    }

    private static boolean containsAny(String text, Collection<String> needles) {
        return needles.stream().anyMatch(text::contains);
    }

    private static String upper(String input) {
        return value(input).toUpperCase(Locale.ROOT);
    }

    private static String normalize(String input) {
        return java.text.Normalizer.normalize(value(input).toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('đ', 'd')
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static String value(String input) {
        return input == null ? "" : input.strip();
    }

    private static Set<String> strings(Set<String> input) {
        if (input == null || input.isEmpty()) return Set.of();
        LinkedHashSet<String> copy = new LinkedHashSet<>();
        input.stream().map(ProductPublicationQualityGate::value).filter(v -> !v.isBlank()).forEach(copy::add);
        return Set.copyOf(copy);
    }
}
