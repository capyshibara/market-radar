package com.marketradar.classify;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Pure version and planning rules shared by classification execution and dry-run. */
public final class ClassificationVersioning {

    private ClassificationVersioning() {}

    public enum PlanAction {
        CLASSIFY_NEW,
        RECLASSIFY_STALE,
        SKIP_CURRENT,
        HOLD_FAILED_VERSION
    }

    public record CurrentVersion(String providerModel, String promptSha256,
                                 String contentSha256, String signature) {}

    public static CurrentVersion current(String providerModel, String effectivePromptContract,
                                         String contentSha256) {
        String provider = normalized(providerModel, "UNKNOWN");
        String content = normalized(contentSha256, "MISSING_CONTENT_HASH");
        String promptHash = sha256(effectivePromptContract == null ? "" : effectivePromptContract);
        return new CurrentVersion(provider, promptHash, content,
                sha256(provider + "\n" + promptHash + "\n" + content));
    }

    /** Legacy rows have null version columns and therefore never match accidentally. */
    public static boolean matches(String storedProviderModel, String storedPromptSha256,
                                  String storedContentSha256, String storedSignature,
                                  CurrentVersion desired) {
        return desired != null
                && desired.providerModel().equals(storedProviderModel)
                && desired.promptSha256().equals(storedPromptSha256)
                && desired.contentSha256().equals(storedContentSha256)
                && desired.signature().equals(storedSignature);
    }

    public static PlanAction plan(boolean hasActiveResult, boolean activeMatches,
                                  boolean failedDesiredVersion, boolean retryFailed) {
        if (activeMatches) return PlanAction.SKIP_CURRENT;
        if (failedDesiredVersion && !retryFailed) return PlanAction.HOLD_FAILED_VERSION;
        return hasActiveResult ? PlanAction.RECLASSIFY_STALE : PlanAction.CLASSIFY_NEW;
    }

    /** Only decisive output is allowed to displace an existing usable result. */
    public static boolean isPromotableStatus(String statusName) {
        return "CONFIRMED".equals(statusName) || "OUT_OF_SCOPE".equals(statusName);
    }

    public static boolean isFailureOutcome(String outcomeName) {
        return "ERROR".equals(outcomeName)
                || "PRESERVED_PRIOR_REVIEW".equals(outcomeName)
                || "CONCURRENT_CHANGE_PRESERVED".equals(outcomeName);
    }

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
