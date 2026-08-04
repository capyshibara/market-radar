package com.marketradar.fetch;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

/** Offline regression for compressed HTTP bodies; performs no DNS or network I/O. */
public class SafeFetcherCompressionTest {
    public static void main(String[] args) throws Exception {
        byte[] original = ("<html><body>Thông tin bảo hiểm có dấu tiếng Việt.</body></html>\n")
                .repeat(40).getBytes(StandardCharsets.UTF_8);

        check(Arrays.equals(original, SafeFetcher.decodeCompressedBody(
                        compressGzip(original), "gzip", original.length + 1L, "https://example.com/gzip")),
                "gzip body is decoded byte-for-byte");
        check(Arrays.equals(original, SafeFetcher.decodeCompressedBody(
                        compressDeflate(original), "deflate", original.length + 1L, "https://example.com/deflate")),
                "deflate body is decoded byte-for-byte");

        expectRejected(() -> SafeFetcher.decodeCompressedBody(
                        compressGzip(original), "gzip", 100, "https://example.com/bomb"),
                "decoded body still obeys the post-decompression cap");
        expectRejected(() -> SafeFetcher.decodeCompressedBody(
                        original, "br", original.length + 1L, "https://example.com/brotli"),
                "unsupported Brotli fails loudly instead of returning mojibake");
        expectRejected(() -> SafeFetcher.decodeCompressedBody(
                        original, "gzip", original.length + 1L, "https://example.com/bad-gzip"),
                "invalid gzip fails loudly");

        System.out.println("SafeFetcherCompressionTest: ALL PASS");
    }

    private static byte[] compressGzip(byte[] input) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(input);
        }
        return out.toByteArray();
    }

    private static byte[] compressDeflate(byte[] input) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflate = new DeflaterOutputStream(out)) {
            deflate.write(input);
        }
        return out.toByteArray();
    }

    private static void expectRejected(ThrowingCall call, String message) throws Exception {
        try {
            call.run();
            throw new AssertionError("Failed: " + message);
        } catch (SafeFetcher.FetchRejectedException expected) {
            // expected
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError("Failed: " + message);
    }

    @FunctionalInterface
    private interface ThrowingCall { void run() throws Exception; }
}
