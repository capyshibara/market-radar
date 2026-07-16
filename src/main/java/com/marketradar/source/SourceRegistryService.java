package com.marketradar.source;

import com.marketradar.domain.Source;
import com.marketradar.fetch.SafeFetcher;
import com.marketradar.repo.SourceRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Application service for safely testing and registering demo sources. */
@Service
public class SourceRegistryService {

    /** A deliberately small operator batch prevents the registry screen becoming a web scanner. */
    static final int MAX_AUDIT_CANDIDATES = 100;
    private static final Pattern HTTPS_URL = Pattern.compile("https://\\S+", Pattern.CASE_INSENSITIVE);

    private final SourceRepository sources;
    private final SafeFetcher fetcher;

    public SourceRegistryService(SourceRepository sources, SafeFetcher fetcher) {
        this.sources = sources;
        this.fetcher = fetcher;
    }

    public TestResult test(String fetchUrl, String typeText) {
        final SourceRegistryRules.ValidatedUrl validatedUrl;
        final Source.SourceType type;
        try {
            validatedUrl = SourceRegistryRules.validateUrl(fetchUrl);
            type = SourceRegistryRules.validateType(typeText);
        } catch (SourceRegistryRules.ValidationException invalid) {
            return TestResult.failure(invalid.getMessage());
        }

        boolean parserSupport = SourceRegistryRules.parserSupportedForActivation(type);
        try {
            SafeFetcher.FetchResult result = fetcher.fetch(
                    validatedUrl.fetchUrl(), validatedUrl.allowedHost(), expectedKind(type));
            String message = parserSupport
                    ? "Connection and content type passed. This parser type can be activated."
                    : type == Source.SourceType.HTML
                        ? "Connection passed. Generic HTML is single-page only, so this source will remain inactive until a listing parser is confirmed."
                        : "Connection passed. JSON requires a source-specific parser, so this source will remain inactive.";
            return new TestResult(true, message, validatedUrl.allowedHost(), result.contentType(),
                    result.body().length, parserSupport, parserSupport);
        } catch (SafeFetcher.FetchRejectedException rejected) {
            return new TestResult(false, rejected.getMessage(), validatedUrl.allowedHost(), null,
                    0, parserSupport, false);
        }
    }

    /**
     * Tests candidate URLs without creating, changing, or activating a Source row. It accepts
     * one URL per line and also extracts the first URL from a Markdown/pipe-table row, which is
     * convenient for output copied from a research assistant.
     */
    public BatchAuditResult auditBatch(String candidatesText) {
        List<AuditCandidate> candidates = parseAuditCandidates(candidatesText);
        List<AuditResult> results = new ArrayList<>();
        for (AuditCandidate candidate : candidates) {
            results.add(auditCandidate(candidate));
        }
        return new BatchAuditResult(candidates.size(), results);
    }

    static List<AuditCandidate> parseAuditCandidates(String candidatesText) {
        if (candidatesText == null || candidatesText.isBlank()) {
            throw new SourceRegistryRules.ValidationException("Paste at least one HTTPS URL to audit.");
        }
        Map<String, AuditCandidate> unique = new LinkedHashMap<>();
        for (String line : candidatesText.split("\\R")) {
            Matcher match = HTTPS_URL.matcher(line);
            if (!match.find()) continue;
            String url = trimUrlPunctuation(match.group());
            String type = typeFromLineOrUrl(line, url);
            String label = labelFromLine(line, url);
            unique.putIfAbsent(url, new AuditCandidate(url, label, type));
            if (unique.size() > MAX_AUDIT_CANDIDATES) {
                throw new SourceRegistryRules.ValidationException(
                        "Audit at most " + MAX_AUDIT_CANDIDATES + " unique URLs at a time.");
            }
        }
        if (unique.isEmpty()) {
            throw new SourceRegistryRules.ValidationException("No HTTPS URL was found. Paste one URL per line.");
        }
        return List.copyOf(unique.values());
    }

