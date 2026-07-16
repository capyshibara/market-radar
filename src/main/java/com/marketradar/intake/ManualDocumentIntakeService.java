package com.marketradar.intake;

import com.marketradar.domain.RawDoc;
import com.marketradar.domain.Source;
import com.marketradar.fetch.SafeFetcher;
import com.marketradar.parse.ContentParsers;
import com.marketradar.repo.RawDocRepository;
import com.marketradar.repo.SourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;

@Service
public class ManualDocumentIntakeService {
    private final RawDocRepository rawDocs;
    private final SourceRepository sources;
    private final ContentParsers parsers;
    private final SafeFetcher fetcher;

    public ManualDocumentIntakeService(RawDocRepository rawDocs, SourceRepository sources,
                                       ContentParsers parsers, SafeFetcher fetcher) {
        this.rawDocs = rawDocs;
        this.sources = sources;
        this.parsers = parsers;
        this.fetcher = fetcher;
    }

    public Result importUrl(String sourceUrl) {
        String url = ManualDocumentRules.directImportUrl(sourceUrl);
        try {
            SafeFetcher.FetchResult fetched = fetcher.fetchDocument(url);
            boolean pdf = "application/pdf".equalsIgnoreCase(fetched.contentType());
            ContentParsers.ParsedText parsed = pdf
                    ? parsers.parsePdf(fetched.body()) : parsers.parseArticleHtml(fetched.body());
            String filename = filenameFromUrl(url, pdf ? "document.pdf" : null);
            DocumentMetadataDetector.Metadata metadata = pdf
                    ? DocumentMetadataDetector.pdf(fetched.body(), parsed.text(), filename)
                    : DocumentMetadataDetector.html(fetched.body(), parsed.text(), parsed.title(), url);
            ManualDocumentRules.Submission input = ManualDocumentRules.validateDetected(
                    metadata.title(), metadata.publisher(), url, metadata.publishedDate(),
                    metadata.language(), parsed.text());
            return store(input, RawDoc.IntakeMethod.MANUAL_TEXT, filename,
                    "URL_IMPORT | contentType=" + fetched.contentType(), pdf ? "PDF" : "article");
        } catch (SafeFetcher.FetchRejectedException rejected) {
            throw new ManualDocumentRules.ValidationException("Could not import this URL: " + rejected.getMessage());
        } catch (ContentParsers.ParseFailedException parseError) {
            throw new ManualDocumentRules.ValidationException("The linked document could not be read: " + parseError.getMessage());
        }
    }

    public Result submitFile(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ManualDocumentRules.ValidationException("Choose a PDF or TXT file.");
        if (file.getSize() > ManualDocumentRules.MAX_FILE_BYTES) {
            throw new ManualDocumentRules.ValidationException("File exceeds the 10 MB safety limit.");
        }
        String filename = file.getOriginalFilename() == null ? "uploaded-file" : file.getOriginalFilename().strip();
        try {
            byte[] bytes = file.getBytes();
            boolean pdf = isPdf(bytes, filename);
            String text;
            DocumentMetadataDetector.Metadata metadata;
            if (pdf) {
                text = parsers.parsePdf(bytes).text();
                metadata = DocumentMetadataDetector.pdf(bytes, text, filename);
            } else if (isText(bytes, filename)) {
                text = new String(bytes, StandardCharsets.UTF_8).strip();
                metadata = DocumentMetadataDetector.text(text, filename);
            } else {
                throw new ManualDocumentRules.ValidationException("Only text-based PDF and UTF-8 TXT files are supported.");
            }
            String hash = sha256(text);
            String artifactHash = sha256(bytes);
            String internalReference = "urn:manual-upload:" + artifactHash;
            ManualDocumentRules.Submission input = ManualDocumentRules.validateDetected(
                    metadata.title(), metadata.publisher(), internalReference,
                    metadata.publishedDate(), metadata.language(), text);
            return store(input, RawDoc.IntakeMethod.FILE_UPLOAD, filename,
                    "FILE_UPLOAD | artifactSha256=" + artifactHash
                            + " | no external source URL supplied", pdf ? "PDF" : "TXT");
        } catch (ContentParsers.ParseFailedException e) {
            throw new ManualDocumentRules.ValidationException("The PDF could not be read: " + e.getMessage());
        } catch (java.io.IOException e) {
            throw new ManualDocumentRules.ValidationException("The uploaded file could not be read.");
        }
    }

