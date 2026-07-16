package com.marketradar.source;

import com.marketradar.domain.Source;
import com.marketradar.fetch.SafeFetcher;
import com.marketradar.repo.SourceRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Application service for safely testing and registering demo sources. */
@Service
public class SourceRegistryService {

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

    public static final class DuplicateSourceException extends IllegalStateException {
        public DuplicateSourceException(String message) {
            super(message);
        }
    }
}
