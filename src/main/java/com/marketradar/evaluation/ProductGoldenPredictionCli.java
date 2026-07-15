package com.marketradar.evaluation;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.stream.Collectors;

/** Small dependency-free CLI used by the Python release evaluator. */
public final class ProductGoldenPredictionCli {

    private ProductGoldenPredictionCli() {}

    public static void main(String[] args) {
        if (args.length != 5) {
            throw new IllegalArgumentException(
                    "expected: caseId base64Title contentDepth base64Labels snapshotDate");
        }
        String title = decode(args[1]);
        String labelCsv = decode(args[3]);
        Set<String> labels = labelCsv.isBlank() ? Set.of() : Arrays.stream(labelCsv.split(","))
                .map(String::strip).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        var prediction = ProductGoldenPredictionAdapter.predict(
                new ProductGoldenPredictionAdapter.FixtureCase(
                        args[0], title, args[2], labels, LocalDate.parse(args[4])));

        System.out.println(String.join("\t",
                prediction.caseId(),
                Boolean.toString(prediction.departmentRelevant()),
                prediction.primaryEventType(),
                prediction.qualityDecision(),
                Integer.toString(prediction.materialityScore()),
                Boolean.toString(prediction.publishEligible()),
                Boolean.toString(prediction.evidenceEvaluable()),
                prediction.rulesVersion(),
                String.join(",", prediction.unavailableFields()),
                prediction.productKiqsCsv()));
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
