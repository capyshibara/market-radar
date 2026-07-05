package com.marketradar.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import com.marketradar.domain.RawDoc;
import com.marketradar.domain.Source;
import com.marketradar.fetch.SafeFetcher;
import com.marketradar.parse.ContentParsers;
import com.marketradar.repo.RawDocRepository;
import com.marketradar.repo.SourceRepository;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Bước 1-2 của pipeline: quét nguồn → parse/chuẩn hoá → lưu raw_docs.
 * Invariants áp dụng:
 *  - Whitelist: chỉ fetch nguồn trong registry, qua SafeFetcher.
 *  - Fail loud: mọi lỗi fetch/parse đều được GHI LẠI (log + record) với lý do, không silent-skip.
 *  - Dedup exact bằng SHA-256 content hash ngay tại ingest.
 */
@Service
public class IngestionJob {

    private static final Logger log = LoggerFactory.getLogger(IngestionJob.class);

    private final SourceRepository sources;
    private final RawDocRepository rawDocs;
    private final SafeFetcher fetcher;
    private final ContentParsers parsers;
    private final boolean scheduledEnabled;

    public IngestionJob(SourceRepository sources, RawDocRepository rawDocs,
                        SafeFetcher fetcher, ContentParsers parsers,
                        @Value("${marketradar.ingest.enabled:false}") boolean scheduledEnabled) {
        this.sources = sources;
        this.rawDocs = rawDocs;
        this.fetcher = fetcher;
        this.parsers = parsers;
        this.scheduledEnabled = scheduledEnabled;
    }

    @Scheduled(fixedDelayString = "${marketradar.ingest.fixed-delay-ms:900000}")
    public void scheduledRun() {
        if (!scheduledEnabled) return; // demo dùng chạy tay để deterministic
        runOnce();
    }

    /** Chạy một vòng ingest cho toàn bộ nguồn active. Trả về summary text cho endpoint tay. */
    @Transactional
    public String runOnce() {
        StringBuilder summary = new StringBuilder();
        for (Source source : sources.findByActiveTrue()) {
            try {
                int stored = ingestSource(source);
                summary.append(source.getCode()).append(": +").append(stored).append(" doc\n");
            } catch (SafeFetcher.FetchRejectedException e) {
                log.warn("FETCH REJECTED [{}]: {}", source.getCode(), e.getMessage());
                summary.append(source.getCode()).append(": REJECTED — ").append(e.getMessage()).append('\n');
            } catch (ContentParsers.ParseFailedException e) {
                log.warn("PARSE FAILED [{}]: {}", source.getCode(), e.getMessage());
                recordFailure(source, source.getFetchUrl(),
                        RawDoc.ParseStatus.PARSE_ERROR, e.getMessage());
                summary.append(source.getCode()).append(": PARSE_ERROR — ").append(e.getMessage()).append('\n');
            } catch (Exception e) {
                log.error("UNEXPECTED [{}]", source.getCode(), e);
                summary.append(source.getCode()).append(": ERROR — ").append(e.getMessage()).append('\n');
            }
        }
        return summary.toString();
    }

    private int ingestSource(Source source)
            throws SafeFetcher.FetchRejectedException, ContentParsers.ParseFailedException {
        return switch (source.getType()) {
            case HTML -> ingestHtml(source);
            case RSS -> ingestRss(source);
            case PDF -> ingestPdf(source);
        };
    }

