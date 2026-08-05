package com.marketradar.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import com.marketradar.domain.PipelineItemLog;
import com.marketradar.domain.RawDoc;
import com.marketradar.domain.Source;
import com.marketradar.fetch.SafeFetcher;
import com.marketradar.fetch.SourceFetchOverrides;
import com.marketradar.intake.DocumentMetadataDetector;
import com.marketradar.parse.ContentParsers;
import com.marketradar.repo.PipelineItemLogRepository;
import com.marketradar.repo.RawDocRepository;
import com.marketradar.repo.SourceRepository;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

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
    private final PipelineRunStatusService progress;
    private final PipelineItemLogRepository itemLogs;

    /** Optional audit/repair selector. Blank means the normal full active registry. */
    @Value("${marketradar.ingest.source-codes:}")
    private String sourceCodesFilter;

    public IngestionJob(SourceRepository sources, RawDocRepository rawDocs,
                        SafeFetcher fetcher, ContentParsers parsers,
                        @Value("${marketradar.ingest.enabled:false}") boolean scheduledEnabled,
                        @Value("${marketradar.ingest.max-items-per-source:25}") int maxItemsPerSource,
                        PipelineRunStatusService progress, PipelineItemLogRepository itemLogs) {
        this.sources = sources;
        this.rawDocs = rawDocs;
        this.fetcher = fetcher;
        this.parsers = parsers;
        this.scheduledEnabled = scheduledEnabled;
        this.maxItemsPerSource = maxItemsPerSource;
        this.progress = progress;
        this.itemLogs = itemLogs;
    }

    @Scheduled(fixedDelayString = "${marketradar.ingest.fixed-delay-ms:900000}")
    public void scheduledRun() {
        if (!scheduledEnabled) return; // demo dùng chạy tay để deterministic
        runOnce();
    }

    /** Chạy một vòng ingest cho toàn bộ nguồn active. Trả về summary text cho endpoint tay. */
    public String runOnce() {
        StringBuilder summary = new StringBuilder();
        java.util.Set<String> selectedCodes = sourceCodesFilter == null
                ? java.util.Set.of()
                : java.util.Arrays.stream(sourceCodesFilter.split(","))
                .map(String::strip).filter(value -> !value.isBlank())
                .map(value -> value.toUpperCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<Source> active = sources.findByActiveTrue().stream()
                .filter(source -> selectedCodes.isEmpty() || selectedCodes.contains(source.getCode()))
                .toList();
        progress.startProgress("ingest", active.size());
        Long runLogId = progress.currentRunLogId("ingest");
        for (Source source : active) {
            try {
                int stored = ingestSource(source);
                summary.append(source.getCode()).append(": +").append(stored).append(" doc\n");
                logItem(runLogId, source, "OK", "+" + stored + " doc");
            } catch (SafeFetcher.FetchRejectedException e) {
                log.warn("FETCH REJECTED [{}]: {}", source.getCode(), e.getMessage());
                summary.append(source.getCode()).append(": REJECTED — ").append(e.getMessage()).append('\n');
                logItem(runLogId, source, "REJECTED", e.getMessage());
            } catch (ContentParsers.ParseFailedException e) {
                log.warn("PARSE FAILED [{}]: {}", source.getCode(), e.getMessage());
                recordFailure(source, source.getFetchUrl(),
                        RawDoc.ParseStatus.PARSE_ERROR, e.getMessage());
                summary.append(source.getCode()).append(": PARSE ERROR — ").append(e.getMessage()).append('\n');
                logItem(runLogId, source, "PARSE_ERROR", e.getMessage());
            } catch (Exception e) {
                log.error("UNEXPECTED [{}]", source.getCode(), e);
                summary.append(source.getCode()).append(": ERROR — ").append(e.getMessage()).append('\n');
                logItem(runLogId, source, "ERROR", e.getMessage());
            }
            progress.stepProgress("ingest");
        }
        return summary.toString();
    }

    private void logItem(Long runLogId, Source source, String status, String message) {
        if (runLogId == null) return; // an toàn nếu gọi runOnce() ngoài executor (vd test)
        itemLogs.save(new PipelineItemLog(runLogId, PipelineItemLog.ItemType.SOURCE,
                source.getCode(), source.getName(), null, status, message));
    }

    private int ingestSource(Source source)
            throws SafeFetcher.FetchRejectedException, ContentParsers.ParseFailedException {
        return switch (source.getType()) {
            case HTML -> ingestHtml(source);
            case RSS -> ingestRss(source);
            case SITEMAP -> ingestSitemap(source);
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
            case "MOF_INSURANCE_CYBER_RISK_2026" -> {
                var result = fetcher.fetch(source.getFetchUrl(), source.getAllowedHost(),
                        SafeFetcher.ExpectedKind.JSON);
                yield ingestListing(source, java.util.List.of(
                        parsers.parseMofDirectArticle(result.body(), source.getBrowseUrl())));
            }
            case "GENERALI_VN" -> {
                var result = fetcher.fetch(source.getFetchUrl(), source.getAllowedHost(),
                        SafeFetcher.ExpectedKind.JSON);
                yield ingestListing(source, parsers.parseGeneraliVn(result.body(), source.getFetchUrl()));
            }
            case "SHINHAN_VN" -> {
                var result = fetcher.fetch(source.getFetchUrl(), source.getAllowedHost(),
                        SafeFetcher.ExpectedKind.JSON);
                yield ingestShinhanVn(source, result.body());
            }
            case "CATHAY_VN" -> {
                var result = fetcher.fetch(source.getFetchUrl(), source.getAllowedHost(),
                        SafeFetcher.ExpectedKind.JSON, SourceFetchOverrides.postBodyFor("CATHAY_VN"));
                yield ingestListing(source, parsers.parseCathayVn(result.body(), source.getFetchUrl()));
            }
            case "HKIA" -> {
                // Body rỗng là đủ — xác nhận thủ công. baseUrl truyền cho parser là TRANG HTML
                // (không phải endpoint .php) vì url trả về tương đối theo "../../" tính từ đó.
                var result = fetcher.fetch(source.getFetchUrl(), source.getAllowedHost(),
                        SafeFetcher.ExpectedKind.JSON, SourceFetchOverrides.postBodyFor("HKIA"));
                yield ingestListing(source, parsers.parseHkia(result.body(), SourceFetchOverrides.HKIA_PAGE_URL));
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
            case "MUNICHRE" -> {
                var result = fetcher.fetch(source.getFetchUrl(), source.getAllowedHost(),
                        SafeFetcher.ExpectedKind.JSON);
                yield ingestListing(source, parsers.parseMunichRe(result.body(), source.getFetchUrl()));
            }
            case "BIDV_METLIFE_FINANCIALS" -> {
                var result = fetcher.fetch(source.getFetchUrl(), source.getAllowedHost(),
                        SafeFetcher.ExpectedKind.JSON);
                yield ingestFinancialListing(source,
                        parsers.parseBidvMetlifeFinancials(result.body(), source.getFetchUrl()));
            }
            default -> throw new ContentParsers.ParseFailedException(
                    "Nguồn JSON '" + source.getCode() + "' chưa có parser riêng");
        };
    }

    /**
     * Shinhan article pages are identical SPA shells. Resolve every allow-listed slug through
     * the publisher's public JSON content endpoint and pass embedded full text to the normal
     * storage path, so HTTP 200 on a shell can never be counted as an acquired article.
     */
    private int ingestShinhanVn(Source source, byte[] listingBody)
            throws ContentParsers.ParseFailedException {
        java.util.List<ContentParsers.ListingItem> enriched = new java.util.ArrayList<>();
        int attempted = 0;
        int titleOnlyStored = 0;
        for (var item : parsers.parseShinhanVn(listingBody, source.getFetchUrl())) {
            var existing = rawDocs.findFirstByUrlOrderByIdAsc(item.link());
            if (existing.isPresent() && existing.get().isFullTextFetched()) continue;
            if (attempted++ >= maxItemsPerSource) break;
            try {
                String path = URI.create(item.link()).getPath();
                String slug = path == null ? "" : path.replaceFirst("/$", "")
                        .substring(path.replaceFirst("/$", "").lastIndexOf('/') + 1);
                if (slug.isBlank()) throw new IllegalArgumentException("article slug is blank");
                String detailUrl = "https://" + source.getAllowedHost()
                        + "/api/v1/application/getContent/" + slug;
                var detail = fetcher.fetch(detailUrl, source.getAllowedHost(), SafeFetcher.ExpectedKind.JSON);
                enriched.add(parsers.parseShinhanVnDetail(detail.body(), item.link()));
            } catch (Exception e) {
                String note = "Shinhan API detail failed (" + truncateNote(e.getMessage())
                        + ") — kept title-only; SPA shell deliberately rejected";
                if (existing.isEmpty() && storeIfNew(source, item.link(), item.title(), item.publishedAt(),
                        item.title(), note)) titleOnlyStored++;
                log.warn("SHINHAN_VN API detail lỗi [{}]: {}", item.link(), e.getMessage());
            }
        }
        return titleOnlyStored + ingestListing(source, enriched);
    }

    /**
     * MOF_ISA: danh sách qua POST /api/article/reads (body rootCategoryId), rồi mỗi bài
     * lấy full text qua GET /api/article/getbyslug (article page là SPA nên KHÔNG fetch
     * HTML như ingestListing được — phải qua API chi tiết). publishedAt lấy từ
     * publicationTime của API (ngày THẬT — đúng thứ bộ lọc độ mới cần).
     */
    private int ingestMofIsa(Source source)
            throws SafeFetcher.FetchRejectedException, ContentParsers.ParseFailedException {
        var listRes = fetcher.fetch(source.getFetchUrl(), source.getAllowedHost(),
                SafeFetcher.ExpectedKind.JSON, SourceFetchOverrides.postBodyFor("MOF_ISA"));
        int stored = 0;
        int attempted = 0;
        for (var art : parsers.parseMofList(listRes.body(), source.getFetchUrl())) {
            var existing = rawDocs.findFirstByUrlOrderByIdAsc(art.url());
            if (existing.isPresent() && existing.get().isFullTextFetched()) {
                logIngestDocument(existing.get(), "UNCHANGED", "Full text already current");
                continue;
            }
            if (attempted++ >= maxItemsPerSource) break;

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
                if (isFull) {
                    existing.get().upgradeToFullText(hash, text, note);
                    rawDocs.save(existing.get());
                    logIngestDocument(existing.get(), "UPDATED", "Full text backfilled");
                    stored++;
                } else {
                    logIngestDocument(existing.get(), "UNCHANGED", "Existing document retained");
                }
            } else if (!rawDocs.existsByContentHash(hash)) {
                RawDoc doc = new RawDoc(source, art.url(), art.title(), art.publishedAt(), Instant.now(),
                        hash, text, source.getLanguage(), RawDoc.ParseStatus.OK, note);
                if (isFull) doc.upgradeToFullText(hash, text, note);
                doc = rawDocs.save(doc);
                logIngestDocument(doc, "NEW", "New document stored");
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
    private int ingestHtml(Source source)
            throws SafeFetcher.FetchRejectedException, ContentParsers.ParseFailedException {
        if (SourceFetchOverrides.usesReaderProxy(source.getCode())) {
            return ingestReaderProxy(source);
        }
        if ("FWD_VN".equals(source.getCode())) {
            var result = fetcher.fetch(source.getFetchUrl(), source.getAllowedHost(),
                    SafeFetcher.ExpectedKind.HTML, null, SourceFetchOverrides.FWD_VN_MAX_BYTES);
            return ingestListing(source, parsers.parseFwdVn(result.body(), source.getFetchUrl()));
        }
        var result = fetcher.fetch(source.getFetchUrl(), source.getAllowedHost(),
                SafeFetcher.ExpectedKind.HTML);
        return switch (source.getCode()) {
            case "IAV_VN", "IAV_LIFE_PRODUCTS", "IAV_LIFE_DISCLOSURES", "IAV_LIFE_ACTIVITIES" ->
                    ingestListing(source, parsers.parseIav(result.body(), source.getFetchUrl()));
            case "AIA_VN" -> ingestListing(source, parsers.parseAia(result.body(), source.getFetchUrl()));
            case "AIA_VN_NOTICES" -> ingestListing(source, parsers.parseAiaNotices(result.body(), source.getFetchUrl()));
            case "MANULIFE_VN" -> ingestListing(source, parsers.parseManulife(result.body(), source.getFetchUrl()));
            case "PRUDENTIAL_VN" -> ingestListing(source, parsers.parsePrudential(result.body(), source.getFetchUrl()));
            case "MAP_LIFE" -> ingestListing(source, parsers.parseMapLife(result.body(), source.getFetchUrl()));
            case "FUBON_VN" -> ingestListing(source, parsers.parseFubonVn(result.body(), source.getFetchUrl()));
            case "CHUBB_VN" -> ingestListing(source, parsers.parseChubbVn(result.body(), source.getFetchUrl()));
            case "TBNH" -> ingestListing(source, parsers.parseTbnh(result.body(), source.getFetchUrl()));
            case "HNX_GOVERNMENT_BONDS" -> ingestListing(source,
                    parsers.parseHnxGovernmentBondMonthly(result.body(), source.getFetchUrl()));
            case "SBV_MARKET_OPERATIONS" -> ingestListing(source,
                    parsers.parseSbvMarketOperations(result.body(), source.getFetchUrl()));
            case "TECHCOMBANK_IR_LIFE_RESULTS" -> ingestFinancialListing(source,
                    parsers.parseTechcombankLifeResults(result.body(), source.getFetchUrl()),
                    "Official bank investor-results document with life-insurance evidence");
            case "BIDV_METLIFE_AGENCY_2026", "VIETCOMBANK_FWD_DISTRIBUTION_2026",
                    "GOV_PERSONAL_DATA_INSURANCE_2026", "IAV_CHUBB_IGLOO_2026",
                    "FINANCE_RESEARCH_DATA_INSURANCE_2026",
                    "LUATVIETNAM_ONLINE_INSURANCE_DATA_2026" ->
                    ingestCuratedDirectArticle(source, result);
            case "THEINVESTOR_INSURANCE" -> ingestListing(source,
                    parsers.parseTheInvestorInsurance(result.body(), source.getFetchUrl()));
            case "DDD_FINANCIAL_SERVICES" -> ingestListing(source,
                    parsers.parseDddFinancialServices(result.body(), source.getFetchUrl()));
            case "AIA_GROUP_RESULTS" -> ingestVietnamMentionListing(source,
                    parsers.parseAiaGroupPress(result.body(), source.getFetchUrl()));
            case "AIA_VN_FINANCIALS", "PRUDENTIAL_VN_FINANCIALS", "MANULIFE_VN_FINANCIALS",
                    "MB_LIFE_FINANCIALS", "HANWHA_VN_FINANCIALS",
                    "CHUBB_VN_FINANCIALS", "FUBON_VN_FINANCIALS",
                    "MAP_LIFE_FINANCIALS", "MVI_LIFE_FINANCIALS",
                    "TECHCOM_LIFE_FINANCIALS", "DAIICHI_VN_FINANCIALS",
                    "PHU_HUNG_LIFE_FINANCIALS" -> ingestFinancialListing(source,
                    parsers.parseFinancialReportLinks(result.body(), source.getFetchUrl(), source.getCode()));
            case "FWD_VN_FINANCIALS" -> ingestFinancialListing(source,
                    parsers.parseFwdFinancialLinks(result.body()));
            case "GENERALI_VN_FINANCIALS" -> ingestFinancialListing(source,
                    parsers.parseGeneraliFinancialLinks(result.body(), source.getFetchUrl()));
            case "MVI_LIFE" -> ingestListing(source, parsers.parseMviLife(result.body(), source.getFetchUrl()));
            case "VIR_INSURANCE" -> ingestListing(source, parsers.parseVirInsurance(result.body(), source.getFetchUrl()));
            case "VIETNAMNET_LIFE" -> ingestListing(source, parsers.parseVietnamNetLife(result.body(), source.getFetchUrl()));
            case "TNCK_VN" -> ingestListing(source, parsers.parseTnckInsurance(result.body(), source.getFetchUrl()));
            case "VIETNAMPLUS_INSURANCE" -> ingestListing(source,
                    parsers.parseVietnamPlusInsurance(result.body(), source.getFetchUrl()));
            case "BAODAUTU_LIFE" -> ingestListing(source,
                    parsers.parseBaoDauTuLife(result.body(), source.getFetchUrl()));
            case "TBTCO_LIFE_SEARCH" -> ingestListing(source,
                    parsers.parseTbtcoLifeSearch(result.body(), source.getFetchUrl()));
            case "VIETNAMFINANCE_LIFE" -> ingestListing(source,
                    parsers.parseVietnamFinanceLife(result.body(), source.getFetchUrl()));
            case "BIZHUB_INSURANCE" -> ingestListing(source,
                    parsers.parseBizhubInsurance(result.body(), source.getFetchUrl()));
            case "BAOCHINHPHU_INSURANCE" -> ingestListing(source,
                    parsers.parseBaoChinhPhuInsurance(result.body(), source.getFetchUrl()));
            case "VNEXPRESS_INSURANCE" -> ingestListing(source,
                    parsers.parseVnExpressInsurance(result.body(), source.getFetchUrl()));
            case "VNECONOMY" -> ingestListing(source, parsers.parseVnEconomyInsurance(result.body(), source.getFetchUrl()));
            case "NSO_VN" -> ingestListing(source, parsers.parseNsoMonthly(result.body(), source.getFetchUrl()));
            case "MB_AGEAS" -> ingestListing(source, parsers.parseMbAgeasPress(result.body(), source.getFetchUrl()));
            case "HANWHA_VN" -> ingestListing(source, parsers.parseHanwhaVn(result.body(), source.getFetchUrl()));
            case "DAIICHI_VN" -> ingestListing(source, parsers.parseDaiichiVn(result.body(), source.getFetchUrl()));
            case "PRU_HK" -> ingestListing(source, parsers.parsePruHk(result.body(), source.getFetchUrl()));
            case "PRULIFE_PH" -> ingestListing(source, parsers.parsePruHk(result.body(), source.getFetchUrl()));
            case "AIA_HK" -> ingestListing(source, parsers.parseAiaHk(result.body(), source.getFetchUrl()));
            case "FSS_KR" -> ingestListing(source, parsers.parseFssKr(result.body(), source.getFetchUrl()));
            case "CHINALIFE_HK" -> ingestListing(source, parsers.parseChinaLifeHk(result.body(), source.getFetchUrl()));
            case "GREAT_EASTERN" -> ingestListing(source, parsers.parseGreatEastern(result.body(), source.getFetchUrl()));
            case "INCOME_SG" -> ingestListing(source, parsers.parseIncomeSg(result.body(), source.getFetchUrl()));
            case "FUBON_TW" -> ingestListing(source, parsers.parseFubonTw(result.body(), source.getFetchUrl()));
            case "SWISSRE_INST" -> ingestListing(source, parsers.parseSwissReInstitute(result.body(), source.getFetchUrl()));
            case "NAIC" -> ingestListing(source, parsers.parseNaic(result.body(), source.getFetchUrl()));
            case "PHU_HUNG_LIFE" -> ingestListing(source, parsers.parsePhuHungLife(result.body(), source.getFetchUrl()));
            default -> {
                var parsed = parsers.parseHtml(result.body());
                yield storeIfNew(source, source.getFetchUrl(), parsed.title(), null, parsed.text()) ? 1 : 0;
            }
        };
    }

    /**
     * A small number of deep-research discoveries are high-value official pages but do
     * not have a stable listing API. Keep them as explicit, auditable registry channels:
     * one URL, one publisher, one article, with deterministic metadata/full-text parsing.
     */
    private int ingestCuratedDirectArticle(Source source, SafeFetcher.FetchResult result)
            throws ContentParsers.ParseFailedException {
        var parsed = parsers.parseArticleHtml(result.body());
        var metadata = DocumentMetadataDetector.html(
                result.body(), parsed.text(), parsed.title(), source.getFetchUrl());
        String title = metadata.title() == null || metadata.title().isBlank()
                ? parsed.title() : metadata.title();
        Instant publishedAt = metadata.publishedDate() == null ? null
                : metadata.publishedDate().atStartOfDay(
                        java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
        return ingestListing(source, java.util.List.of(new ContentParsers.ListingItem(
                title, source.getFetchUrl(), publishedAt, parsed.text())));
    }

    /**
     * Explicit fallback for official publisher sites whose WAF blocks direct server HTTP.
     * Discovery and article URLs remain on the official host; r.jina.ai is only the bounded,
     * allow-listed transport.  This is intentionally not a generic proxy path.
     */
    private int ingestReaderProxy(Source source)
            throws SafeFetcher.FetchRejectedException, ContentParsers.ParseFailedException {
        var first = fetchReader(source.getFetchUrl());
        java.util.LinkedHashMap<String, ContentParsers.ListingItem> unique = new java.util.LinkedHashMap<>();
        if ("SUNLIFE_VN_FINANCIALS".equals(source.getCode())) {
            return ingestFinancialListing(source, parsers.parseSunLifeFinancialReader(first.body()));
        } else if ("BVNT_FINANCIALS".equals(source.getCode())) {
            return ingestFinancialListing(source, parsers.parseReaderFinancialLinks(
                    first.body(), source.getAllowedHost(), source.getCode()));
        } else if ("SHINHAN_VN_FINANCIALS".equals(source.getCode())) {
            return ingestFinancialListing(source, parsers.parseShinhanFinancialReader(first.body()));
        } else if ("BIZHUB_INSURANCE".equals(source.getCode())) {
            for (var item : parsers.parseBizhubReaderListing(first.body())) {
                unique.putIfAbsent(item.link(), item);
            }
        } else if ("SUNLIFE_VN".equals(source.getCode())) {
            for (var item : parsers.parseSunLifeReaderListing(first.body())) unique.putIfAbsent(item.link(), item);
            for (String page : parsers.parseSunLifeReaderPagination(first.body())) {
                try {
                    var pageResult = fetchReader(page);
                    for (var item : parsers.parseSunLifeReaderListing(pageResult.body())) {
                        unique.putIfAbsent(item.link(), item);
                    }
                } catch (Exception e) {
                    log.warn("Reader archive page lỗi [{}] {}: {}", source.getCode(), page, e.getMessage());
                }
            }
        } else if ("BVNT".equals(source.getCode())) {
            for (var item : parsers.parseBaoVietReaderListing(first.body())) unique.putIfAbsent(item.link(), item);
        } else if ("BAOVIET_HOLDINGS_NEWS".equals(source.getCode())) {
            for (var item : parsers.parseBaoVietHoldingsReaderListing(first.body())) {
                unique.putIfAbsent(item.link(), item);
            }
            // Six recent pages cover the current reporting year without walking the 80+
            // page historical archive on every cycle. Existing full-text rows are skipped.
            for (int page = 0; page < 6; page++) {
                String pageUrl = source.getFetchUrl() + "?page=" + page;
                try {
                    var pageResult = fetchReader(pageUrl);
                    for (var item : parsers.parseBaoVietHoldingsReaderListing(pageResult.body())) {
                        unique.putIfAbsent(item.link(), item);
                    }
                } catch (Exception e) {
                    log.warn("Reader archive page lỗi [{}] {}: {}", source.getCode(), pageUrl, e.getMessage());
                }
            }
        } else {
            throw new ContentParsers.ParseFailedException(
                    "Reader proxy configured without a source-specific parser: " + source.getCode());
        }

        int attempted = 0;
        int titleOnlyStored = 0;
        java.util.List<ContentParsers.ListingItem> enriched = new java.util.ArrayList<>();
        for (var item : unique.values()) {
            var existing = rawDocs.findFirstByUrlOrderByIdAsc(item.link());
            if (existing.isPresent() && existing.get().isFullTextFetched()) continue;
            if (attempted++ >= maxItemsPerSource) break;
            String officialHost = safeHost(item.link());
            if (!source.getAllowedHost().equalsIgnoreCase(officialHost)) {
                log.warn("Reader article outside source whitelist [{}] {}", source.getCode(), item.link());
                continue;
            }
            try {
                var detail = fetchReader(item.link());
                var parsed = parsers.parseReaderArticle(detail.body());
                enriched.add(new ContentParsers.ListingItem(
                        parsed.title() == null || parsed.title().isBlank() ? item.title() : parsed.title(),
                        item.link(), item.publishedAt() == null ? parsed.publishedAt() : item.publishedAt(),
                        parsed.text()));
            } catch (Exception e) {
                String note = "Reader full-text fetch failed (" + truncateNote(e.getMessage()) + ") — kept title-only";
                if (existing.isEmpty() && storeIfNew(source, item.link(), item.title(), item.publishedAt(),
                        item.title(), note)) titleOnlyStored++;
                log.warn("Reader article fetch lỗi [{}] {}: {}", source.getCode(), item.link(), e.getMessage());
            }
        }
        int fullTextStored = ingestListing(source, enriched);
        return titleOnlyStored + fullTextStored;
    }

    private SafeFetcher.FetchResult fetchReader(String officialUrl)
            throws SafeFetcher.FetchRejectedException {
        return fetcher.fetch(SourceFetchOverrides.readerUrl(officialUrl),
                SourceFetchOverrides.READER_PROXY_HOST, SafeFetcher.ExpectedKind.TEXT);
    }

    /**
     * Parent IR pages are high quality but must not pollute Vietnam competitor coverage with
     * group-wide or other-country figures. Fetch candidates, then admit only article bodies
     * where Vietnam appears close to a performance metric. Source identity remains the parent.
     */
    private int ingestVietnamMentionListing(Source source,
            java.util.List<ContentParsers.ListingItem> listing) {
        java.util.List<ContentParsers.ListingItem> qualified = new java.util.ArrayList<>();
        int attempted = 0;
        for (var item : listing) {
            var existing = rawDocs.findFirstByUrlOrderByIdAsc(item.link());
            if (existing.isPresent() && existing.get().isFullTextFetched()) continue;
            if (attempted++ >= maxItemsPerSource) break;
            String host = articleFetchHost(source, safeHost(item.link()));
            if (host == null) continue;
            try {
                var response = fetcher.fetch(item.link(), host, SafeFetcher.ExpectedKind.HTML);
                var article = parsers.parseArticleHtml(response.body());
                if (!containsVietnamPerformanceEvidence(article.text())) continue;
                var metadata = DocumentMetadataDetector.html(
                        response.body(), article.text(), article.title(), item.link());
                String title = metadata.title() == null || metadata.title().isBlank()
                        ? item.title() : metadata.title();
                Instant date = item.publishedAt();
                if (date == null && metadata.publishedDate() != null) {
                    date = metadata.publishedDate().atStartOfDay(
                            java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
                }
                qualified.add(new ContentParsers.ListingItem(title, item.link(), date, article.text()));
            } catch (Exception e) {
                log.warn("Parent IR candidate fetch lỗi [{}] {}: {}",
                        source.getCode(), item.link(), e.getMessage());
            }
        }
        return ingestListing(source, qualified);
    }

    private static boolean containsVietnamPerformanceEvidence(String text) {
        if (text == null || text.isBlank()) return false;
        String folded = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(java.util.Locale.ROOT);
        String metric = "(?:growth|new business|vonb|premium|sales|profit|performance|doanh thu|loi nhuan)";
        String vietnam = "(?:vietnam|viet nam)";
        return java.util.regex.Pattern.compile("(?s)" + metric + ".{0,240}" + vietnam
                        + "|" + vietnam + ".{0,240}" + metric)
                .matcher(folded).find();
    }

    private int ingestSitemap(Source source)
            throws SafeFetcher.FetchRejectedException, ContentParsers.ParseFailedException {
        var result = fetcher.fetch(source.getFetchUrl(), source.getAllowedHost(),
                SafeFetcher.ExpectedKind.SITEMAP);
        String pathFilter = switch (source.getCode()) {
            case "TECHCOM_LIFE" -> "/news/";
            default -> null;
        };
        return ingestListing(source, parsers.parseSitemap(result.body(), pathFilter));
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
        int attempted = 0;
        for (var item : listing) {
            String link = item.link() == null ? source.getFetchUrl() : item.link();
            var existing = rawDocs.findFirstByUrlOrderByIdAsc(link);
            if (existing.isPresent() && existing.get().isFullTextFetched()) {
                logIngestDocument(existing.get(), "UNCHANGED", "Full text already current");
                continue; // đã có toàn văn — khỏi fetch lại
            }
            // The budget applies to work still needed, not to the first N listing rows.
            // Repeated cycles can therefore walk backwards through an archive instead
            // of looking at the same already-stored top 25 forever.
            if (attempted++ >= maxItemsPerSource) break;

            String linkHost = safeHost(link);
            String fetchHost = articleFetchHost(source, linkHost);
            String fullText = item.embeddedText();
            String note = fullText == null || fullText.isBlank()
                    ? null : "Full text extracted from listing/API payload";
            String resolvedTitle = item.title();
            Instant resolvedPublishedAt = item.publishedAt();
            if ((fullText == null || fullText.isBlank()) && fetchHost != null) {
                try {
                    var art = fetcher.fetch(link, fetchHost, SafeFetcher.ExpectedKind.HTML);
                    // Fix 2026-07-15 (audit): parseArticleHtml thay parseHtml — trước đây rawText
                    // là Document.text() NGUYÊN TRANG (menu/footer chiếm ~2.5k ký tự đầu), extractor
                    // đọc 6k ký tự đầu → toàn boilerplate. Giờ chỉ giữ khối nội dung chính.
                    var parsed = "PHU_HUNG_LIFE".equals(source.getCode())
                            ? parsers.parsePhuHungDetail(art.body())
                            : parsers.parseArticleHtml(art.body());
                    fullText = parsed.text();
                    note = parsed.note();
                    var metadata = DocumentMetadataDetector.html(
                            art.body(), fullText, parsed.title(), link);
                    if (metadata.title() != null && !metadata.title().isBlank()) {
                        resolvedTitle = metadata.title();
                    }
                    if (resolvedPublishedAt == null && metadata.publishedDate() != null) {
                        resolvedPublishedAt = metadata.publishedDate()
                                .atStartOfDay(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
                    }
                } catch (Exception e) {
                    note = "Full-text fetch failed (" + truncateNote(e.getMessage()) + ") — kept title-only";
                    log.warn("Full-article fetch lỗi [{}] {}: {}", source.getCode(), link, e.getMessage());
                }
            } else if (fullText == null || fullText.isBlank()) {
                note = "Article link points outside the whitelist (" + linkHost + ") — title-only";
            }

            if (fullText == null || fullText.isBlank()) {
                if (existing.isEmpty() && storeIfNew(source, link, resolvedTitle, resolvedPublishedAt, item.title(), note)) stored++;
                // existing nhưng vẫn chưa fetch được toàn văn (vd mạng lỗi lần này) → giữ nguyên, thử lại lần ingest sau
                continue;
            }
            String hash = sha256(normalizeForHash(fullText));
            if (existing.isPresent()) {
                existing.get().upgradeToFullText(hash, fullText, note, resolvedTitle, resolvedPublishedAt);
                rawDocs.save(existing.get());
                logIngestDocument(existing.get(), "UPDATED", "Full text backfilled");
                stored++;
            } else if (!rawDocs.existsByContentHash(hash)) {
                RawDoc doc = new RawDoc(source, link, resolvedTitle, resolvedPublishedAt, Instant.now(),
                        hash, fullText, source.getLanguage(), RawDoc.ParseStatus.OK, note);
                doc.upgradeToFullText(hash, fullText, note);
                doc = rawDocs.save(doc);
                logIngestDocument(doc, "NEW", "New document stored");
                stored++;
            }
        }
        return stored;
    }

    /**
     * Statutory reports need a different acquisition path from ordinary HTML articles:
     * listing pages may point straight to PDF, to a same-host PDF.js wrapper, or to a
     * clean URL that responds with application/pdf (Generali).  Every hop remains on the
     * source host or one exact per-source document host declared in TargetedRefetchPolicy.
     */
    private int ingestFinancialListing(Source source,
            java.util.List<ContentParsers.ListingItem> initial) {
        return ingestFinancialListing(source, initial, "Official statutory financial report");
    }

    private int ingestFinancialListing(Source source,
            java.util.List<ContentParsers.ListingItem> initial, String evidenceLabel) {
        java.util.ArrayDeque<ContentParsers.ListingItem> queue = new java.util.ArrayDeque<>(initial);
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        int stored = 0;
        int attempted = 0;
        while (!queue.isEmpty() && attempted < maxItemsPerSource) {
            var item = queue.removeFirst();
            if (item.link() == null || !seen.add(item.link())) continue;
            var existing = rawDocs.findFirstByUrlOrderByIdAsc(item.link());
            if (existing.isPresent() && existing.get().isFullTextFetched()) {
                logIngestDocument(existing.get(), "UNCHANGED", evidenceLabel + " already current");
                continue;
            }
            String host = articleFetchHost(source, safeHost(item.link()));
            if (host == null) {
                log.warn("Financial document outside explicit whitelist [{}] {}",
                        source.getCode(), item.link());
                continue;
            }
            attempted++;
            try {
                var response = fetchFinancialAsset(item.link(), host);
                if (!"application/pdf".equalsIgnoreCase(response.contentType())) {
                    // One bounded intermediary hop (Dai-ichi PDF.js wrapper).  Do not
                    // store wrapper/navigation text as if it were financial evidence.
                    for (var nested : parsers.parseFinancialReportLinks(
                            response.body(), item.link(), source.getCode())) {
                        if (!seen.contains(nested.link())) queue.addLast(new ContentParsers.ListingItem(
                                nested.title(), nested.link(),
                                nested.publishedAt() == null ? item.publishedAt() : nested.publishedAt()));
                    }
                    continue;
                }
                var parsed = parsers.parsePdf(response.body());
                var metadata = DocumentMetadataDetector.pdf(response.body(), parsed.text(), item.link());
                String title = item.title() == null || item.title().isBlank()
                        ? metadata.title() : item.title();
                Instant publishedAt = item.publishedAt() == null
                        ? response.lastModified() : item.publishedAt();
                String note = evidenceLabel + "; publication date "
                        + (item.publishedAt() != null ? "from publisher listing"
                        : response.lastModified() != null ? "from HTTP Last-Modified"
                        : "unavailable (accounting period was not substituted)");
                if (parsed.note() != null && !parsed.note().isBlank()) note += "; " + parsed.note();
                String hash = sha256(normalizeForHash(parsed.text()));
                existing = rawDocs.findFirstByUrlOrderByIdAsc(item.link());
                if (existing.isPresent()) {
                    existing.get().upgradeToFullText(hash, parsed.text(), note, title, publishedAt);
                    rawDocs.save(existing.get());
                    logIngestDocument(existing.get(), "UPDATED", evidenceLabel + " full text backfilled");
                    stored++;
                } else if (!rawDocs.existsByContentHash(hash)) {
                    RawDoc doc = new RawDoc(source, item.link(), title, publishedAt, Instant.now(),
                            hash, parsed.text(), source.getLanguage(), RawDoc.ParseStatus.OK, note);
                    doc.upgradeToFullText(hash, parsed.text(), note);
                    doc = rawDocs.save(doc);
                    logIngestDocument(doc, "NEW", evidenceLabel + " stored with extracted text");
                    stored++;
                }
            } catch (Exception e) {
                log.warn("Evidence-document fetch/parse failed [{}] {}: {}",
                        source.getCode(), item.link(), e.getMessage());
            }
        }
        return stored;
    }

    private SafeFetcher.FetchResult fetchFinancialAsset(String link, String allowedHost)
            throws SafeFetcher.FetchRejectedException {
        String path;
        try { path = URI.create(link).getPath().toLowerCase(java.util.Locale.ROOT); }
        catch (Exception ignored) { path = ""; }
        if (path.endsWith(".pdf")) {
            return fetcher.fetch(link, allowedHost, SafeFetcher.ExpectedKind.PDF,
                    null, 30L * 1024 * 1024);
        }
        try {
            return fetcher.fetch(link, allowedHost, SafeFetcher.ExpectedKind.HTML);
        } catch (SafeFetcher.FetchRejectedException mismatch) {
            if (mismatch.getMessage() != null
                    && mismatch.getMessage().contains("does not match source type HTML")) {
                return fetcher.fetch(link, allowedHost, SafeFetcher.ExpectedKind.PDF,
                        null, 30L * 1024 * 1024);
            }
            throw mismatch;
        }
    }

    private static String truncateNote(String s) {
        return s == null ? "?" : (s.length() <= 120 ? s : s.substring(0, 120) + "…");
    }

    /**
     * Fix 2026-07-15 (audit — backfill nguồn title-only): một số nguồn đăng listing trên host chính
     * nhưng BÀI VIẾT nằm ở host thứ cấp cố định (Chubb → chubb.mediaroom.com; Dai-ichi →
     * kh.dai-ichi-life.com.vn). Whitelist host thứ cấp đó TƯỜNG MINH theo từng nguồn — vẫn
     * one-hop, vẫn qua SafeFetcher với expected-host, KHÔNG phải mở whitelist chung chung.
     */
    /** Host được phép fetch bài chi tiết cho link này, hoặc null nếu link ngoài whitelist. */
    static String articleFetchHost(Source source, String linkHost) {
        return TargetedRefetchPolicy.articleFetchHost(
                source.getCode(), source.getAllowedHost(), linkHost);
    }

    /**
     * Fix 2026-07-15 (audit — backfill kho hiện có): mọi doc ĐÃ fullTextFetched trước fix
     * parseArticleHtml đang giữ rawText NGUYÊN TRANG (menu/footer lẫn bài). Chạy tay một lần:
     * re-fetch từng URL bài (vẫn SafeFetcher + đúng host whitelist/override), re-parse bằng
     * parseArticleHtml, cập nhật TẠI CHỖ. Doc có fact rồi vẫn an toàn — EvidenceFact giữ
     * spanText riêng, không đọc lại rawText; doc CHƯA extract sẽ được extractor đọc bản sạch.
     * Fail loud từng doc (log + note), không dừng cả vòng vì một bài lỗi.
     */
    @Deprecated(forRemoval = true)
    public String refetchFullTextOnce() {
        throw new UnsupportedOperationException(
                "Broad refetch is retired; use GET /pipeline/refetch/plan.json then "
                        + "POST /pipeline/refetch/execute.json with explicit rawDocIds (max 25) and confirm=true");
    }

    private int ingestPdf(Source source)
            throws SafeFetcher.FetchRejectedException, ContentParsers.ParseFailedException {
        var result = fetcher.fetch(source.getFetchUrl(), source.getAllowedHost(),
                SafeFetcher.ExpectedKind.PDF);
        var parsed = parsers.parsePdf(result.body());
        var metadata = DocumentMetadataDetector.pdf(result.body(), parsed.text(), source.getFetchUrl());
        String title = metadata.title() == null || metadata.title().isBlank()
                ? parsed.title() : metadata.title();
        Instant publishedAt = metadata.publishedDate() == null ? null
                : metadata.publishedDate().atStartOfDay(
                        java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
        return storeFullTextIfNew(source, source.getFetchUrl(), title, publishedAt,
                parsed.text(), parsed.note()) ? 1 : 0;
    }

    /**
     * RSS batch 1: lưu title + description của entry làm rawText.
     * Fix 2026-07-15 (audit — nguồn RSS toàn doc 46-300 ký tự): giờ fetch TOÀN VĂN bài theo
     * link entry, cùng cơ chế/ràng buộc với ingestListing (chỉ link cùng allowedHost hoặc
     * override tường minh; qua SafeFetcher; bài lỗi → fallback title+description như cũ,
     * fail loud vào note). Doc cũ title+desc được backfill TẠI CHỖ (upgradeToFullText).
     */
    private int ingestRss(Source source)
            throws SafeFetcher.FetchRejectedException, ContentParsers.ParseFailedException {
        var result = fetcher.fetch(source.getFetchUrl(), source.getAllowedHost(),
                SafeFetcher.ExpectedKind.RSS);
        int stored = 0;
        int attempted = 0;
        var feedItems = parsers.parseRss(result.body());
        if ("BNEWS_FINANCE_INSURANCE".equals(source.getCode())) {
            feedItems = parsers.selectVietnamLifeInsurance(feedItems);
        }
        for (var item : feedItems) {
            String link = item.link() == null ? source.getFetchUrl() : item.link();
            var existing = rawDocs.findFirstByUrlOrderByIdAsc(link);
            if (existing.isPresent() && existing.get().isFullTextFetched()) {
                logIngestDocument(existing.get(), "UNCHANGED", "Full text already current");
                continue;
            }
            if (attempted++ >= maxItemsPerSource) break;

            String linkHost = safeHost(link);
            String fetchHost = articleFetchHost(source, linkHost);
            String fullText = null, note = null;
            String resolvedTitle = item.title();
            Instant resolvedPublishedAt = item.publishedAt();
            if (fetchHost != null) {
                try {
                    var article = fetcher.fetch(link, fetchHost, SafeFetcher.ExpectedKind.HTML);
                    var parsed = parsers.parseArticleHtml(article.body());
                    fullText = parsed.text();
                    note = parsed.note();
                    var metadata = DocumentMetadataDetector.html(article.body(), fullText, parsed.title(), link);
                    if (metadata.title() != null && !metadata.title().isBlank()) resolvedTitle = metadata.title();
                    if (resolvedPublishedAt == null && metadata.publishedDate() != null) {
                        resolvedPublishedAt = metadata.publishedDate()
                                .atStartOfDay(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
                    }
                } catch (Exception e) {
                    note = "Full-text fetch failed (" + truncateNote(e.getMessage()) + ") — kept title+description";
                    log.warn("Full-article fetch lỗi (RSS) [{}] {}: {}", source.getCode(), link, e.getMessage());
                }
            } else {
                note = "Link entry trỏ host ngoài whitelist (" + linkHost + ") — chỉ lưu metadata feed";
            }

            if (fullText == null || fullText.isBlank()) {
                String text = item.title() + "\n\n" + item.descriptionText();
                if (existing.isEmpty() && storeIfNew(source, link, resolvedTitle, resolvedPublishedAt, text, note)) stored++;
                continue;
            }
            String hash = sha256(normalizeForHash(fullText));
            if (existing.isPresent()) {
                existing.get().upgradeToFullText(hash, fullText, note, resolvedTitle, resolvedPublishedAt);
                rawDocs.save(existing.get());
                logIngestDocument(existing.get(), "UPDATED", "Full text backfilled");
                stored++;
            } else if (!rawDocs.existsByContentHash(hash)) {
                RawDoc doc = new RawDoc(source, link, resolvedTitle, resolvedPublishedAt, Instant.now(),
                        hash, fullText, source.getLanguage(), RawDoc.ParseStatus.OK, note);
                doc.upgradeToFullText(hash, fullText, note);
                doc = rawDocs.save(doc);
                logIngestDocument(doc, "NEW", "New document stored");
                stored++;
            }
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
            rawDocs.findFirstByUrlOrderByIdAsc(url)
                    .ifPresent(doc -> logIngestDocument(doc, "UNCHANGED", "Duplicate content retained"));
            return false;
        }
        RawDoc saved = rawDocs.save(new RawDoc(source, url, title, publishedAt, Instant.now(),
                hash, text, source.getLanguage(), RawDoc.ParseStatus.OK, note));
        logIngestDocument(saved, "NEW", "New document stored");
        return true;
    }

    /** A directly fetched PDF already contains the complete bounded/OCR-extracted body. */
    private boolean storeFullTextIfNew(Source source, String url, String title,
                                       Instant publishedAt, String text, String note) {
        String hash = sha256(normalizeForHash(text));
        if (rawDocs.existsByContentHash(hash)) {
            log.debug("Dedup hash trùng, bỏ qua: {}", url);
            rawDocs.findFirstByUrlOrderByIdAsc(url)
                    .ifPresent(doc -> logIngestDocument(doc, "UNCHANGED", "Duplicate content retained"));
            return false;
        }
        RawDoc doc = new RawDoc(source, url, title, publishedAt, Instant.now(),
                hash, text, source.getLanguage(), RawDoc.ParseStatus.OK, note);
        doc.upgradeToFullText(hash, text, note);
        RawDoc saved = rawDocs.save(doc);
        logIngestDocument(saved, "NEW", "New full-text PDF stored");
        return true;
    }

    private void recordFailure(Source source, String url, RawDoc.ParseStatus status, String reason) {
        String hash = sha256("FAILURE:" + url + ":" + Instant.now());
        RawDoc failed = rawDocs.save(new RawDoc(source, url, null, null, Instant.now(),
                hash, null, source.getLanguage(), status, reason));
        logIngestDocument(failed, "PARSE_FAILED", reason);
    }

    /**
     * Document-level ingest events make the cycle trail historically truthful.
     * Source-level records are retained above for fetch diagnostics; this record
     * is deliberately in addition to them, not a replacement.
     */
    private void logIngestDocument(RawDoc doc, String status, String message) {
        Long runLogId = progress.currentRunLogId("ingest");
        if (runLogId == null || doc == null || doc.getId() == null) return;
        itemLogs.save(new PipelineItemLog(runLogId, PipelineItemLog.ItemType.RAW_DOC,
                String.valueOf(doc.getId()), doc.getTitle(), doc.getId(), status, message));
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
