package com.marketradar.llm;

import java.net.URI;
import java.util.Locale;

/**
 * Kiểm tra Invariant #2 (Verifier ≠ Writer), dùng CHUNG cho:
 *  - VerifierClientFactory lúc khởi động app (fail loud, từ chối boot)
 *  - LlmSettingsController lúc đổi provider tại runtime (fail loud, từ chối apply)
 * Tách ra một chỗ để hai nơi không bao giờ lệch logic với nhau.
 */
public final class Invariant2 {

    private Invariant2() {}

    /** Ném IllegalStateException nếu writer và verifier bị coi là CÙNG họ model. */
    public static void assertDifferentFamily(SwitchableLlmClient.Config writer, SwitchableLlmClient.Config verifier) {
        boolean writerIsClaude = writer.kind() == SwitchableLlmClient.Kind.ANTHROPIC
                || startsWithClaude(writer.model());
        boolean verifierLooksClaude = verifier.kind() == SwitchableLlmClient.Kind.ANTHROPIC
                || startsWithClaude(verifier.model())
                || containsAnthropic(verifier.baseUrl());
        if (writerIsClaude && verifierLooksClaude) {
            throw new IllegalStateException(
                "VI PHẠM INVARIANT #2 (Verifier ≠ Writer): writer là ANTHROPIC nhưng verifier "
                + "cấu hình model/endpoint họ Claude (" + verifier.baseUrl() + ", " + verifier.model() + ").");
        }

        if (writer.kind() == SwitchableLlmClient.Kind.OPENAI_COMPAT
                && verifier.kind() == SwitchableLlmClient.Kind.OPENAI_COMPAT) {
            String writerHost = hostOf(writer.baseUrl());
            String verifierHost = hostOf(verifier.baseUrl());
            if (writerHost != null && writerHost.equalsIgnoreCase(verifierHost)) {
                throw new IllegalStateException(
                    "VI PHẠM INVARIANT #2 (Verifier ≠ Writer): writer và verifier cùng trỏ về "
                    + writerHost + " (" + writer.model() + " vs " + verifier.model() + "). "
                    + "Verifier phải là provider KHÁC HỌ.");
            }
        }
    }

    private static boolean startsWithClaude(String model) {
        return model != null && model.toLowerCase(Locale.ROOT).startsWith("claude");
    }

    private static boolean containsAnthropic(String url) {
        return url != null && url.toLowerCase(Locale.ROOT).contains("anthropic");
    }

    private static String hostOf(String url) {
        try { return URI.create(url).getHost(); } catch (Exception e) { return null; }
    }
}
