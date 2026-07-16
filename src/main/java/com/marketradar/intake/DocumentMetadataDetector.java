package com.marketradar.intake;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Deterministic metadata extraction for the zero-form-field intake flow. */
public final class DocumentMetadataDetector {
    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Pattern ISO_DATE = Pattern.compile("(20\\d{2}-\\d{2}-\\d{2})");
    private static final Pattern DMY_DATE = Pattern.compile("(?<!\\d)(\\d{1,2}/\\d{1,2}/20\\d{2})(?!\\d)");
    private static final Pattern EN_DATE = Pattern.compile(
            "(?i)\\b(January|February|March|April|May|June|July|August|September|October|November|December)\\s+(\\d{1,2}),\\s+(20\\d{2})\\b");
    private static final DateTimeFormatter ENGLISH_DATE = new DateTimeFormatterBuilder()
            .parseCaseInsensitive().appendPattern("MMMM d, uuuu").toFormatter(Locale.ENGLISH);
    private static final Map<String, String> KNOWN_PUBLISHERS = Map.ofEntries(
            Map.entry("aia.com.vn", "AIA Việt Nam"),
            Map.entry("bcg.com", "Boston Consulting Group"),
            Map.entry("web-assets.bcg.com", "Boston Consulting Group"),
            Map.entry("swissre.com", "Swiss Re Institute"),
            Map.entry("mckinsey.com", "McKinsey & Company"),
            Map.entry("munichre.com", "Munich Re"),
            Map.entry("mof.gov.vn", "Bộ Tài chính Việt Nam"),
            Map.entry("iav.vn", "Hiệp hội Bảo hiểm Việt Nam"));

    private DocumentMetadataDetector() {}

    public static Metadata html(byte[] body, String cleanText, String parsedTitle, String url) {
        Document doc = Jsoup.parse(new String(body, java.nio.charset.StandardCharsets.UTF_8), url);
        String title = firstNonBlank(parsedTitle, content(doc, "meta[property=og:title]"), doc.title(), "Imported article");
        String publisher = firstNonBlank(knownPublisher(url),
                content(doc, "meta[property=og:site_name]"), hostLabel(url), "Imported website");
        String dateCandidate = firstNonBlank(
                content(doc, "meta[property=article:published_time]"),
                content(doc, "meta[name=date]"),
                attribute(doc, "[data-datepublished]", "data-datepublished"),
                attribute(doc, "[datepublished]", "datepublished"),
                attribute(doc, "[itemprop=datePublished]", "content"),
                attribute(doc, "time[datetime]", "datetime"));
        LocalDate date = firstDate(dateCandidate, jsonDate(doc.html()), cleanText);
        String language = normalizeLanguage(doc.selectFirst("html") == null ? null : doc.selectFirst("html").attr("lang"), cleanText);
        return new Metadata(title, publisher, date, language);
    }

    public static Metadata pdf(byte[] body, String cleanText, String filename) {
        String pdfTitle = null;
        String author = null;
        try (PDDocument doc = PDDocument.load(body)) {
            pdfTitle = doc.getDocumentInformation().getTitle();
            author = doc.getDocumentInformation().getAuthor();
        } catch (Exception ignored) {
            // The text parser already owns parse failure; metadata is optional.
        }
        String title = firstNonBlank(pdfTitle, titleFromFilename(filename), firstMeaningfulLine(cleanText), "Uploaded PDF");
        String publisher = knownPublisherInText(cleanText);
        if (publisher == null) publisher = usefulAuthor(author) ? author.strip() : "Uploaded document";
        // PDF CreationDate is a file-production timestamp, not trustworthy publication evidence.
        LocalDate date = firstDate(cleanText);
        return new Metadata(title, publisher, date, normalizeLanguage(null, cleanText));
    }

    public static Metadata text(String cleanText, String filename) {
        return new Metadata(firstNonBlank(titleFromFilename(filename), firstMeaningfulLine(cleanText), "Uploaded text"),
                firstNonBlank(knownPublisherInText(cleanText), "Uploaded document"),
                firstDate(cleanText), normalizeLanguage(null, cleanText));
    }

    private static String content(Document doc, String selector) {
        Element element = doc.selectFirst(selector);
        return element == null ? null : element.attr("content");
    }

