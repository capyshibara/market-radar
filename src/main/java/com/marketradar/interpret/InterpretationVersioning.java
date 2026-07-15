package com.marketradar.interpret;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Pure, deterministic identity rules for append-only interpretation editions. */
public final class InterpretationVersioning {
    private InterpretationVersioning() {}

    /** Bump when parsing/gating semantics change even if model and prompt do not. */
    public static final String CONTRACT_VERSION = "interpretation-v2";

    public record EditionKey(String signature, String inputHash) {
        public EditionKey {
            if (signature == null || signature.isBlank()) throw new IllegalArgumentException("signature is required");
            if (inputHash == null || inputHash.isBlank()) throw new IllegalArgumentException("inputHash is required");
        }
    }

    public static EditionKey key(String providerAndModel, String promptKey,
                                 String effectivePrompt, String renderedInput) {
        String signature = sha256(CONTRACT_VERSION + "\n" + value(providerAndModel)
                + "\n" + value(promptKey) + "\n" + value(effectivePrompt));
        return new EditionKey(signature, sha256(value(renderedInput)));
    }

    public static boolean isCurrent(String storedSignature, String storedInputHash, EditionKey current) {
        return current != null && current.signature().equals(storedSignature)
                && current.inputHash().equals(storedInputHash);
    }

    /** Failed/empty attempts are auditable but must never displace the prior good edition. */
    public static boolean shouldActivate(boolean schemaRejected, int sentenceCount) {
        return !schemaRejected && sentenceCount > 0;
    }

    private static String value(String value) { return value == null ? "" : value; }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
