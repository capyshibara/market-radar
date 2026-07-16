package com.marketradar.intake;

import com.marketradar.domain.RawDoc;
import com.marketradar.domain.Source;
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

@Service
public class ManualDocumentIntakeService {
    private static final String MANUAL_SOURCE_CODE = "MANUAL_RESEARCH";
    private final RawDocRepository rawDocs;
    private final SourceRepository sources;
    private final ContentParsers parsers;

    public ManualDocumentIntakeService(RawDocRepository rawDocs, SourceRepository sources,
                                       ContentParsers parsers) {
        this.rawDocs = rawDocs;
        this.sources = sources;
        this.parsers = parsers;
    }

    public Result submitText(String title, String publisher, String sourceUrl,
                             java.time.LocalDate publishedDate, String language, String rawText) {
        ManualDocumentRules.Submission input = ManualDocumentRules.validate(
                title, publisher, sourceUrl, publishedDate, language, rawText);
        return store(input, RawDoc.IntakeMethod.MANUAL_TEXT, null, "MANUAL_TEXT");
    }

    public Result submitFile(String title, String publisher, String sourceUrl,
                             java.time.LocalDate publishedDate, String language, MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ManualDocumentRules.ValidationException("Choose a PDF or TXT file.");
        if (file.getSize() > ManualDocumentRules.MAX_FILE_BYTES) {
            throw new ManualDocumentRules.ValidationException("File exceeds the 10 MB safety limit.");
        }
        String filename = file.getOriginalFilename() == null ? "uploaded-file" : file.getOriginalFilename().strip();
        String lower = filename.toLowerCase(java.util.Locale.ROOT);
        String text;
        try {
            byte[] bytes = file.getBytes();
            if (lower.endsWith(".pdf")) {
                text = parsers.parsePdf(bytes).text();
            } else if (lower.endsWith(".txt")) {
                text = new String(bytes, StandardCharsets.UTF_8);
            } else {
                throw new ManualDocumentRules.ValidationException("Only PDF and UTF-8 TXT files are supported.");
            }
        } catch (ContentParsers.ParseFailedException e) {
            throw new ManualDocumentRules.ValidationException("The PDF could not be read: " + e.getMessage());
        } catch (java.io.IOException e) {
            throw new ManualDocumentRules.ValidationException("The uploaded file could not be read.");
        }
        ManualDocumentRules.Submission input = ManualDocumentRules.validate(
                title, publisher, sourceUrl, publishedDate, language, text);
        return store(input, RawDoc.IntakeMethod.FILE_UPLOAD, filename, "FILE_UPLOAD");
    }

    private Result store(ManualDocumentRules.Submission input, RawDoc.IntakeMethod method,
                         String filename, String auditPrefix) {
        String hash = sha256(input.text());
        if (rawDocs.existsByContentHash(hash)) return new Result(null, true,
                "This exact content already exists, so no duplicate was added.");
        Source source = manualSource();
        RawDoc doc = new RawDoc(source, input.sourceUrl(), input.title(),
                input.publishedDate().atStartOfDay().toInstant(ZoneOffset.UTC), Instant.now(), hash,
                input.text(), input.language(), RawDoc.ParseStatus.OK,
                auditPrefix + " | publisher=" + input.publisher() + " | operator-supplied original URL");
        doc.markFullTextAvailable();
        doc.setIntakeMethod(method);
        doc.setPublisherName(input.publisher());
        doc.setOriginalFilename(filename);
        RawDoc saved = rawDocs.save(doc);
        return new Result(saved.getId(), false, "Added as full-text evidence. Run Classify, then Extract to create reviewable facts.");
    }

    private Source manualSource() {
        return sources.findByCode(MANUAL_SOURCE_CODE).orElseGet(() -> {
            Source source = new Source(MANUAL_SOURCE_CODE, "Manually supplied research",
                    "https://manual-input.invalid/", "manual-input.invalid", Source.SourceType.HTML, 3, "en");
            source.setActive(false); // manual records never enter the scheduled crawler
            source.setUrlUnverified(true);
            return sources.save(source);
        });
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }

    public record Result(Long rawDocId, boolean duplicate, String message) {}
}
