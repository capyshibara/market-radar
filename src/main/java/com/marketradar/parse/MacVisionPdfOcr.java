package com.marketradar.parse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Zero-API-cost fallback for image-only statutory PDFs on the demo's macOS runtime.
 *
 * <p>The normal path remains PDFBox.  Only a PDF with no usable embedded text reaches
 * this helper.  A small bundled Swift program uses Apple's on-device Vision framework;
 * the source PDF never leaves the machine.  Other operating systems simply return an
 * empty result so the ingestion job can report an honest OCR-required failure.</p>
 */
final class MacVisionPdfOcr {
    private static final Logger log = LoggerFactory.getLogger(MacVisionPdfOcr.class);
    private static final Duration COMPILE_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration OCR_TIMEOUT = Duration.ofMinutes(5);
    private static final long MAX_OCR_TEXT_BYTES = 4L * 1024 * 1024;
    private static volatile Path cachedBinary;

    private MacVisionPdfOcr() {}

    static Optional<OcrText> extract(byte[] pdf, int pageCount) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("mac")) return Optional.empty();
        if (!Files.isExecutable(Path.of("/usr/bin/swiftc"))) return Optional.empty();
        Path input = null;
        Path output = null;
        Path error = null;
        try {
            Path binary = ensureBinary();
            if (binary == null) return Optional.empty();
            input = Files.createTempFile("market-radar-ocr-", ".pdf");
            output = Files.createTempFile("market-radar-ocr-", ".txt");
            error = Files.createTempFile("market-radar-ocr-", ".err");
            Files.write(input, pdf);
            int pages = Math.max(1, pageCount);
            Process process = new ProcessBuilder(binary.toString(), input.toString(), Integer.toString(pages))
                    .redirectOutput(output.toFile())
                    .redirectError(error.toFile())
                    .start();
            if (!process.waitFor(OCR_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("macOS Vision OCR timed out after {} seconds", OCR_TIMEOUT.toSeconds());
                return Optional.empty();
            }
            if (process.exitValue() != 0) {
                log.warn("macOS Vision OCR exited {}: {}", process.exitValue(), bounded(error, 2_000));
                return Optional.empty();
            }
            if (Files.size(output) > MAX_OCR_TEXT_BYTES) {
                log.warn("macOS Vision OCR output exceeded {} bytes", MAX_OCR_TEXT_BYTES);
                return Optional.empty();
            }
            String text = Files.readString(output, StandardCharsets.UTF_8).strip();
            if (text.length() < 200) return Optional.empty();
            return Optional.of(new OcrText(text, pages));
        } catch (Exception e) {
            log.warn("macOS Vision OCR unavailable: {}", e.getMessage());
            return Optional.empty();
        } finally {
            delete(input);
            delete(output);
            delete(error);
        }
    }

    private static Path ensureBinary() throws Exception {
        Path ready = cachedBinary;
        if (ready != null && Files.isExecutable(ready)) return ready;
        synchronized (MacVisionPdfOcr.class) {
            ready = cachedBinary;
            if (ready != null && Files.isExecutable(ready)) return ready;
            byte[] script;
            try (InputStream stream = MacVisionPdfOcr.class.getResourceAsStream("/ocr/MacVisionPdfOcr.swift")) {
                if (stream == null) throw new IllegalStateException("bundled OCR Swift source missing");
                script = stream.readAllBytes();
            }
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(script), 0, 8);
            Path directory = Path.of(System.getProperty("java.io.tmpdir"), "market-radar-ocr");
            Files.createDirectories(directory);
            Path binary = directory.resolve("pdf-ocr-" + digest);
            if (!Files.isExecutable(binary)) {
                Path source = directory.resolve("pdf-ocr-" + digest + ".swift");
                Path error = directory.resolve("pdf-ocr-" + digest + ".compile.log");
                Files.write(source, script);
                Process compiler = new ProcessBuilder("/usr/bin/swiftc", source.toString(), "-o", binary.toString())
                        .redirectErrorStream(true)
                        .redirectOutput(error.toFile())
                        .start();
                if (!compiler.waitFor(COMPILE_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                    compiler.destroyForcibly();
                    throw new IllegalStateException("Swift OCR helper compilation timed out");
                }
                if (compiler.exitValue() != 0) {
                    throw new IllegalStateException("Swift OCR helper compilation failed: " + bounded(error, 4_000));
                }
            }
            cachedBinary = binary;
            return binary;
        }
    }

    private static String bounded(Path path, int max) {
        try {
            String value = Files.readString(path, StandardCharsets.UTF_8).strip();
            return value.length() <= max ? value : value.substring(0, max);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static void delete(Path path) {
        if (path == null) return;
        try { Files.deleteIfExists(path); } catch (Exception ignored) {}
    }

    record OcrText(String text, int pages) {}
}
