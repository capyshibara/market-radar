package com.marketradar.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.EvidenceFact.FactType;
import com.marketradar.domain.RawDoc;
import com.marketradar.domain.Source;
import com.marketradar.domain.Category;
import com.marketradar.domain.Department;
import com.marketradar.domain.RoutingRule;
import com.marketradar.repo.EvidenceFactRepository;
import com.marketradar.repo.RawDocRepository;
import com.marketradar.repo.RoutingRuleRepository;
import com.marketradar.repo.SourceRepository;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Seed cho batch 1:
 *  (a) 5 nguồn curated — ⚠️ fetchUrl là PLACEHOLDER soạn offline, PHẢI verify
 *      bằng tay khi có mạng trước demo (cờ urlUnverified = true).
 *  (b) Fact mẫu ĐẶT TAY (sampleData = true) để template report hiển thị được ngay
 *      — toàn bộ công ty/sản phẩm trong fact mẫu là HƯ CẤU, chỉ minh hoạ cấu trúc.
 */
@Component
public class SeedData implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedData.class);

    private final SourceRepository sources;
    private final RawDocRepository rawDocs;
    private final EvidenceFactRepository facts;
    private final RoutingRuleRepository routingRules;

    public SeedData(SourceRepository sources, RawDocRepository rawDocs,
                    EvidenceFactRepository facts, RoutingRuleRepository routingRules) {
        this.sources = sources;
        this.rawDocs = rawDocs;
        this.facts = facts;
        this.routingRules = routingRules;
    }

    private Source mofIsa;
    private Source nfra;

    @Override
    public void run(String... args) {
        if (sources.count() > 0) return;
        seedSources();
        seedSampleFacts();
        seedRoutingRules();
        log.info("Seed xong: {} nguồn, {} fact mẫu, {} routing rule",
                sources.count(), facts.count(), routingRules.count());
    }

    private void seedSources() {
        // Fix 2026-07-14 (Hanh: ưu tiên regulator VN): portal MOF là SPA (Vue) — HTML tĩnh chỉ
        // là <div id="app"> rỗng, KHÔNG parse được. JS gọi REST API sạch: danh sách qua
        // POST /api/article/reads (body {"rootCategoryId":<id chuyên mục BH>}), chi tiết qua
        // GET /api/article/getbyslug. fetchUrl = endpoint danh sách; rootCategoryId + endpoint
        // chi tiết nằm trong ingestMofIsa/parseMofList (đặc thù site, như các parser riêng khác).
        mofIsa = sources.save(new Source("MOF_ISA", "Cục Quản lý, giám sát bảo hiểm — Bộ Tài chính",
                "https://www.mof.gov.vn/api/article/reads?offset=0&limit=25", "www.mof.gov.vn",
                Source.SourceType.JSON, 1, "vi"));
        // Collision check 2026-07-05: registry's "root" URL is a meta-refresh redirect TO this exact
        // deep path — current seed is already the real target. No change.
        nfra = sources.save(new Source("NFRA_CN", "国家金融监督管理总局 (NFRA)",
                "https://www.nfra.gov.cn/cn/view/pages/index/index.html", "www.nfra.gov.cn",
                Source.SourceType.HTML, 1, "zh"));
        // Collision check 2026-07-05: registry's URL differs only by trailing slash, both live,
        // identical content. No functional difference — no change.
        sources.save(new Source("IAV_VN", "Hiệp hội Bảo hiểm Việt Nam",
                "https://iav.vn", "iav.vn",
                Source.SourceType.HTML, 2, "vi"));
        // Track 2 recheck 2026-07-11: /bao-hiem/rss VÀ mọi biến thể /rss đều 302 → trang 404;
        // homepage không còn nhắc RSS ở đâu — site đã BỎ RSS. Deactivate (bật lại nếu viết
        // parser HTML cho chuyên mục /bao-hiem/ — Batch 6b).
        Source tnck = new Source("TNCK_VN", "Tin nhanh Chứng khoán — chuyên mục bảo hiểm",
                "https://www.tinnhanhchungkhoan.vn/bao-hiem/rss", "www.tinnhanhchungkhoan.vn",
                Source.SourceType.RSS, 2, "vi");
        tnck.setActive(false);
        sources.save(tnck);
        sources.save(new Source("CBIRC_NEWS", "21世纪经济报道 — 保险频道",
                "https://www.21jingji.com", "www.21jingji.com",
                Source.SourceType.HTML, 3, "zh"));

        seedBatch6aSources();
    }

    /**
     * Batch 6a — nguồn bổ sung từ tmr-source-registry-merged.md (04/07/2026).
     * KHÔNG lặp lại MOF_ISA / IAV_VN / NFRA_CN ở trên — cùng thực thể, đã seed.
     * urlUnverified mặc định true (Source constructor) — Track 2 verify sẽ set false
     * khi xác nhận reachable; PARSE của Group B (HTML) chờ per-site parser Batch 6b.
     */
    private void seedBatch6aSources() {
        // ---- GROUP A: https + RSS ----
        sources.save(new Source("FSA_JP", "Financial Services Agency (Japan)",
                "https://www.fsa.go.jp/fsaEnNewsList_rss2.xml", "www.fsa.go.jp",
                Source.SourceType.RSS, 1, "en"));
        // Track 2 fix 2026-07-11: language=english trả channel RỖNG (0 item), nhưng
        // language=chinese CÙNG serno trả ~800 item (xác nhận live) — đổi sang bản Chinese,
        // language field "zh" (pipeline đã xử lý nguồn tiếng Trung: NFRA_CN, CBIRC_NEWS).
        sources.save(new Source("FSC_TW", "Financial Supervisory Commission (Taiwan)",
                "https://www.fsc.gov.tw/RSS/Messages?serno=201202290001&language=chinese",
                "www.fsc.gov.tw", Source.SourceType.RSS, 1, "zh"));
        sources.save(new Source("HKMA", "Hong Kong Monetary Authority",
                "https://www.hkma.gov.hk/eng/other-information/rss/rss_press-release.xml",
                "www.hkma.gov.hk", Source.SourceType.RSS, 1, "en"));
        // Track 2 recheck 2026-07-11: backend feed CHẾT phía server — /desktopmodules/rssedaily
        // 302 → trang 404 (status=500), /RSS.aspx trả channel rỗng. Mọi biến thể đều hỏng.
        // Deactivate (trang HTML /News vẫn sống — cần parser riêng nếu muốn bật lại).
        Source air = new Source("AIR", "Asia Insurance Review (eDaily)",
                "https://www.asiainsurancereview.com/desktopmodules/rssedaily/",
                "www.asiainsurancereview.com", Source.SourceType.RSS, 2, "en");
        air.setActive(false);
        sources.save(air);
        // Track 2 fix 2026-07-11: /rss là index HTML, nhưng site có feed XML thật theo chuyên mục —
        // /rss/banking-finance xác nhận live 200 text/xml (sát ngành bảo hiểm nhất trong danh sách feed).
        sources.save(new Source("BT_SG", "The Business Times (Singapore) — Banking & Finance",
                "https://www.businesstimes.com.sg/rss/banking-finance", "www.businesstimes.com.sg",
                Source.SourceType.RSS, 2, "en"));

        // ---- GROUP B: https, HTML-only — needs Batch 6b per-site parser ----
        // Vietnam life insurers
        // Track 2 recheck 2026-07-11: 403 với CẢ UA browser lẫn UA MarketRadar — WAF chặn
        // automated fetch. Deactivate (không lách bot-protection; cần kênh khác, vd RSS/API chính thức).
        Source bvnt = new Source("BVNT", "Bảo Việt Nhân thọ",
                "https://www.baovietnhantho.com.vn/", "www.baovietnhantho.com.vn",
                Source.SourceType.HTML, 2, "vi");
        bvnt.setActive(false);
        sources.save(bvnt);
        // Track 2 fix + Batch 6b (2026-07-05): root 301 → /vi.html, then found the real news listing
        // page from that page's own nav (confirmed live, real press-release cards — see parseAia).
        sources.save(new Source("AIA_VN", "AIA Việt Nam",
                "https://www.aia.com.vn/vi/ve-chung-toi/truyen-thong/su-kien-noi-bat.html", "www.aia.com.vn",
                Source.SourceType.HTML, 2, "vi"));
        // Batch 6b (2026-07-05): root was homepage only — found real news listing page from its nav
        // (confirmed live, real press-release teasers — see parseManulife).
        // Track 2 recheck 2026-07-11: giờ trả 403 với CẢ UA browser lẫn UA MarketRadar — site đã bật
        // WAF/bot-protection sau 07-05. Deactivate (parser parseManulife GIỮ NGUYÊN — bật lại nếu hết chặn).
        Source manulife = new Source("MANULIFE_VN", "Manulife Việt Nam",
                "https://www.manulife.com.vn/vi/ve-chung-toi/tin-tuc-va-su-kien/thong-cao-bao-chi.html",
                "www.manulife.com.vn", Source.SourceType.HTML, 2, "vi");
        manulife.setActive(false);
        sources.save(manulife);
        // Track 2 fix + Batch 6b (2026-07-05): root 301 → /vi/, but that's the homepage (blog teasers
        // only, not press releases) — found the real press-release listing page from its nav
        // (confirmed live — see parsePrudential).
        sources.save(new Source("PRUDENTIAL_VN", "Prudential Việt Nam",
                "https://www.prudential.com.vn/vi/ve-chung-toi/thong-cao-bao-chi/", "www.prudential.com.vn",
                Source.SourceType.HTML, 2, "vi"));
        // Fix 2026-07-05: old domain www.mbalife.com.vn was DNS NXDOMAIN — wrong domain entirely.
        // Real domain confirmed by user + live-checked: mblife.vn (200 text/html).
        // Fix 2026-07-14: root "/" homepage has no articles — moved fetchUrl to the actual press
        // page (/goc-bao-chi, "press corner", found via its own nav). Parser added (parseMbAgeasPress):
        // Next.js + Apollo GraphQL, article data already embedded in __NEXT_DATA__ (no separate API
        // call needed — unlike BIDV). Verified live: 20 dated items, current 2026 press releases.
        sources.save(new Source("MB_AGEAS", "MB Ageas Life",
                "https://mblife.vn/goc-bao-chi", "mblife.vn",
                Source.SourceType.HTML, 2, "vi"));
        // Fix 2026-07-05: old domain www.phuhunglife.vn timed out — wrong TLD. Real domain confirmed
        // by user + live-checked: www.phuhunglife.com (200 text/html).
        // Fix 2026-07-14 (Case: find-the-news-URL): homepage has no articles; nav "Tin Tức - Sự
        // Kiện" is a JS dropdown, real category page found via its submenu: /vn/tin-tuc/?...
        // categoryId=2907 ("Thông cáo báo chí"). Page IS server-rendered but data lives in a
        // window.globalData.newsPage.newsList = {...} JS assignment, not normal HTML tags
        // (parsePhuHungLife — balanced-brace JSON extraction). Dates use DOTS "dd.MM.yyyy", unique
        // among all sources. Only 3 items/fetch (site's fixed pageSize, confirmed unchangeable via
        // query param) of 41 total — still real dated content each run.
        sources.save(new Source("PHU_HUNG_LIFE", "Phú Hưng Life",
                "https://www.phuhunglife.com/vn/tin-tuc/?currentPage=1&year=all&categoryId=2907",
                "www.phuhunglife.com", Source.SourceType.HTML, 2, "vi"));
        // Added 2026-07-05, confirmed live (200 text/html) — was missing entirely from registry.
        // Batch 6b note 2026-07-05: news listing (/about-us/news/) ships an EMPTY
        // <div class="row article-list-wrapper"></div> — AJAX-populated (global MetLife platform),
        // same architecture blocker as MOF VN/HK IA. No per-site parser written — root URL kept as
        // generic parseHtml dump for now.
        // Fix 2026-07-14 (feedback Hanh: "mắt thấy ngày mà crawler không thấy"): trang tin
        // /about-us/news/ render bằng JS (AEM) — HTML tĩnh KHÔNG có bài. JS gọi endpoint JSON
        // dưới đây (title + publishedDate "MMM d, yyyy" + path). Fetch thẳng JSON đó (SourceType.JSON,
        // parseBidvMetlife). startArticleNum/endArticleNum = phân trang; lấy 25 bài mới nhất, sort desc.
        sources.save(new Source("BIDV_METLIFE", "BIDV MetLife",
                "https://www.bidvmetlife.com.vn/bin/MLApp/globalMarketingPlatform/fetchArticleColumnGridArticleListing"
                        + "?articleDataConfig=taxonomy&articleDataTaxonomy=/content/metlife/vn/homepage/about-us/news"
                        + "&startArticleNum=0&endArticleNum=25&sortby=date&dateSort=desc",
                "www.bidvmetlife.com.vn", Source.SourceType.JSON, 2, "vi"));
        // Added 2026-07-05, confirmed live then. Track 2 recheck 2026-07-14: now fails TLS
        // handshake — "unable to get local issuer certificate" (their server stopped sending the
        // intermediate cert in the chain). This is a misconfiguration on MAP Life's own server,
        // not our fetchUrl — nothing to fix on this end. Deactivate until they repair their cert.
        Source mapLife = new Source("MAP_LIFE", "Mirae Asset Prévoir (MAP Life)",
                "https://www.map-life.com.vn/news", "www.map-life.com.vn",
                Source.SourceType.HTML, 2, "vi");
        mapLife.setActive(false);
        sources.save(mapLife);
        // Added 2026-07-05, confirmed live (200 text/html) — Vietnam entity, was missing entirely.
        // NOTE: distinct from FUBON_TW below (Taiwan parent, DNS-dead) — different entity/country.
        // Batch 6b (same day): pointed at the real news listing page — see parseFubonVn.
        sources.save(new Source("FUBON_VN", "Fubon Việt Nam",
                "https://www.fubonlife.com.vn/tin-tuc.html?tab=5", "www.fubonlife.com.vn",
                Source.SourceType.HTML, 2, "vi"));
        // Fix 2026-07-14 (Case: find-the-news-URL): root "/" is just a meta-refresh stub
        // (474 bytes) to /cathay/ (Vue SPA, same host — no allowedHost change needed). The
        // TSPD scripts on this site are perf-monitoring, NOT a hard bot-block (confirmed: page
        // loads fine in a real browser with no challenge). News nav -> /cathay/news, itself a
        // Vue SPA route with no static articles; its widget calls a GraphQL API
        // (POST /cathay/api/graphql) whose exact query was captured by patching window.fetch and
        // clicking the real category tab in a browser (not guessed). parseCathayVn: "content"
        // field is a nested JSON string ({"vi_VN":{"title":...}}, like MOF's articleContent),
        // posted_at is clean "yyyy-MM-dd". Detail link = /cathay/news-detail?news_id={id} (Vue
        // Router route name, confirmed live 200). Verified server-side with crawler UA: 15 items.
        sources.save(new Source("CATHAY_VN", "Cathay Life Việt Nam",
                "https://www.cathaylife.com.vn/cathay/api/graphql", "www.cathaylife.com.vn",
                Source.SourceType.JSON, 2, "vi"));
        // Track 2 recheck 2026-07-14: 403 with our UA and with a full browser UA alike — genuine
        // bot-protection WAF. Deactivate (bypassing bot detection is out of scope).
        Source sunlifeVn = new Source("SUNLIFE_VN", "Sun Life Việt Nam",
                "https://www.sunlife.com.vn/", "www.sunlife.com.vn",
                Source.SourceType.HTML, 2, "vi");
        sunlifeVn.setActive(false);
        sources.save(sunlifeVn);
        // Fix 2026-07-14 (Case: find-the-news-URL): homepage has no articles; nav "Tin Tức" ->
        // "Thông cáo báo chí" is JS-driven (href="#") — real page is /press-release. THAT page's
        // static HTML is also empty; its widget calls GET /api/v1/application/getContent/
        // press-release, which returns not a list but ONE post whose contentVn field is a hand-
        // authored HTML blob with each "article" as a .dropshadowboxes-container block
        // (parseShinhanVn — real link is the "Xem thêm" button, NOT the title's own href, which
        // is a stale/duplicated slug per their CMS). Verified live: 67 items, real 2026 dates.
        sources.save(new Source("SHINHAN_VN", "Shinhan Life Việt Nam",
                "https://www.shinhanlifevn.com.vn/api/v1/application/getContent/press-release",
                "www.shinhanlifevn.com.vn", Source.SourceType.JSON, 2, "vi"));
        // Fix 2026-07-14: old path 404 (Track 2 2026-07-05 flagged, not yet fixed then) — real
        // press-release page found live: chubb.com/vn-en/media-centre/press-release.html.
        // Parser added same day (parseChubbVn): server-rendered li.news-list, MM/dd/yyyy dates
        // (US edition). Article hrefs point to chubb.mediaroom.com (outside allowedHost) —
        // ingestListing falls back to title-only, still carries the real publish date.
        sources.save(new Source("CHUBB_VN", "Chubb Life Việt Nam",
                "https://www.chubb.com/vn-en/media-centre/press-release.html", "www.chubb.com",
                Source.SourceType.HTML, 2, "vi"));
        // Fix 2026-07-14 (first pass): old fetchUrl (www, https) 301-redirects to http+non-www
        // (blocked by https-only + host whitelist). Root cause was just the "www." — bare domain
        // serves the same site directly on https, confirmed live 200, no redirect.
        // Fix 2026-07-14 (second pass, Case A): homepage itself has no static articles — news
        // widget loads via POST /api/news/home (empty "{}" body works, confirmed live). Response
        // is a JSON envelope wrapping pre-rendered HTML (parseDaiichiVn). Article links point to
        // a different subdomain (kh.dai-ichi-life.com.vn) — falls back to title+real-date, same
        // pattern as Chubb.
        sources.save(new Source("DAIICHI_VN", "Dai-ichi Life Việt Nam",
                "https://dai-ichi-life.com.vn/api/news/home", "dai-ichi-life.com.vn",
                Source.SourceType.JSON, 2, "vi"));
        // Track 2 fix 2026-07-05: root 301 → /vi/ (same host).
        // Fix 2026-07-14: root/homepage has no articles — site routes ENTIRELY client-side
        // (every path, even nonexistent ones, returns the same HTTP 200 app-shell; confirmed
        // by curl). Real news route found by loading the site in a browser and clicking nav
        // ("Tin tức - Kiến thức" -> /vi/blog/). Parser added (parseFwdVn): Contentstack CMS
        // embeds ~331 articles in __NEXT_DATA__, page is ~7-8MB (needs SafeFetcher's
        // maxBytesOverride — see IngestionJob.FWD_VN_MAX_BYTES). Verified live, real 2026 dates.
        sources.save(new Source("FWD_VN", "FWD Việt Nam",
                "https://www.fwd.com.vn/vi/blog/", "www.fwd.com.vn",
                Source.SourceType.HTML, 2, "vi"));
        // Fix 2026-07-05 (user decision): old www.generali.vn 301'd to this exact URL (host drops
        // www) — switched fetchUrl+allowedHost directly to the redirect target, confirmed live 200.
        // Fix 2026-07-14 (Case: find-the-news-URL): homepage has no articles. Real page
        // /thong-cao-bao-chi is Next.js App Router (RSC streaming, no __NEXT_DATA__) whose article
        // list is ALSO not in the initial HTML — loaded by a client fetch to a Strapi CMS API:
        // GET /api/cms/api/thong-cao-bao-chis?fields[...]&pagination[...]&sort[0]=published_date:desc
        // (parseGeneraliVn). published_date is clean "yyyy-MM-dd". Verified live + server-side
        // with crawler UA: 65 press releases available, real 2026 dates.
        sources.save(new Source("GENERALI_VN", "Generali Việt Nam",
                "https://generali.vn/api/cms/api/thong-cao-bao-chis"
                        + "?fields%5B0%5D=title&fields%5B1%5D=slug&fields%5B2%5D=published_date"
                        + "&pagination%5Bpage%5D=1&pagination%5BpageSize%5D=25"
                        + "&sort%5B0%5D=published_date%3Adesc",
                "generali.vn", Source.SourceType.JSON, 2, "vi"));
        // Track 2 fix 2026-07-05: root 301 → /vi (same host, path only).
        // Fix 2026-07-14 (Case: find-the-news-URL): /vi homepage has no articles — moved fetchUrl
        // to /vi/news, server-rendered (no API needed, unlike Generali/Shinhan). parseHanwhaVn:
        // div.thumb / p.time (dd/MM/yyyy) / h3.title a. Verified live: 6 items, dated July 2026.
        sources.save(new Source("HANWHA_VN", "Hanwha Life Việt Nam",
                "https://hanwhalife.com.vn/vi/news", "hanwhalife.com.vn",
                Source.SourceType.HTML, 2, "vi"));

        // Vietnam finance media (RSS unconfirmed as of registry write — seeded as HTML/Group B;
        // Track 2 may promote to Group A/RSS if a feed is confirmed — that's a separate seed edit)
        sources.save(new Source("VNECONOMY", "VnEconomy",
                "https://vneconomy.vn/", "vneconomy.vn",
                Source.SourceType.HTML, 2, "vi"));
        // Parser added 2026-07-14 (parseTbnh): homepage is server-rendered, div.article[id^=article-]
        // with dd/MM/yyyy dates (49/75 items dated same-page). No insurance-only section confirmed —
        // homepage kept as fetchUrl; Classifier (AI#1) filters relevance downstream as usual.
        sources.save(new Source("TBNH", "Thời báo Ngân hàng",
                "https://thoibaonganhang.vn/", "thoibaonganhang.vn",
                Source.SourceType.HTML, 2, "vi"));
        // Decision 2026-07-05: home.rss has valid RSS body but mislabeled Content-Type (text/html) —
        // SafeFetcher's content-type gate (#5) would reject it. NOT relaxing the gate for one source's
        // server misconfig (would weaken the check for everyone). Staying HTML type on root URL;
        // needs a CafeF-specific listing parser (Batch 6b), not parseRss.
        sources.save(new Source("CAFEF", "CafeF",
                "https://cafef.vn/", "cafef.vn",
                Source.SourceType.HTML, 3, "vi"));

        // China (NFRA already seeded above as NFRA_CN — not repeated)
        sources.save(new Source("PINGAN_MEDIA", "Ping An (media)",
                "https://group.pingan.com/media.html", "group.pingan.com",
                Source.SourceType.HTML, 2, "zh"));
        // Fix 2026-07-14: old path 404 (Track 2 2026-07-05 flagged, not yet fixed then) — real
        // news center found live at /about-us/news-center.
        sources.save(new Source("CHINALIFE_HK", "China Life (HK/overseas)",
                "https://www.chinalife.com.hk/about-us/news-center", "www.chinalife.com.hk",
                Source.SourceType.HTML, 2, "en"));

        // Hong Kong
        // Fix 2026-07-14 (Hanh: mở rộng khu vực — regulator T1): homepage has no articles; found
        // /en/infocenter/press_releases.html via its nav ("Press Releases"). That page is a near-
        // empty jQuery shell (4.4KB) whose content loads via POST /en/infocenter/press_releases.php
        // with an EMPTY body (confirmed live + server-side with crawler UA). Response: 490 releases,
        // newest-first, sorted, confirmed through July 2026. parseHkia().
        sources.save(new Source("HKIA", "Insurance Authority (Hong Kong)",
                "https://www.ia.org.hk/en/infocenter/press_releases.php", "www.ia.org.hk",
                Source.SourceType.JSON, 1, "en"));
        sources.save(new Source("AIA_HK", "AIA Group HK",
                "https://www.aia.com.hk/en/about-aia/about-us/media-centre/press-releases",
                "www.aia.com.hk", Source.SourceType.HTML, 2, "en"));
        // Fix 2026-07-14: old path 404 (site restructured since the 2026-07-05 trailing-slash
        // fix) — real newsroom found live at /en/about-us/newsroom/.
        // Parser added same day (parsePruHk): AEM server-rendered, article.article-card[data-date]
        // (dd-MM-yyyy, reuses PRU_FMT). Page is a full archive (115 items, 2018-2026, unsorted) —
        // freshness window correctly keeps only the ~26 2025-2026 items, by design not a bug.
        sources.save(new Source("PRU_HK", "Prudential HK",
                "https://www.prudential.com.hk/en/about-us/newsroom/", "www.prudential.com.hk",
                Source.SourceType.HTML, 2, "en"));

        // Taiwan
        // Fix 2026-07-14: news.fubon.com never resolved (Track 2 2026-07-05, DNS NXDOMAIN) — that
        // subdomain appears retired. No standalone Fubon Life (TW) domain found; Fubon Financial
        // Holdings runs one shared newsroom for all subsidiaries incl. Fubon Life — using that
        // (confirmed live, real article links). Renamed host + entity name to reflect this.
        sources.save(new Source("FUBON_TW", "Fubon Financial Holdings (incl. Fubon Life)",
                "https://www.fubon.com/Fubon_Portal/financialholdings/en/news/list.jsp", "www.fubon.com",
                Source.SourceType.HTML, 2, "en"));
        // Track 2 recheck 2026-07-14: still behind the same auth-gateway/WAF (BigIP load balancer,
        // response has no Content-Type header at all) — confirmed structural block, not our URL.
        // Deactivate.
        Source cathayTw = new Source("CATHAY_TW", "Cathay Life (Taiwan)",
                "https://www.cathaylife.com.tw/cathaylife_en/news_detail.aspx?type=8",
                "www.cathaylife.com.tw", Source.SourceType.HTML, 2, "en");
        cathayTw.setActive(false);
        sources.save(cathayTw);

        // South Korea
        // Track 2 fix 2026-07-05: 302 → /eng/index (same host).
        sources.save(new Source("FSC_KR", "Financial Services Commission (Korea)",
                "https://www.fsc.go.kr/eng/index", "www.fsc.go.kr",
                Source.SourceType.HTML, 1, "en"));
        // Fix 2026-07-14: old host redirected (english.fss.or.kr → www.fss.or.kr) — Track 2
        // 2026-07-05 flagged the host change but left it unfixed. Switched fetchUrl+allowedHost
        // directly to the redirect target, confirmed live 200 with no further redirect.
        sources.save(new Source("FSS_KR", "Financial Supervisory Service (Korea)",
                "https://www.fss.or.kr/eng/main/main.do?menuNo=400000", "www.fss.or.kr",
                Source.SourceType.HTML, 1, "en"));
        // Track 2 fix 2026-07-05: 302 → /static/CM_CC00001_P10000.html (same host).
        sources.save(new Source("HANWHA_GLOBAL", "Hanwha Life (global)",
                "https://www.hanwhalife.com/static/CM_CC00001_P10000.html", "www.hanwhalife.com",
                Source.SourceType.HTML, 2, "en"));

        // Japan
        // Fix 2026-07-14: old path 404 (Track 2 2026-07-05 flagged, not yet fixed then) — real
        // newsroom found live at /en/newsroom/release/.
        sources.save(new Source("TOKIO_MARINE", "Tokio Marine Holdings",
                "https://www.tokiomarinehd.com/en/newsroom/release/", "www.tokiomarinehd.com",
                Source.SourceType.HTML, 2, "en"));
        // Track 2 recheck 2026-07-14: still 403 with both our UA and a full browser UA — genuine
        // bot-protection WAF, not a UA-string issue. Deactivate (bypassing bot detection is out of
        // scope — see market-radar's own safety policy).
        Source msad = new Source("MSAD", "MS&AD Insurance Group",
                "https://www.ms-ad-hd.com/en/news/", "www.ms-ad-hd.com",
                Source.SourceType.HTML, 2, "en");
        msad.setActive(false);
        sources.save(msad);
        // Fix 2026-07-14: old path 404 (Track 2 2026-07-05 flagged, not yet fixed then) — real
        // newsroom found live at /global/news/.
        sources.save(new Source("NIPPON_LIFE", "Nippon Life",
                "https://www.nissay.co.jp/global/news/", "www.nissay.co.jp",
                Source.SourceType.HTML, 2, "en"));

        // Singapore
        // Track 2 recheck 2026-07-14: /news is a clean, real REST API (GET /api/v1/search, Solr
        // backend) — but the server returns a "Maintenance" HTML page specifically to our honest,
        // self-identifying crawler UA while serving real JSON to a normal browser UA (confirmed by
        // A/B curl with identical request otherwise). This is UA-based bot detection, not a URL or
        // architecture problem. Consistent with this project's standing policy (see MSAD, IC_PH,
        // BNM_MY notes below): NOT spoofing a browser UA to bypass detection. Deactivate.
        Source masSg = new Source("MAS_SG", "Monetary Authority of Singapore",
                "https://www.mas.gov.sg/", "www.mas.gov.sg",
                Source.SourceType.HTML, 1, "en");
        masSg.setActive(false);
        sources.save(masSg);
        // Fix 2026-07-14: old path redirected to the site's own 404 error page (Track 2 2026-07-05
        // flagged, not yet fixed then) — real media releases page found live at
        // /about-us/media-centre/media-releases.html.
        sources.save(new Source("GREAT_EASTERN", "Great Eastern",
                "https://www.greateasternlife.com/sg/en/about-us/media-centre/media-releases.html",
                "www.greateasternlife.com", Source.SourceType.HTML, 2, "en"));
        sources.save(new Source("INCOME_SG", "Income (NTUC)",
                "https://www.income.com.sg/about-us/corporate-information/press-releases",
                "www.income.com.sg", Source.SourceType.HTML, 2, "en"));
        // Track 2 recheck 2026-07-14: connection reset (RST_STREAM) even forcing HTTP/1.1 — looks
        // like network/regional-level blocking, not a URL problem. Deactivate.
        Source aiaSg = new Source("AIA_SG", "AIA Singapore",
                "https://www.aia.com.sg/en/about-aia/press-releases.html", "www.aia.com.sg",
                Source.SourceType.HTML, 2, "en");
        aiaSg.setActive(false);
        sources.save(aiaSg);

        // ASEAN (other)
        // Track 2 recheck 2026-07-14: consistent 20s connect timeout (retried at 20s, still no
        // response) — not a URL issue, looks like network/regional reachability. Deactivate.
        Source ojkId = new Source("OJK_ID", "OJK (Indonesia)",
                "https://www.ojk.go.id/", "www.ojk.go.id",
                Source.SourceType.HTML, 1, "id");
        ojkId.setActive(false);
        sources.save(ojkId);
        // Track 2 recheck 2026-07-14: 403 with our UA, 202 (challenge page) with a full browser
        // UA — genuine bot-protection WAF; even the "success" case wouldn't be real content
        // without executing JS. Deactivate (bypassing bot detection is out of scope).
        Source bnmMy = new Source("BNM_MY", "Bank Negara Malaysia",
                "https://www.bnm.gov.my/", "www.bnm.gov.my",
                Source.SourceType.HTML, 1, "en");
        bnmMy.setActive(false);
        sources.save(bnmMy);
        // Track 2 recheck 2026-07-14: still 403 regardless of UA — genuine bot-protection WAF.
        // Deactivate.
        Source icPh = new Source("IC_PH", "Insurance Commission (Philippines)",
                "https://www.insurance.gov.ph/", "www.insurance.gov.ph",
                Source.SourceType.HTML, 1, "en");
        icPh.setActive(false);
        sources.save(icPh);
        sources.save(new Source("THAILIFE_TH", "Thai Life",
                "https://www.thailife.com/en/media-centre/news/", "www.thailife.com",
                Source.SourceType.HTML, 2, "en"));
        // Fix 2026-07-14: old path 404, redirects to /en/about-us/newsroom/ (Track 2 2026-07-05
        // flagged, not yet fixed then) — used the exact redirect target directly, confirmed live 200.
        sources.save(new Source("PRULIFE_PH", "Pru Life UK (Philippines)",
                "https://www.prulifeuk.com.ph/en/about-us/newsroom/", "www.prulifeuk.com.ph",
                Source.SourceType.HTML, 2, "en"));
        // Track 2 recheck 2026-07-14: consistent 20s connect timeout — not a URL issue, looks like
        // network/regional reachability. Deactivate.
        Source philamPh = new Source("PHILAM_PH", "AIA Philippines (Philam)",
                "https://www.philamlife.com/our-company/press-center", "www.philamlife.com",
                Source.SourceType.HTML, 2, "en");
        philamPh.setActive(false);
        sources.save(philamPh);

        // US & Global
        sources.save(new Source("NAIC", "NAIC",
                "https://content.naic.org/newsroom", "content.naic.org",
                Source.SourceType.HTML, 1, "en"));
        // Fix 2026-07-14: old path 404 (Track 2 2026-07-05 flagged, not yet fixed then) — real
        // sigma research hub found live at /institute/research/sigma-research.html.
        sources.save(new Source("SWISSRE_INST", "Swiss Re Institute (Sigma)",
                "https://www.swissre.com/institute/research/sigma-research.html", "www.swissre.com",
                Source.SourceType.HTML, 2, "en"));
        sources.save(new Source("MUNICHRE", "Munich Re",
                "https://www.munichre.com/en.html", "www.munichre.com",
                Source.SourceType.HTML, 2, "en"));
        sources.save(new Source("LIMRA", "LIMRA",
                "https://www.limra.com/", "www.limra.com",
                Source.SourceType.HTML, 2, "en"));
        // Track 2 recheck 2026-07-14: connection reset (RST_STREAM) even forcing HTTP/1.1 —
        // consistent with edge/CDN-level bot mitigation, not a URL problem. Deactivate.
        Source mckinsey = new Source("MCKINSEY_INS", "McKinsey (Insurance)",
                "https://www.mckinsey.com/industries/financial-services/our-insights",
                "www.mckinsey.com", Source.SourceType.HTML, 2, "en");
        mckinsey.setActive(false);
        sources.save(mckinsey);

        // Asia trade media
        // Note 2026-07-14: intermittent — worked in an earlier run this session, timed out/hung on
        // TLS handshake in a later one (Cloudflare-fronted, IPs 172.67.x/104.21.x). Left ACTIVE:
        // this looks like transient rate-limiting/challenge behavior, not a dead or wrong URL.
        sources.save(new Source("INS_ASIA_NEWS", "Insurance Asia News",
                "https://insuranceasianews.com/", "insuranceasianews.com",
                Source.SourceType.HTML, 3, "en"));
        // Track 2 recheck 2026-07-14: still 403 regardless of UA — genuine bot-protection WAF.
        // Deactivate.
        Source insBizAsia = new Source("INS_BIZ_ASIA", "Insurance Business Asia",
                "https://www.insurancebusinessmag.com/asia/", "www.insurancebusinessmag.com",
                Source.SourceType.HTML, 3, "en");
        insBizAsia.setActive(false);
        sources.save(insBizAsia);
    }

    private void seedSampleFacts() {
        // ---- RawDoc mẫu (sampleData=true) — công ty/sản phẩm HƯ CẤU ----
        RawDoc docZh = new RawDoc(nfra,
                "https://www.nfra.gov.cn/SAMPLE/demo-doc-1",
                "[MẪU] 某人寿保险公司获批新型分红型终身寿险产品",
                Instant.parse("2026-06-29T02:00:00Z"), Instant.now(),
                "sample-hash-zh-001",
                "[DỮ LIỆU MẪU] 华晟人寿保险股份有限公司（示例）获批推出\"金福长盈\"分红型终身寿险，"
                + "保证利率为2.0%，首年最低保费人民币10,000元。该产品自2026年7月1日起在全国范围销售。",
                "zh", RawDoc.ParseStatus.OK, "Dữ liệu mẫu đặt tay cho demo template");
        docZh.setSampleData(true);
        rawDocs.save(docZh);

        RawDoc docVi = new RawDoc(mofIsa,
                "https://mof.gov.vn/SAMPLE/demo-doc-2",
                "[MẪU] Doanh nghiệp bảo hiểm điều chỉnh biểu phí sản phẩm liên kết đơn vị",
                Instant.parse("2026-06-30T07:00:00Z"), Instant.now(),
                "sample-hash-vi-002",
                "[DỮ LIỆU MẪU] Công ty TNHH Bảo hiểm Nhân thọ Hoa Sen (hư cấu) công bố điều chỉnh "
                + "phí quản lý quỹ của sản phẩm liên kết đơn vị 'An Phát Đầu Tư' từ 2,0%/năm xuống 1,75%/năm, "
                + "áp dụng từ ngày 01/08/2026 cho cả hợp đồng mới và hợp đồng hiện hữu.",
                "vi", RawDoc.ParseStatus.OK, "Dữ liệu mẫu đặt tay cho demo template");
        docVi.setSampleData(true);
        rawDocs.save(docVi);

        // ---- EvidenceFact mẫu — span NGUYÊN VĂN theo ngôn ngữ gốc ----
        facts.save(new EvidenceFact("F-001", docZh, FactType.PRODUCT_LAUNCH,
                "华晟人寿保险股份有限公司（示例）获批推出\"金福长盈\"分红型终身寿险，保证利率为2.0%，首年最低保费人民币10,000元。",
                "zh")
                .eventDate(LocalDate.of(2026, 6, 29))
                .company("Huasheng Life (mẫu — hư cấu)")
                .productName("金福长盈 — bảo hiểm trọn đời có chia lãi")
                .category("Ra mắt sản phẩm")
                .categoryEn("Product Launch")
                .summaryVi("[Bản dịch/tóm tắt] Được phê duyệt sản phẩm trọn đời chia lãi mới, lãi suất đảm bảo 2,0%, phí tối thiểu năm đầu 10.000 NDT, bán toàn quốc từ 01/07/2026.")
                .summaryEn("[Translation/summary] Approved a new participating whole-life product, guaranteed rate 2.0%, minimum first-year premium RMB 10,000, sold nationwide from 07/01/2026."));

        facts.save(new EvidenceFact("F-002", docVi, FactType.FEE_CHANGE,
                "điều chỉnh phí quản lý quỹ của sản phẩm liên kết đơn vị 'An Phát Đầu Tư' từ 2,0%/năm xuống 1,75%/năm, áp dụng từ ngày 01/08/2026 cho cả hợp đồng mới và hợp đồng hiện hữu.",
                "vi")
                .eventDate(LocalDate.of(2026, 6, 30))
                .company("BHNT Hoa Sen (mẫu — hư cấu)")
                .productName("An Phát Đầu Tư (unit-linked)")
                .category("Thay đổi phí")
                .categoryEn("Fee Change")
                .summaryVi("Giảm phí quản lý quỹ ULP từ 2,0% xuống 1,75%/năm, hiệu lực 01/08/2026, áp dụng cả hợp đồng hiện hữu.")
                .summaryEn("Cut the unit-linked fund management fee from 2.0% to 1.75%/year, effective 08/01/2026, applying to existing contracts too."));
    }

    /**
     * Bảng tra routing PLACEHOLDER (5 category x 2 dept, many-to-many).
     * Ontology thật (department -> information needs) là deliverable riêng —
     * bảng này chỉ để demo cơ chế routing hoạt động.
     */
    private void seedRoutingRules() {
        routingRules.save(new RoutingRule(Category.PRODUCT_LAUNCH, Department.PRODUCT));
        routingRules.save(new RoutingRule(Category.PRODUCT_LAUNCH, Department.SALES));
        routingRules.save(new RoutingRule(Category.FEE_BENEFIT_COMMISSION_CHANGE, Department.PRODUCT));
        routingRules.save(new RoutingRule(Category.FEE_BENEFIT_COMMISSION_CHANGE, Department.SALES));
        routingRules.save(new RoutingRule(Category.PRODUCT_REGULATION, Department.PRODUCT));
        routingRules.save(new RoutingRule(Category.SALES_DATA, Department.SALES));
        routingRules.save(new RoutingRule(Category.DISTRIBUTION_CHANNEL, Department.SALES));
    }
}