    private static String attribute(Document doc, String selector, String attribute) {
        Element element = doc.selectFirst(selector);
        if (element == null) return null;
        String value = element.attr(attribute);
        return value.isBlank() ? element.text() : value;
    }

    private static String jsonDate(String html) {
        if (html == null) return null;
        var matcher = Pattern.compile("\\\"datePublished\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"", Pattern.CASE_INSENSITIVE).matcher(html);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static LocalDate firstDate(String... candidates) {
        for (String candidate : candidates) {
            LocalDate parsed = parseDate(candidate);
            if (parsed != null && !parsed.isAfter(LocalDate.now(REPORT_ZONE).plusDays(1))) return parsed;
        }
        return null;
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        String sample = value.length() > 12_000 ? value.substring(0, 12_000) : value;
        try { return OffsetDateTime.parse(sample.strip()).toLocalDate(); } catch (Exception ignored) {}
        try { return Instant.parse(sample.strip()).atZone(REPORT_ZONE).toLocalDate(); } catch (Exception ignored) {}
        var iso = ISO_DATE.matcher(sample);
        if (iso.find()) try { return LocalDate.parse(iso.group(1)); } catch (Exception ignored) {}
        var en = EN_DATE.matcher(sample);
        if (en.find()) try { return LocalDate.parse(en.group(), ENGLISH_DATE); } catch (Exception ignored) {}
        var dmy = DMY_DATE.matcher(sample);
        if (dmy.find()) try { return LocalDate.parse(dmy.group(1), DateTimeFormatter.ofPattern("d/M/uuuu")); } catch (Exception ignored) {}
        return null;
    }

    private static String normalizeLanguage(String declared, String text) {
        if (declared != null && !declared.isBlank()) {
            String code = declared.strip().toLowerCase(Locale.ROOT).split("[-_]")[0];
            if (code.matches("vi|en|zh|ko|ja")) return code;
        }
        if (text != null && text.matches("(?is).*?[ăâđêôơưáàảãạấầẩẫậắằẳẵặéèẻẽẹếềểễệíìỉĩịóòỏõọốồổỗộớờởỡợúùủũụứừửữựýỳỷỹỵ].*")) return "vi";
        return "en";
    }

    private static String knownPublisher(String url) {
        try {
            String host = URI.create(url).getHost().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
            for (var entry : KNOWN_PUBLISHERS.entrySet()) {
                if (host.equals(entry.getKey()) || host.endsWith("." + entry.getKey())) return entry.getValue();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String hostLabel(String url) {
        try { return URI.create(url).getHost().replaceFirst("^www\\.", ""); }
        catch (Exception ignored) { return null; }
    }

    private static String knownPublisherInText(String text) {
        if (text == null) return null;
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("boston consulting group") || lower.contains("©2026 bcg")) return "Boston Consulting Group";
        if (lower.contains("swiss re institute")) return "Swiss Re Institute";
        if (lower.contains("mckinsey & company")) return "McKinsey & Company";
        if (lower.contains("aia việt nam")) return "AIA Việt Nam";
        if (lower.contains("bộ tài chính")) return "Bộ Tài chính Việt Nam";
        return null;
    }

    private static String titleFromFilename(String filename) {
        if (filename == null || filename.isBlank()) return null;
        String base = filename.replaceFirst("(?i)\\.(pdf|txt)$", "").replaceAll("[_-]+", " ").strip();
        if (base.length() < 3 || base.equalsIgnoreCase("document") || base.equalsIgnoreCase("download")) return null;
        return Character.toUpperCase(base.charAt(0)) + base.substring(1);
    }

    private static String firstMeaningfulLine(String text) {
        if (text == null) return null;
        for (String line : text.split("\\R")) {
            String clean = line.strip();
            if (clean.length() >= 8 && clean.length() <= 240) return clean;
        }
        return null;
    }

    private static boolean usefulAuthor(String author) {
        return author != null && !author.isBlank() && author.length() <= 180
                && !author.equalsIgnoreCase("Microsoft Office User");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.strip();
        return null;
    }

    public record Metadata(String title, String publisher, LocalDate publishedDate, String language) {}
}
