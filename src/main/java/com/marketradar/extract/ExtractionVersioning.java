package com.marketradar.extract;

import com.marketradar.domain.RawDoc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Pure version/signature rules shared by planning and execution. */
public final class ExtractionVersioning {

    public static final String PIPELINE_VERSION = "extract-facts-v3-chunked";

    private ExtractionVersioning() {}

    public static CurrentVersion current(String modelVersion, String promptBody) {
        String model = modelVersion == null || modelVersion.isBlank()
                ? "UNKNOWN" : modelVersion.strip();
        String prompt = promptBody == null ? "" : promptBody;
        return new CurrentVersion(PIPELINE_VERSION, model, sha256(prompt), prompt);
    }

    /** Input content is part of the signature, so a full-text backfill is automatically stale. */
    public static String signature(CurrentVersion version, RawDoc doc) {
        String contentHash = doc.getContentHash() == null ? "" : doc.getContentHash();
        return sha256(version.pipelineVersion() + "\n" + version.modelVersion() + "\n"
                + version.promptSha256() + "\n" + contentHash);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public record CurrentVersion(String pipelineVersion, String modelVersion,
                                 String promptSha256, String promptBody) {}
}
