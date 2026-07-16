import com.marketradar.domain.Source;
import com.marketradar.fetch.SafeFetcher;
import com.marketradar.repo.SourceRepository;
import com.marketradar.source.SourceRegistryService;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Standalone regression for server-side re-test, activation, and duplicate policies. */
public class SourceRegistryServiceTest {
    public static void main(String[] args) {
        savesUntestedSourceInactive();
        activatesRetestedRssSource();
        holdsJsonInactiveAfterSuccessfulConnectivityTest();
        rejectsDuplicatesBeforeFetch();
        reportsFetcherFailureClearly();
        auditsWithoutChangingRegistryAndRoutesPdfToIntake();
        System.out.println("SourceRegistryServiceTest: ALL PASS");
    }

    private static void savesUntestedSourceInactive() {
        FakeRepository repository = new FakeRepository();
        StubFetcher fetcher = new StubFetcher(false);
        SourceRegistryService service = new SourceRegistryService(repository.proxy(), fetcher);
        var result = service.save(command("NEWS_ONE", "https://news.example.com/rss", "RSS", true, false));

        check(result.success(), "source saved");
        check(!result.active(), "untested source cannot activate");
        check(result.urlUnverified(), "untested source remains unverified");
        check(fetcher.calls == 0, "testPassed=false does not make a network call");
        check(repository.lastSaved != null && !repository.lastSaved.isActive(), "inactive state persisted");
    }

    private static void activatesRetestedRssSource() {
        FakeRepository repository = new FakeRepository();
        StubFetcher fetcher = new StubFetcher(false);
        SourceRegistryService service = new SourceRegistryService(repository.proxy(), fetcher);
        var result = service.save(command("NEWS_TWO", "https://news.example.com/feed", "RSS", true, true));

        check(fetcher.calls == 1, "save re-tests server-side instead of trusting browser state");
        check(result.active(), "verified RSS may activate");
        check(!result.urlUnverified(), "successful server test marks URL verified");
    }

    private static void holdsJsonInactiveAfterSuccessfulConnectivityTest() {
        FakeRepository repository = new FakeRepository();
        StubFetcher fetcher = new StubFetcher(false);
        SourceRegistryService service = new SourceRegistryService(repository.proxy(), fetcher);
        var result = service.save(command("NEWS_API", "https://api.example.com/news", "JSON", true, true));

        check(fetcher.calls == 1, "JSON connectivity is still checked");
        check(!result.active(), "generic JSON is held inactive without a dedicated parser");
        check(!result.urlUnverified(), "reachable JSON URL is recorded as verified");
    }

    private static void rejectsDuplicatesBeforeFetch() {
        FakeRepository repository = new FakeRepository();
        repository.codes.add("NEWS_DUP");
        StubFetcher fetcher = new StubFetcher(false);
        SourceRegistryService service = new SourceRegistryService(repository.proxy(), fetcher);
        try {
            service.save(command("NEWS_DUP", "https://news.example.com/dup", "RSS", true, true));
            throw new AssertionError("duplicate code must be rejected");
        } catch (SourceRegistryService.DuplicateSourceException expected) {
            check(fetcher.calls == 0, "duplicate rejected before network call");
        }
    }

    private static void reportsFetcherFailureClearly() {
        SourceRegistryService service = new SourceRegistryService(new FakeRepository().proxy(), new StubFetcher(true));
        var result = service.test("https://news.example.com/feed", "RSS");
        check(!result.success(), "rejected fetch is a failed test");
        check(result.message().contains("simulated timeout"), "fetch failure reason is returned");
        check("news.example.com".equals(result.allowedHost()), "derived host remains visible for diagnosis");
        check(result.bytes() == 0, "failed test reports no downloaded bytes");
    }

    private static void auditsWithoutChangingRegistryAndRoutesPdfToIntake() {
        FakeRepository repository = new FakeRepository();
        StubFetcher fetcher = new StubFetcher(false);
        SourceRegistryService service = new SourceRegistryService(repository.proxy(), fetcher);
        var result = service.auditBatch("| Swiss Re report | https://example.com/sigma.pdf |\n"
                + "https://example.com/feed.xml\nhttps://example.com/latest");

        check(result.total() == 3, "Markdown rows and raw URLs are parsed");
        check("IMPORT_AS_DOCUMENT".equals(result.results().get(0).status()), "PDF routes to manual intake");
        check("REVIEW_FOR_RECURRING_SOURCE".equals(result.results().get(1).status()), "RSS is reviewed as a recurring source");
        check("NEEDS_DEDICATED_PARSER".equals(result.results().get(2).status()), "HTML is held for a dedicated parser");
        check(repository.lastSaved == null, "audit never persists a source");
    }

    private static SourceRegistryService.SaveCommand command(
            String code, String url, String type, boolean active, boolean testPassed) {
        return new SourceRegistryService.SaveCommand(code, "Demo source", url, type,
                2, "en", active, testPassed);
    }

    private static final class StubFetcher extends SafeFetcher {
        private final boolean reject;
        private int calls;

        private StubFetcher(boolean reject) {
            super(1, 1, 1024, true, 0, 0);
            this.reject = reject;
        }

        @Override
        public FetchResult fetch(String url, String allowedHost, ExpectedKind kind)
                throws FetchRejectedException {
            calls++;
            if (reject) throw new FetchRejectedException("simulated timeout");
            return new FetchResult("demo content".getBytes(StandardCharsets.UTF_8),
                    kind == ExpectedKind.JSON ? "application/json" : "application/rss+xml");
        }
    }

    private static final class FakeRepository {
        private final Set<String> codes = new HashSet<>();
        private final Set<String> urls = new HashSet<>();
        private Source lastSaved;

        private SourceRepository proxy() {
            return (SourceRepository) Proxy.newProxyInstance(
                    SourceRepository.class.getClassLoader(),
                    new Class<?>[]{SourceRepository.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "existsByCodeIgnoreCase" -> codes.stream()
                                .anyMatch(code -> code.equalsIgnoreCase((String) args[0]));
                        case "existsByFetchUrl" -> urls.contains((String) args[0]);
                        case "findAll" -> List.of();
                        case "findFirstByAllowedHostIgnoreCase" -> Optional.empty();
                        case "saveAndFlush" -> {
                            lastSaved = (Source) args[0];
                            codes.add(lastSaved.getCode());
                            urls.add(lastSaved.getFetchUrl());
                            yield lastSaved;
                        }
                        case "toString" -> "FakeSourceRepository";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