    /**
     * Batch 6b: một số nguồn HTML có parser riêng theo cấu trúc trang thật (không
     * đoán từ registry notes — xác nhận bằng fetch trực tiếp), trả về NHIỀU tin/lần
     * chạy thay vì dump nguyên trang. Nguồn chưa có parser riêng vẫn dùng đường cũ
     * (parseHtml dump toàn trang thành 1 RawDoc) — không force-fit parser cho site
     * chưa xác nhận cấu trúc (MOF VN bị chặn do site là SPA render bằng JS, SafeFetcher
     * chỉ lấy static HTML nên không parser nào trích được — bỏ qua, không silent-skip
     * mà ghi rõ trong handoff).
     */
    private int ingestHtml(Source source)
            throws SafeFetcher.FetchRejectedException, ContentParsers.ParseFailedException {
        var result = fetcher.fetch(source.getFetchUrl(), source.getAllowedHost(),
                SafeFetcher.ExpectedKind.HTML);
        return switch (source.getCode()) {
            case "IAV_VN" -> ingestListing(source, parsers.parseIav(result.body(), source.getFetchUrl()));
            case "AIA_VN" -> ingestListing(source, parsers.parseAia(result.body(), source.getFetchUrl()));
            case "MANULIFE_VN" -> ingestListing(source, parsers.parseManulife(result.body(), source.getFetchUrl()));
            case "PRUDENTIAL_VN" -> ingestListing(source, parsers.parsePrudential(result.body(), source.getFetchUrl()));
            case "MAP_LIFE" -> ingestListing(source, parsers.parseMapLife(result.body(), source.getFetchUrl()));
            case "FUBON_VN" -> ingestListing(source, parsers.parseFubonVn(result.body(), source.getFetchUrl()));
            default -> {
                var parsed = parsers.parseHtml(result.body());
                yield storeIfNew(source, source.getFetchUrl(), parsed.title(), null, parsed.text()) ? 1 : 0;
            }
        };
    }

    private int ingestListing(Source source, java.util.List<ContentParsers.ListingItem> listing) {
        int stored = 0;
        for (var item : listing) {
            if (storeIfNew(source, item.link(), item.title(), item.publishedAt(), item.title())) stored++;
        }
        return stored;
    }

    private int ingestPdf(Source source)
            throws SafeFetcher.FetchRejectedException, ContentParsers.ParseFailedException {
        var result = fetcher.fetch(source.getFetchUrl(), source.getAllowedHost(),
                SafeFetcher.ExpectedKind.PDF);
        var parsed = parsers.parsePdf(result.body());
        return storeIfNew(source, source.getFetchUrl(), parsed.title(), null, parsed.text()) ? 1 : 0;
    }

    /**
     * RSS batch 1: lưu title + description của entry làm rawText.
     * Fetch full bài viết theo link là bước sau — và khi làm, link đó CŨNG phải
     * qua SafeFetcher với allowedHost của source (không mở rộng whitelist ngầm).
     */
    private int ingestRss(Source source)
            throws SafeFetcher.FetchRejectedException, ContentParsers.ParseFailedException {
        var result = fetcher.fetch(source.getFetchUrl(), source.getAllowedHost(),
                SafeFetcher.ExpectedKind.RSS);
        int stored = 0;
        for (var item : parsers.parseRss(result.body())) {
            String link = item.link() == null ? source.getFetchUrl() : item.link();
            // Ghi chú host lạ ngay từ ingest — audit trail cho bước fetch full-text sau này
            String note = null;
            String linkHost = safeHost(link);
            if (linkHost != null && !linkHost.equalsIgnoreCase(source.getAllowedHost())) {
                note = "Link entry trỏ host ngoài whitelist (" + linkHost + ") — chỉ lưu metadata feed";
            }
            String text = item.title() + "\n\n" + item.descriptionText();
            if (storeIfNew(source, link, item.title(), item.publishedAt(), text, note)) stored++;
        }
        return stored;
    }

    private boolean storeIfNew(Source source, String url, String title,
                               Instant publishedAt, String text) {
        return storeIfNew(source, url, title, publishedAt, text, null);
    }

    private boolean storeIfNew(Source source, String url, String title,
                               Instant publishedAt, String text, String note) {
        String hash = sha256(text);
        if (rawDocs.existsByContentHash(hash)) {
            log.debug("Dedup hash trùng, bỏ qua: {}", url);
            return false;
        }
        rawDocs.save(new RawDoc(source, url, title, publishedAt, Instant.now(),
                hash, text, source.getLanguage(), RawDoc.ParseStatus.OK, note));
        return true;
    }

    private void recordFailure(Source source, String url, RawDoc.ParseStatus status, String reason) {
        String hash = sha256("FAILURE:" + url + ":" + Instant.now());
        rawDocs.save(new RawDoc(source, url, null, null, Instant.now(),
                hash, null, source.getLanguage(), status, reason));
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 không khả dụng", e);
        }
    }

    private static String safeHost(String url) {
        try { return URI.create(url).getHost(); } catch (Exception e) { return null; }
    }
}
