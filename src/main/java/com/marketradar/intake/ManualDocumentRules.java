package com.marketradar.intake;

import java.net.URI;
import java.time.LocalDate;
import java.util.Locale;

/** Boundary checks for operator-supplied research.  They make provenance explicit,
 * but do not grant uploaded material higher source credibility. */
public final class ManualDocumentRules {
    public static final int MIN_TEXT_CHARS = 600;
    public static final int MAX_TEXT_CHARS = 250_000;
    public static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

    private ManualDocumentRules() {}

    public static Submission validate(String title, String publisher, String sourceUrl,
                                      LocalDate publishedDate, String language, String text) {
        String cleanTitle = required(title, "Title", 300);
        String cleanPublisher = required(publisher, "Publisher", 180);
        String cleanUrl = httpsUrl(sourceUrl);
        if (publishedDate == null) throw new ValidationException("Publication date is required.");
        if (publishedDate.isAfter(LocalDate.now().plusDays(1))) {
            throw new ValidationException("Publication date cannot be in the future.");
        }
        String cleanLanguage = language == null ? "" : language.strip().toLowerCase(Locale.ROOT);
        if (!(cleanLanguage.equals("en") || cleanLanguage.equals("vi") || cleanLanguage.equals("zh")
                || cleanLanguage.equals("ko") || cleanLanguage.equals("ja"))) {
            throw new ValidationException("Choose a supported document language.");
        }
        String cleanText = text == null ? "" : text.strip();
        if (cleanText.length() < MIN_TEXT_CHARS) {
            throw new ValidationException("Content needs at least " + MIN_TEXT_CHARS + " characters.");
        }
        if (cleanText.length() > MAX_TEXT_CHARS) {
            throw new ValidationException("Content exceeds the " + MAX_TEXT_CHARS + " character safety limit.");
        }
        return new Submission(cleanTitle, cleanPublisher, cleanUrl, publishedDate, cleanLanguage, cleanText);
    }

    public static String httpsUrl(String value) {
        String clean = required(value, "Original source URL", 2048);
        try {
            URI uri = URI.create(clean);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new ValidationException("Original source URL must be a complete HTTPS URL.");
            }
            return uri.toString();
        } catch (IllegalArgumentException badUrl) {
            throw new ValidationException("Original source URL is not valid.");
        }
    }

    private static String required(String value, String label, int max) {
        String clean = value == null ? "" : value.strip();
        if (clean.isEmpty()) throw new ValidationException(label + " is required.");
        if (clean.length() > max) throw new ValidationException(label + " is too long.");
        return clean;
    }

    public record Submission(String title, String publisher, String sourceUrl,
                             LocalDate publishedDate, String language, String text) {}
    public static class ValidationException extends RuntimeException {
        public ValidationException(String message) { super(message); }
    }
}