    private AuditResult auditCandidate(AuditCandidate candidate) {
        final SourceRegistryRules.ValidatedUrl validated;
        final Source.SourceType type;
        try {
            validated = SourceRegistryRules.validateUrl(candidate.fetchUrl());
            type = SourceRegistryRules.validateType(candidate.type());
        } catch (SourceRegistryRules.ValidationException invalid) {
            return AuditResult.invalid(candidate, invalid.getMessage());
        }

        Optional<Source> exact = sources.findAll().stream()
                .filter(source -> source.getFetchUrl().equals(validated.fetchUrl()))
                .findFirst();
        Optional<Source> sameHost = sources.findFirstByAllowedHostIgnoreCase(validated.allowedHost());
        if (exact.isPresent()) {
            Source source = exact.get();
            return new AuditResult(candidate.label(), validated.fetchUrl(), type.name(), "ALREADY_REGISTERED",
                    "ALREADY_REGISTERED", validated.allowedHost(), null, 0, source.getCode());
        }

        final SafeFetcher.FetchResult fetched;
        try {
            fetched = fetcher.fetch(validated.fetchUrl(), validated.allowedHost(), expectedKind(type));
        } catch (SafeFetcher.FetchRejectedException rejected) {
            return new AuditResult(candidate.label(), validated.fetchUrl(), type.name(), "FETCH_REJECTED",
                    rejected.getMessage(), validated.allowedHost(), null, 0,
                    sameHost.map(Source::getCode).orElse(null));
        }

        String existing = sameHost.map(Source::getCode).orElse(null);
        if (type == Source.SourceType.PDF) {
            return new AuditResult(candidate.label(), validated.fetchUrl(), type.name(), "IMPORT_AS_DOCUMENT",
                    "IMPORT_AS_DOCUMENT",
                    validated.allowedHost(), fetched.contentType(), fetched.body().length, existing);
        }
        if (type == Source.SourceType.RSS) {
            return new AuditResult(candidate.label(), validated.fetchUrl(), type.name(), "REVIEW_FOR_RECURRING_SOURCE",
                    "REVIEW_FOR_RECURRING_SOURCE", validated.allowedHost(), fetched.contentType(), fetched.body().length, existing);
        }
        return new AuditResult(candidate.label(), validated.fetchUrl(), type.name(), "NEEDS_DEDICATED_PARSER",
                "NEEDS_DEDICATED_PARSER", validated.allowedHost(), fetched.contentType(), fetched.body().length, existing);
    }

    private static String typeFromLineOrUrl(String line, String url) {
        String upper = line.toUpperCase(Locale.ROOT);
        for (Source.SourceType type : Source.SourceType.values()) {
            if (upper.matches(".*(?:\\||,|\\s)" + type.name() + "(?:\\||,|\\s|$).*")) return type.name();
        }
        String path = URI.create(url).getPath();
        String lowerPath = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (lowerPath.endsWith(".pdf")) return Source.SourceType.PDF.name();
        if (lowerPath.endsWith(".xml") || lowerPath.endsWith(".rss") || lowerPath.endsWith(".atom")) return Source.SourceType.RSS.name();
        if (lowerPath.endsWith(".json")) return Source.SourceType.JSON.name();
        return Source.SourceType.HTML.name();
    }

    private static String labelFromLine(String line, String url) {
        String candidate = line.replace(url, "").replaceAll("[|\\[\\]()`*_]+", " ").trim();
        return candidate.isBlank() ? URI.create(url).getHost() : candidate.substring(0, Math.min(candidate.length(), 160));
    }

