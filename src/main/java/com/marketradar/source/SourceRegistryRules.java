package com.marketradar.source;

import com.marketradar.domain.Source;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Pure validation rules shared by the Source Registry test and save paths. */
public final class SourceRegistryRules {

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{2,39}");
    private static final Pattern IPV4 = Pattern.compile("(?:\\d{1,3}\\.){3}\\d{1,3}");
    private static final Set<String> LANGUAGES = Set.of("vi", "en", "zh", "ko", "ja");

    private SourceRegistryRules() {}

    public static ValidatedUrl validateUrl(String fetchUrl) {
        String raw = required(fetchUrl, "Fetch URL");
        if (raw.length() > 1000) {
            throw new ValidationException("Fetch URL must be 1,000 characters or fewer.");
        }

        final URI uri;
        try {
            uri = new URI(raw);
        } catch (URISyntaxException exception) {
            throw new ValidationException("Fetch URL is not a valid URL.");
        }
        if (uri.isOpaque() || !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new ValidationException("Fetch URL must use HTTPS.");
        }
        if (uri.getRawUserInfo() != null) {
            throw new ValidationException("Fetch URL must not contain embedded credentials.");
        }
        if (uri.getRawFragment() != null) {
            throw new ValidationException("Fetch URL must not contain a fragment.");
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            throw new ValidationException("Fetch URL may only use the standard HTTPS port.");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new ValidationException("Fetch URL must contain a valid hostname.");
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.endsWith(".") || host.length() > 253 || IPV4.matcher(host).matches() || host.indexOf(':') >= 0) {
            throw new ValidationException("Fetch URL must use a public hostname, not an IP literal.");
        }

        try {
            String path = uri.getRawPath();
            if (path == null || path.isBlank()) path = "/";
            URI normalized = new URI("https", null, host, -1, path, uri.getRawQuery(), null);
            return new ValidatedUrl(normalized.toASCIIString(), host);
        } catch (URISyntaxException impossibleAfterValidation) {
            throw new ValidationException("Fetch URL could not be normalized safely.");
        }
    }

    public static Source.SourceType validateType(String type) {
        String normalized = required(type, "Parser type").toUpperCase(Locale.ROOT);
        try {
            return Source.SourceType.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new ValidationException("Parser type must be RSS, HTML, PDF, or JSON.");
        }
    }

    public static String validateCode(String code) {
        String normalized = required(code, "Source code").toUpperCase(Locale.ROOT);
        if (!CODE.matcher(normalized).matches()) {
            throw new ValidationException(
                    "Source code must be 3–40 characters and use only A–Z, 0–9, and underscores.");
        }
        return normalized;
    }

    public static String validateName(String name) {
        String normalized = required(name, "Source name");
        if (normalized.length() > 200) {
            throw new ValidationException("Source name must be 200 characters or fewer.");
        }
        if (normalized.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new ValidationException("Source name must not contain control characters.");
        }
        return normalized;
    }

    public static int validateTier(int tier) {
        if (tier < 1 || tier > 4) {
            throw new ValidationException("Tier must be between 1 and 4.");
        }
        return tier;
    }

    public static String validateLanguage(String language) {
        String normalized = required(language, "Language").toLowerCase(Locale.ROOT);
        if (!LANGUAGES.contains(normalized)) {
            throw new ValidationException("Language must be vi, en, zh, ko, or ja.");
        }
        return normalized;
    }

    /** Generic RSS/PDF ingestion is safe; HTML/JSON need a confirmed source-specific listing parser. */
    public static boolean parserSupportedForActivation(Source.SourceType type) {
        return type == Source.SourceType.RSS || type == Source.SourceType.PDF;
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(label + " is required.");
        }
        return value.trim();
    }

    public record ValidatedUrl(String fetchUrl, String allowedHost) {}

    public static final class ValidationException extends IllegalArgumentException {
        public ValidationException(String message) {
            super(message);
        }
    }
}