    private Result store(ManualDocumentRules.Submission input, RawDoc.IntakeMethod method,
                         String filename, String auditPrefix, String type) {
        String hash = sha256(input.text());
        if (rawDocs.existsByContentHash(hash)) return new Result(null, true,
                "This exact content already exists, so no duplicate was added.");
        Source source = sourceFor(input);
        RawDoc doc = new RawDoc(source, input.sourceUrl(), input.title(),
                input.publishedDate() == null ? null
                        : input.publishedDate().atStartOfDay().toInstant(ZoneOffset.UTC),
                Instant.now(), hash, input.text(), input.language(), RawDoc.ParseStatus.OK,
                auditPrefix + " | publisher=" + input.publisher());
        doc.markFullTextAvailable();
        doc.setIntakeMethod(method);
        doc.setPublisherName(input.publisher());
        doc.setOriginalFilename(filename);
        RawDoc saved = rawDocs.save(doc);
        String date = input.publishedDate() == null ? "date not detected" : input.publishedDate().toString();
        return new Result(saved.getId(), false, "Added " + type + " · " + input.publisher()
                + " · " + date + " · " + input.text().length() + " characters. Run Classify, then Extract.");
    }

    private Source sourceFor(ManualDocumentRules.Submission input) {
        java.net.URI uri = null;
        if (input.sourceUrl().startsWith("https://")) {
            try { uri = java.net.URI.create(input.sourceUrl()); } catch (Exception ignored) {}
        }
        if (uri != null && uri.getHost() != null) {
            var registered = sources.findFirstByAllowedHostIgnoreCase(uri.getHost());
            if (registered.isPresent()) return registered.get();
        }
        String code = publisherCode(input.publisher());
        java.net.URI finalUri = uri;
        return sources.findByCode(code).orElseGet(() -> {
            String fetchUrl = finalUri == null ? "https://manual-input.invalid/" : input.sourceUrl();
            String host = finalUri == null ? "manual-input.invalid" : finalUri.getHost();
            Source.SourceType type = input.sourceUrl().toLowerCase(Locale.ROOT).contains(".pdf")
                    ? Source.SourceType.PDF : Source.SourceType.HTML;
            Source source = new Source(code, input.publisher(), fetchUrl, host, type, 3, input.language());
            source.setActive(false);
            source.setUrlUnverified(finalUri == null);
            return sources.save(source);
        });
    }

    private static String publisherCode(String publisher) {
        String ascii = java.text.Normalizer.normalize(publisher, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (ascii.isBlank()) ascii = "UPLOADED";
        if (ascii.length() > 22) ascii = ascii.substring(0, 22).replaceAll("_+$", "");
        return "MANUAL_" + ascii + "_" + sha256(publisher).substring(0, 6).toUpperCase(Locale.ROOT);
    }

    private static boolean isPdf(byte[] bytes, String filename) {
        return bytes.length >= 5 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D'
                && bytes[3] == 'F' && bytes[4] == '-'
                || filename.toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    private static boolean isText(byte[] bytes, String filename) {
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".txt")) return false;
        for (byte value : bytes) if (value == 0) return false;
        return true;
    }

    private static String filenameFromUrl(String url, String fallback) {
        try {
            String path = java.net.URI.create(url).getPath();
            String name = path == null ? null : path.substring(path.lastIndexOf('/') + 1);
            return name == null || name.isBlank() ? fallback : name;
        } catch (Exception ignored) { return fallback; }
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value));
        } catch (Exception impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }

    public record Result(Long rawDocId, boolean duplicate, String message) {}
}