    private static String trimUrlPunctuation(String url) {
        while (url.endsWith(".") || url.endsWith(",") || url.endsWith(";")
                || url.endsWith(")") || url.endsWith("]") || url.endsWith(">")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /**
     * Synchronized so the friendly duplicate checks and insert are one process-local unit.
     * The database's unique code constraint remains the final race-condition safeguard.
     */
    @Transactional
    public synchronized SaveResult save(SaveCommand command) {
        String code = SourceRegistryRules.validateCode(command.code());
        String name = SourceRegistryRules.validateName(command.name());
        SourceRegistryRules.ValidatedUrl validatedUrl = SourceRegistryRules.validateUrl(command.fetchUrl());
        Source.SourceType type = SourceRegistryRules.validateType(command.type());
        int tier = SourceRegistryRules.validateTier(command.tier());
        String language = SourceRegistryRules.validateLanguage(command.language());

        if (sources.existsByCodeIgnoreCase(code)) {
            throw new DuplicateSourceException("A source with code " + code + " already exists.");
        }
        if (sources.existsByFetchUrl(validatedUrl.fetchUrl())) {
            throw new DuplicateSourceException("This fetch URL is already registered.");
        }

        // Never trust the browser's testPassed flag: a claimed pass triggers a fresh,
        // server-side SafeFetcher call immediately before persistence.
        TestResult serverTest = command.testPassed()
                ? test(validatedUrl.fetchUrl(), type.name())
                : TestResult.notRun(validatedUrl.allowedHost(),
                        SourceRegistryRules.parserSupportedForActivation(type));
        boolean verified = command.testPassed() && serverTest.success();
        boolean active = command.active() && verified && serverTest.recommendedActive();

        Source source = new Source(code, name, validatedUrl.fetchUrl(), validatedUrl.allowedHost(),
                type, tier, language);
        source.setUrlUnverified(!verified);
        source.setActive(active);
        try {
            Source saved = sources.saveAndFlush(source);
            String message;
            if (active) {
                message = "Source saved, verified, and activated.";
            } else if (!verified) {
                message = "Source saved inactive. Run a successful test before activation.";
            } else {
                message = "Source saved and verified, but kept inactive until a source-specific parser is configured.";
            }
            return new SaveResult(true, message, saved.getId(), active, !verified);
        } catch (DataIntegrityViolationException conflict) {
            throw new DuplicateSourceException("The source code or fetch URL is already registered.");
        }
    }

    private static SafeFetcher.ExpectedKind expectedKind(Source.SourceType type) {
        return switch (type) {
            case RSS -> SafeFetcher.ExpectedKind.RSS;
            case HTML -> SafeFetcher.ExpectedKind.HTML;
            case PDF -> SafeFetcher.ExpectedKind.PDF;
            case JSON -> SafeFetcher.ExpectedKind.JSON;
        };
    }

    public record SaveCommand(String code, String name, String fetchUrl, String type,
                              int tier, String language, boolean active, boolean testPassed) {}

    public record TestResult(boolean success, String message, String allowedHost,
                             String contentType, long bytes, boolean parserSupport,
                             boolean recommendedActive) {
        private static TestResult failure(String message) {
            return new TestResult(false, message, null, null, 0, false, false);
        }

        private static TestResult notRun(String allowedHost, boolean parserSupport) {
            return new TestResult(false, "Source was not tested.", allowedHost, null, 0,
                    parserSupport, false);
        }
    }

    public record SaveResult(boolean success, String message, Long id,
                             boolean active, boolean urlUnverified) {}

    public record AuditCandidate(String fetchUrl, String label, String type) {}

    public record BatchAuditResult(int total, List<AuditResult> results) {}

    public record AuditResult(String label, String fetchUrl, String inferredType, String status,
                              String advice, String allowedHost, String contentType, long bytes,
                              String existingSourceCode) {
        private static AuditResult invalid(AuditCandidate candidate, String message) {
            return new AuditResult(candidate.label(), candidate.fetchUrl(), candidate.type(), "INVALID_URL",
                    message, null, null, 0, null);
        }
    }

    public static final class DuplicateSourceException extends IllegalStateException {
        public DuplicateSourceException(String message) {
            super(message);
        }
    }
}
