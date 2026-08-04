package com.marketradar.report.bi;

import com.marketradar.product.ProductMarketScope;

import java.util.List;

/** Offline regression for deterministic Connector grouping and deep-dive nomination. */
public class ConnectorTest {
    public static void main(String[] args) {
        BiFinding companyA = finding(BiFinding.COMPANY_EVENT, "AIA");
        BiFinding companyB = finding(BiFinding.COMPANY_EVENT, "AIA");
        BiFinding aiA = finding(BiFinding.TECH_AI_SIGNAL, "AIA");
        BiFinding macroA = finding(BiFinding.MACRO_ECONOMIC, "AIA");
        BiFinding noSubject = finding(BiFinding.COMPANY_EVENT, null);

        var groups = Connector.groupByBucketAndSubject(
                List.of(companyA, companyB, aiA, macroA, noSubject), BiFinding.COMPANY_EVENT);
        check(groups.size() == 1, "only matching bucket + nonblank subject is grouped");
        check(groups.get(0).members().size() == 2, "same-subject company facts stay together");
        check(groups.get(0).bigEnoughForOwnPage(), "two company facts qualify for an own page");

        var candidates = Connector.proposeDeepDiveCandidates(
                List.of(companyA, companyB, aiA, macroA, noSubject));
        check(candidates.size() == 1, "cross-bucket AIA signals nominate one deep dive");
        check(candidates.get(0).sourceBuckets().size() == 2,
                "macro is excluded and the two material source buckets are retained");
        check(candidates.get(0).reason().contains("Xuyên 2 chủ đề"),
                "audit reason states why the candidate qualified");

        var tooThin = Connector.proposeDeepDiveCandidates(List.of(companyA, companyB));
        check(tooThin.isEmpty(), "two facts from only one bucket remain below the threshold");

        System.out.println("ConnectorTest: ALL PASS");
    }

    private static BiFinding finding(String bucket, String subject) {
        return new BiFinding(bucket, subject, "Nội dung", "Finding", false, List.of(),
                ProductMarketScope.VIETNAM, "Vietnam");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError("Failed: " + message);
    }
}
