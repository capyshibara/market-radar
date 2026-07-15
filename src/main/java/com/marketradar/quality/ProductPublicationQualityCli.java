package com.marketradar.quality;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.stream.Collectors;

/** Thin command adapter used by the checked-in publication-quality fixture evaluator. */
public final class ProductPublicationQualityCli {
    private ProductPublicationQualityCli() {}

    public static void main(String[] args) {
        if (args.length != 24) {
            System.err.println("expected 24 arguments, received " + args.length);
            System.exit(2);
        }
        var candidate = new ProductPublicationQualityGate.InsightCandidate(
                args[0], args[1], args[2], decode(args[3]), csv(decode(args[4])), csv(decode(args[5])),
                args[6], args[7], csv(decode(args[8])), csv(decode(args[9])), csv(decode(args[10])),
                csv(decode(args[11])), decode(args[12]), decode(args[13]), Boolean.parseBoolean(args[14]),
                Boolean.parseBoolean(args[15]), date(args[16]), date(args[17]), date(args[18]),
                date(args[19]), date(args[20]), dates(decode(args[21])), csv(decode(args[22])), csv(decode(args[23])));
        var result = ProductPublicationQualityGate.evaluate(candidate);
        String codes = result.findings().stream().map(finding -> finding.code().name()).distinct()
                .collect(Collectors.joining(","));
        System.out.printf("%s\t%s\t%.6f\t%s%n", result.insightId(), result.disposition(),
                result.resolvedEvidenceRatio(), codes);
    }

    private static String decode(String encoded) {
        return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    private static Set<String> csv(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Arrays.stream(value.split(",", -1)).map(String::strip).filter(v -> !v.isBlank()).collect(Collectors.toSet());
    }

    private static Set<LocalDate> dates(String value) {
        return csv(value).stream().map(LocalDate::parse).collect(Collectors.toSet());
    }

    private static LocalDate date(String value) {
        return value == null || value.isBlank() || "-".equals(value) ? null : LocalDate.parse(value);
    }
}
