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
    private final int maxItemsPerSource;

    public IngestionJob(SourceRepository sources, RawDocRepository rawDocs,
                        SafeFetcher fetcher, ContentParsers parsers,
                        @Value("${marketradar.ingest.enabled:false}") boolean scheduledEnabled,
                        @Value("${marketradar.ingest.max-items-per-source:25}") int maxItemsPerSource) {
        this.sources = sources;
        this.rawDocs = rawDocs;
        this.fetcher = fetcher;
        this.parsers = parsers;
        this.scheduledEnabled = scheduledEnabled;
        this.maxItemsPerSource = maxItemsPerSource;
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
                summary.append(source.getCode()).append(": PARSE ERROR — ").append(e.getMessage()).append('\n');
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
            case JSON -> ingestJson(source);
        };
    }

    /**
     * Fix 2026-07-14 (feedback Hanh: "crawler không thấy ngày mà mắt thì thấy"): một số
     * site (BIDV MetLife — nền tảng AEM) render danh sách tin bằng JS, HTML tĩnh KHÔNG
     * chứa bài. Nhưng JS chỉ gọi một endpoint JSON có sẵn (title + publishedDate + path) —
     * ta gọi thẳng endpoint đó (fetchUrl của source giờ là URL JSON) rồi parse như listing.
     * Cùng cơ chế ingestListing: mỗi path bài là trang HTML cùng host → fetch toàn văn.
     */
    private int ingestJson(Source source)
            throws SafeFetcher.FetchRejectedException, ContentParsers.ParseFailedException {
        return switch (source.getCode()) {
            case "BIDV_METLIFE" -> {
                var result = fetcher.fetch(source.getFetchUrl(), source.getAllowedHost(),
                        SafeFetcher.ExpectedKind.JSON);
                yield ingestListing(source, parsers.parseBidvMetlife(result.body(), source.getFetchUrl()));
            }
            case "MOF_ISA" -> ingestMofIsa(source);
            case "DAIICHI_VN" -> {
                // POST rỗng "{}" là đủ — xác nhận thủ công response giống hệt request không body.
                var result = fetcher.fetch(source.getFetchUrl(), source.getAllowedHost(),
                        SafeFetcher.ExpectedKind.JSON, "{}");
                yield ingestListing(source, parsers.parseDaiichiVn(result.body(), source.getFetchUrl()));
            }
            case "GENERALI_VN" -> {
                var result = fetcher.fetch(source.getFetchUrl(), source.getAllowedHost(),
                        SafeFetcher.ExpectedKind.JSON);
                yield ingestListing(source, parsers.parseGeneraliVn(result.body(), source.getFetchUrl()));
            }
            case "SHINHAN_VN" -> {
                var result = fetcher.fetch(source.getFetchUrl(), source.getAllowedHost(),
                        SafeFetcher.ExpectedKind.JSON);
                yield ingestListing(source, parsers.parseShinhanVn(result.body(), source.getFetchUrl()));
            }
            case "CATHAY_VN" -> {
                var result = fetcher.fetch(source.getFetchUrl(), source.getAllowedHost(),
                        SafeFetcher.ExpectedKind.JSON, CATHAY_VN_GRAPHQL_BODY);
                yield ingestListing(source, parsers.parseCathayVn(result.body(), source.getFetchUrl()));
            }
            case "HKIA" -> {
                // Body rỗng là đủ — xác nhận thủ công. baseUrl truyền cho parser là TRANG HTML
                // (không phải endpoint .php) vì url trả về tương đối theo "../../" tính từ đó.
                var result = fetcher.fetch(source.getFetchUrl(), source.getAllowedHost(),
                        SafeFetcher.ExpectedKind.JSON, "");
                yield ingestListing(source, parsers.parseHkia(result.body(), HKIA_PAGE_URL));
            }
            case "FSC_KR" -> {
                var result = fetcher.fetch(source.getFetchUrl(), source.getAllowedHost(),
                        SafeFetcher.ExpectedKind.JSON);
                yield ingestListing(source, parsers.parseFscKr(result.body(), source.getFetchUrl()));
            }
            case "NIPPON_LIFE" -> {
                var result = fetcher.fetch(source.getFetchUrl(), source.getAllowedHost(),
                        SafeFetcher.ExpectedKind.JSON);
                yield ingestListing(source, parsers.parseNipponLife(result.body(), source.getFetchUrl()));
            }
            case "NFRA_CN" -> {
                var result = fetcher.fetch(source.getFetchUrl(), source.getAllowedHost(),
                        SafeFetcher.ExpectedKind.JSON);
                yield ingestListing(source, parsers.parseNfraCn(result.body(), source.getFetchUrl()));
            }
            default -> throw new ContentParsers.ParseFailedException(
                    "Nguồn JSON '" + source.getCode() + "' chưa có parser riêng");
        };
    }

    /**
     * Query GraphQL THẬT của Cathay Life, bắt được bằng cách vá window.fetch trên trang
     * /cathay/news rồi bấm tab chuyên mục thật (không đoán schema). ncategory_id="1" =
     * "Hoạt động kinh doanh" — chuyên mục tin công ty/PR, sát nghĩa insurance news nhất.
     */
    private static final String CATHAY_VN_GRAPHQL_BODY = """
            {"variables":{"condition":{"ncategory_id":"1","start":1,"end":15},"ncategory_id":"1"},\
            "query":"query news($condition: NewsParams!, $ncategory_id: Int) {\\n    news(condition: $condition) {\\n        news_id\\n        images\\n        images_name\\n        content\\n        featured\\n        ncategory_id\\n        posted_at\\n    }\\n    count(ncategory_id: $ncategory_id)\\n}"}""";

    /** rootCategoryId của chuyên mục "Quản lý giám sát bảo hiểm" trên portal MOF (xác nhận live 2026-07-14). */
    private static final String MOF_INSURANCE_ROOT_CATEGORY = "8dc0b2a0-38bd-427c-b6d5-c97a6f9952b4";

    /** Trang HTML thật của HKIA (không phải endpoint .php) — url tương đối trong response resolve theo đây. */
    private static final String HKIA_PAGE_URL = "https://www.ia.org.hk/en/infocenter/press_releases.html";

    /**
     * MOF_ISA: danh sách qua POST /api/article/reads (body rootCategoryId), rồi mỗi bài
     * lấy full text qua GET /api/article/getbyslug (article page là SPA nên KHÔNG fetch
     * HTML như ingestListing được — phải qua API chi tiết). publishedAt lấy từ
     * publicationTime của API (ngày THẬT — đúng thứ bộ lọc độ mới cần).
     */
    private int ingestMofIsa(Source source)
            throws SafeFetcher.FetchRejectedException, ContentParsers.ParseFailedException {
        String listBody = "{\"rootCategoryId\":\"" + MOF_INSURANCE_ROOT_CATEGORY + "\"}";
        var listRes = fetcher.fetch(source.getFetchUrl(), source.getAllowedHost(),
                SafeFetcher.ExpectedKind.JSON, listBody);
        int stored = 0;
        for (var art : capItems(source, parsers.parseMofList(listRes.body(), source.getFetchUrl()))) {
            var existing = rawDocs.findFirstByUrlOrderByIdAsc(art.url());
            if (existing.isPresent() && existing.get().isFullTextFetched()) continue;

            String fullText = null, note = null;
            try {
                String detailUrl = "https://" + source.getAllowedHost()
                        + "/api/article/getbyslug?slug=" + art.slug();
                var detail = fetcher.fetch(detailUrl, source.getAllowedHost(), SafeFetcher.ExpectedKind.JSON);
                fullText = parsers.parseMofContent(detail.body());
            } catch (Exception e) {
                note = "MOF detail fetch failed (" + truncateNote(e.getMessage()) + ") — dùng title+mô tả";
                log.warn("MOF_ISA detail lỗi [{}]: {}", art.slug(), e.getMessage());
            }
            // Fallback khi API chi tiết lỗi/rỗng: title + description (vẫn có ngày thật để lọc)
            boolean isFull = fullText != null && !fullText.isBlank();
            String text = isFull ? fullText
                    : (art.title() + (art.description().isBlank() ? "" : "\n\n" + art.description()));
            String hash = sha256(normalizeForHash(text));
            if (existing.isPresent()) {
                if (isFull) { existing.get().upgradeToFullText(hash, text, note); rawDocs.save(existing.get()); stored++; }
            } else if (!rawDocs.existsByContentHash(hash)) {
                RawDoc doc = new RawDoc(source, art.url(), art.title(), art.publishedAt(), Instant.now(),
                        hash, text, source.getLanguage(), RawDoc.ParseStatus.OK, note);
                if (isFull) doc.upgradeToFullText(hash, text, note);
                rawDocs.save(doc);
                stored++;
            }
        }
        return stored;
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
    /**
     * FWD_VN /vi/blog/ embed ~331 bài trong __NEXT_DATA__ (~7-8MB) — vượt cap mặc định 5MB.
     * Đã xác nhận thủ công đây là nội dung thật (không phải payload tấn công) — dùng biến thể
     * fetch() có maxBytesOverride CHỈ cho nguồn này, xem SafeFetcher.
     */
    private static final long FWD_VN_MAX_BYTES = 12L * 1024 * 1024;

    private int ingestHtml(Source source)
            throws SafeFetcher.FetchRejectedException, ContentParsers.ParseFailedException {
        if ("FWD_VN".equals(source.getCode())) {
            var result = fetcher.fetch(source.getFetchUrl(), source.getAllowedHost(),
                    SafeFetcher.ExpectedKind.HTML, null, FWD_VN_MAX_BYTES);
            return ingestListing(source, parsers.parseFwdVn(result.body(), source.getFetchUrl()));
        }
        var result = fetcher.fetch(source.getFetchUrl(), source.getAllowedHost(),
                SafeFetcher.ExpectedKind.HTML);
        return switch (source.getCode()) {
            case "IAV_VN" -> ingestListing(source, parsers.parseIav(result.body(), source.getFetchUrl()));
            case "AIA_VN" -> ingestListing(source, parsers.parseAia(result.body(), source.getFetchUrl()));
            case "MANULIFE_VN" -> ingestListing(source, parsers.parseManulife(result.body(), source.getFetchUrl()));
            case "PRUDENTIAL_VN" -> ingestListing(source, parsers.parsePrudential(result.body(), source.getFetchUrl()));
            case "MAP_LIFE" -> ingestListing(source, parsers.parseMapLife(result.body(), source.getFetchUrl()));
            case "FUBON_VN" -> ingestListing(source, parsers.parseFubonVn(result.body(), source.getFetchUrl()));
            case "CHUBB_VN" -> ingestListing(source, parsers.parseChubbVn(result.body(), source.getFetchUrl()));
            case "TBNH" -> ingestListing(source, parsers.parseTbnh(result.body(), source.getFetchUrl()));
            case "MB_AGEAS" -> ingestListing(source, parsers.parseMbAgeasPress(result.body(), source.getFetchUrl()));
            case "HANWHA_VN" -> ingestListing(source, parsers.parseHanwhaVn(result.body(), source.getFetchUrl()));
            case "PRU_HK" -> ingestListing(source, parsers.parsePruHk(result.body(), source.getFetchUrl()));
            case "AIA_HK" -> ingestListing(source, parsers.parseAiaHk(result.body(), source.getFetchUrl()));
            case "FSS_KR" -> ingestListing(source, parsers.parseFssKr(result.body(), source.getFetchUrl()));
            case "CHINALIFE_HK" -> ingestListing(source, parsers.parseChinaLifeHk(result.body(), source.getFetchUrl()));
            case "GREAT_EASTERN" -> ingestListing(source, parsers.parseGreatEastern(result.body(), source.getFetchUrl()));
            case "PHU_HUNG_LIFE" -> ingestListing(source, parsers.parsePhuHungLife(result.body(), source.getFetchUrl()));
            default -> {
                var parsed = parsers.parseHtml(result.body());
                yield storeIfNew(source, source.getFetchUrl(), parsed.title(), null, parsed.text()) ? 1 : 0;
            }
        };
    }

    /**
     * Cap số item lấy mỗi nguồn mỗi lần chạy (mặc định 25, config max-items-per-source).
     * Nhiều feed/listing trả NGUYÊN archive (FSC_TW ~800 item nhiều năm tuổi) — không cap thì
     * mỗi doc cũ đó lại tốn classify (3 LLM call) + phình cặp so sánh dedup O(n²).
     * Parser trả item MỚI NHẤT trước (thứ tự trang/feed gốc) nên cap = lấy tin mới nhất.
     */
    private <T> java.util.List<T> capItems(Source source, java.util.List<T> items) {
        if (items.size() <= maxItemsPerSource) return items;
        log.info("Cap ingest [{}]: {} item → giữ {} mới nhất", source.getCode(), items.size(), maxItemsPerSource);
        return items.subList(0, maxItemsPerSource);
    }

    /**
     * Batch 9 (feedback Hanh): trước đây chỉ lưu TIÊU ĐỀ của từng item listing —
     * fact/claim sinh ra mỏng và link đôi khi dừng ở trang listing. Giờ fetch
     * TOÀN VĂN từng bài theo link (vẫn qua SafeFetcher, CHỈ khi link cùng
     * allowedHost — không mở rộng whitelist ngầm), lưu URL bài chính xác.
     * Fetch bài lỗi → fallback lưu title-only như cũ (fail loud vào note).
     *
     * Fix Hanh 2026-07-14: check cũ "URL đã tồn tại + OK" khiến doc title-only
     * TỪ TRƯỚC KHI có full-text fetch không bao giờ được backfill — vì URL đó
     * đã "tồn tại + OK" ngay từ lần ingest headline-only đầu tiên. Giờ check
     * đúng field fullTextFetched: doc cũ (mặc định false) → backfill TẠI CHỖ
     * (upgradeToFullText, KHÔNG insert row mới — tránh tự tạo cặp trùng URL).
     */
    private int ingestListing(Source source, java.util.List<ContentParsers.ListingItem> listing) {
        int stored = 0;
        for (var item : capItems(source, listing)) {
            String link = item.link() == null ? source.getFetchUrl() : item.link();
            var existing = rawDocs.findFirstByUrlOrderByIdAsc(link);
            if (existing.isPresent() && existing.get().isFullTextFetched()) continue; // đã có toàn văn — khỏi fetch lại

            String linkHost = safeHost(link);
            String fullText = null, note = null;
            if (linkHost != null && linkHost.equalsIgnoreCase(source.getAllowedHost())) {
                try {
                    var art = fetcher.fetch(link, source.getAllowedHost(), SafeFetcher.ExpectedKind.HTML);
                    fullText = parsers.parseHtml(art.body()).text();
                } catch (Exception e) {
                    note = "Full-text fetch failed (" + truncateNote(e.getMessage()) + ") — kept title-only";
                    log.warn("Full-article fetch lỗi [{}] {}: {}", source.getCode(), link, e.getMessage());
                }
            } else {
                note = "Article link points outside the whitelist (" + linkHost + ") — title-only";
            }

            if (fullText == null || fullText.isBlank()) {
                if (existing.isEmpty() && storeIfNew(source, link, item.title(), item.publishedAt(), item.title(), note)) stored++;
                // existing nhưng vẫn chưa fetch được toàn văn (vd mạng lỗi lần này) → giữ nguyên, thử lại lần ingest sau
                continue;
            }
            String hash = sha256(normalizeForHash(fullText));
            if (existing.isPresent()) {
                existing.get().upgradeToFullText(hash, fullText, note);
                rawDocs.save(existing.get());
                stored++;
            } else if (!rawDocs.existsByContentHash(hash)) {
                RawDoc doc = new RawDoc(source, link, item.title(), item.publishedAt(), Instant.now(),
                        hash, fullText, source.getLanguage(), RawDoc.ParseStatus.OK, note);
                doc.upgradeToFullText(hash, fullText, note);
                rawDocs.save(doc);
                stored++;
            }
        }
        return stored;
    }

    private static String truncateNote(String s) {
        return s == null ? "?" : (s.length() <= 120 ? s : s.substring(0, 120) + "…");
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
        for (var item : capItems(source, parsers.parseRss(result.body()))) {
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
        String hash = sha256(normalizeForHash(text));
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

    /**
     * Chỉ chuẩn hoá chuỗi dùng để HASH (rawText lưu DB vẫn giữ nguyên — invariant
     * "GIỮ NGUYÊN ngôn ngữ gốc"). Gộp mọi khoảng trắng liên tiếp (space/tab/newline)
     * thành 1 space + trim, để bản đăng lại chỉ khác nhau ở xuống dòng/khoảng trắng
     * (không đổi câu chữ) vẫn khớp hash — khỏi rơi vào vùng xám Jaccard tốn LLM pairwise.
     */
    private static String normalizeForHash(String text) {
        if (text == null) return "";
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
                .strip().replaceAll("\\s+", " ");
    }

    private static String safeHost(String url) {
        try { return URI.create(url).getHost(); } catch (Exception e) { return null; }
    }
}
