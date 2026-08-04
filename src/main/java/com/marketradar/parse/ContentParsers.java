package com.marketradar.parse;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bộ parser chuẩn hoá về text. Nguyên tắc an toàn:
 *  - Nội dung fetch về chỉ là DỮ LIỆU — trích text, không thực thi, không render thô.
 *  - Jsoup: chỉ lấy Document.text() (đã strip toàn bộ script/style/markup).
 *  - PDFBox: chỉ trích text, không load resource ngoài; giới hạn số trang.
 *  - Parse lỗi → ném ParseFailedException, tầng trên ghi record lỗi (fail loud), không đoán.
 */
@Component
public class ContentParsers {

    private static final Logger log = LoggerFactory.getLogger(ContentParsers.class);
    private static final int PDF_MAX_PAGES = 100;
    private static final int PDF_OCR_MAX_PAGES = 30;
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter IAV_FMT_EN = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter IAV_FMT_VI = DateTimeFormatter.ofPattern("dd/MM/yyyy h:mm:ss a", Locale.ENGLISH);
    // AIA emits both zero-padded and non-padded dates (for example 07/04/2026 and
    // 7/4/2026).  Use variable-width day/month so genuine current articles do not
    // fall back to a year-only timestamp.
    private static final DateTimeFormatter AIA_FMT = DateTimeFormatter.ofPattern("d/M/uuuu", Locale.ENGLISH);
    private static final DateTimeFormatter PRU_FMT = DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ENGLISH);
    // Chubb "vn-en" press page is the ENGLISH/US edition — dates confirmed MM/dd/yyyy
    // (xác nhận qua item "09/22/2023": ngày 22 không thể là tháng → thứ tự phải là MM/dd).
    private static final DateTimeFormatter CHUBB_FMT = DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.ENGLISH);
    // Phú Hưng Life dùng dấu CHẤM chứ không phải gạch chéo: "16.06.2026" — khác mọi nguồn khác.
    private static final DateTimeFormatter PHU_HUNG_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.ENGLISH);
    // HKIA (Hong Kong): "8/7/2026" — d/M/yyyy KHÔNG số 0 đệm đầu (khác AIA_FMT dd/MM/yyyy).
    private static final DateTimeFormatter HKIA_FMT = DateTimeFormatter.ofPattern("d/M/yyyy", Locale.ENGLISH);
    // AIA Hong Kong: "9 July 2026" — d MMMM yyyy (tên tháng đầy đủ tiếng Anh).
    private static final DateTimeFormatter AIA_HK_FMT = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);
    // NFRA (Trung Quốc): "2026-07-10 17:55:33" — CÁCH nhau bằng dấu cách, không phải "T" nên
    // LocalDateTime.parse() mặc định (ISO_LOCAL_DATE_TIME) KHÔNG đọc được, cần formatter riêng.
    private static final DateTimeFormatter NFRA_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH);
    // Fubon Financial Holdings (Taiwan): "2026.06.26" — dấu chấm.
    private static final DateTimeFormatter FUBON_TW_FMT = DateTimeFormatter.ofPattern("yyyy.MM.dd", Locale.ENGLISH);
    // NAIC: "Jun. 16, 2026" — viết tắt tháng CÓ dấu chấm.
    private static final DateTimeFormatter NAIC_FMT = DateTimeFormatter.ofPattern("MMM'.' d, yyyy", Locale.ENGLISH);
    private static final java.util.regex.Pattern NAIC_DATE_PATTERN =
            java.util.regex.Pattern.compile("(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\. \\d{1,2}, \\d{4}");
    // Munich Re: "May 12, 2026" / "September 07, 2025" (ngày có/không số 0 đệm đều gặp).
    private static final DateTimeFormatter MUNICHRE_FMT = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH);
    private static final java.util.regex.Pattern MANULIFE_YEAR = java.util.regex.Pattern.compile("(\\d{4})");
    private static final java.util.regex.Pattern AIA_MONTH_ARCHIVE_LINK = java.util.regex.Pattern.compile("/\\d{4}/\\d{2}\\.html$");
    // Fix 2026-07-14: URL bài AIA có năm ngay trong path (.../su-kien-noi-bat/2024/...) —
    // dùng làm fallback khi card KHÔNG có div ngày, để tin cũ vẫn có publishedAt (năm-01-01)
    // và bị bộ lọc "7 ngày" loại đúng, thay vì rơi về fetchedAt=hôm nay rồi hiện như tin mới.
    private static final java.util.regex.Pattern AIA_URL_YEAR = java.util.regex.Pattern.compile("/(20\\d{2})/");
    private static final ObjectMapper JSON = new ObjectMapper();
    // BIDV MetLife JSON trả ngày dạng "MAY 12, 2026" (tháng VIẾT HOA) — parse không phân biệt hoa/thường.
    private static final DateTimeFormatter BIDV_FMT = new DateTimeFormatterBuilder()
            .parseCaseInsensitive().appendPattern("MMM d, yyyy").toFormatter(Locale.ENGLISH);
    private static final java.util.regex.Pattern DDMMYYYY = java.util.regex.Pattern.compile("(\\d{2}/\\d{2}/\\d{4})");
    private static final java.util.regex.Pattern ISO_YMD = java.util.regex.Pattern.compile("(20\\d{2}-\\d{2}-\\d{2})");

    /** HTML → text thuần + title. */
    public ParsedText parseHtml(byte[] body) throws ParseFailedException {
        try {
            Document doc = Jsoup.parse(new String(body, StandardCharsets.UTF_8));
            String title = doc.title();
            String text = doc.text(); // text-only: script/style/attr đều bị loại
            if (text == null || text.isBlank()) {
                throw new ParseFailedException("HTML parse ra text rỗng");
            }
            return new ParsedText(title, text, null);
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("Jsoup lỗi: " + e.getMessage());
        }
    }

    /**
     * Selector các khối "chính" thường gặp — thứ tự KHÔNG quan trọng (chọn theo độ dài text),
     * gộp cả pattern AEM (AIA/Manulife/BIDV), WordPress, và các class phổ biến VN news CMS.
     */
    private static final String MAIN_CONTENT_SELECTOR =
            "article, main, [role=main], .article-content, .article-body, .article-detail, "
            + ".post-content, .entry-content, .news-detail, .news-content, .detail-content, "
            + "#main-content, .cmp-text, .content-detail, .contentdetail";

    /** Boilerplate cần loại TRƯỚC khi chọn khối chính — nav/menu/footer/form chiếm hàng nghìn
     *  ký tự đầu trang (đo thật 2026-07-15: doc AIA_VN mở đầu bằng ~2.500 ký tự menu). */
    private static final String BOILERPLATE_SELECTOR =
            "nav, header, footer, aside, form, script, style, noscript, iframe, svg, button, "
            + "[role=navigation], [role=banner], [role=contentinfo], [role=search], [aria-hidden=true], "
            + ".breadcrumb, .breadcrumbs, .menu, .nav, .navbar, .sidebar, .cookie, .cookie-banner, "
            + ".share, .social, .related, .related-news, .comment, .comments, .subscribe, .newsletter";

    /**
     * Fix 2026-07-15 (audit chất lượng nội dung): HTML TRANG BÀI VIẾT → text phần NỘI DUNG CHÍNH,
     * thay cho parseHtml (dump Document.text() = cả menu/footer). Nguyên nhân trực tiếp làm fact
     * mỏng: extractor chỉ đọc N ký tự đầu — mà N ký tự đầu là boilerplate điều hướng, không phải bài.
     *
     * Cách chọn (deterministic, không LLM):
     *  1. Xoá các phần tử boilerplate rõ nghĩa (nav/header/footer/aside/form + role/class phổ biến).
     *  2. Trong các ứng viên khối-nội-dung (article/main/role=main + class content phổ biến),
     *     lấy khối có text DÀI NHẤT — nếu đủ dài (>= MIN_MAIN_CHARS) thì đó là bài viết.
     *  3. Không ứng viên nào đủ dài → fallback body đã strip boilerplate (vẫn sạch hơn hẳn cũ).
     * Vẫn fail loud khi text rỗng — cùng policy parseHtml.
     */
    public ParsedText parseArticleHtml(byte[] body) throws ParseFailedException {
        try {
            Document doc = Jsoup.parse(new String(body, StandardCharsets.UTF_8));
            String title = doc.title();
            doc.select(BOILERPLATE_SELECTOR).remove();

            Element best = null;
            int bestLen = 0;
            for (Element cand : doc.select(MAIN_CONTENT_SELECTOR)) {
                int len = cand.text().length();
                if (len > bestLen) { best = cand; bestLen = len; }
            }
            String fallback = doc.body() != null ? doc.body().text() : doc.text();
            String text;
            String note;
            if (best != null && bestLen >= MIN_MAIN_CHARS) {
                text = best.text();
                note = null;
            } else {
                text = fallback;
                note = "main-content selector không khớp — dùng body đã strip boilerplate";
            }
            if (text == null || text.isBlank()) {
                throw new ParseFailedException("Article HTML parse ra text rỗng");
            }
            return new ParsedText(title, text, note);
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("Jsoup lỗi (article): " + e.getMessage());
        }
    }

    /** Khối chính phải >= ngưỡng này mới được tin là bài viết (tránh vớ nhầm div content rỗng). */
    private static final int MIN_MAIN_CHARS = 400;

    /** RSS/Atom → danh sách entry (title, link, mô tả text-hoá, publishedAt). */
    public List<RssItem> parseRss(byte[] body) throws ParseFailedException {
        try {
            SyndFeed feed = new SyndFeedInput().build(new XmlReader(new ByteArrayInputStream(body)));
            List<RssItem> items = new ArrayList<>();
            for (SyndEntry entry : feed.getEntries()) {
                String descHtml = entry.getDescription() != null ? entry.getDescription().getValue() : "";
                // Mô tả trong RSS có thể chứa HTML → text-hoá qua Jsoup luôn
                String descText = descHtml.isBlank() ? "" : Jsoup.parse(descHtml).text();
                Instant published = entry.getPublishedDate() != null
                        ? entry.getPublishedDate().toInstant() : null;
                items.add(new RssItem(
                        entry.getTitle() == null ? "(không tiêu đề)" : entry.getTitle(),
                        entry.getLink(),
                        descText,
                        descHtml,
                        published));
            }
            if (items.isEmpty()) throw new ParseFailedException("Feed không có entry nào");
            return items;
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("Rome lỗi: " + e.getMessage());
        }
    }

    /** XML sitemap -> article discovery index. lastmod is retained as the best
     * deterministic date available; article metadata may replace/fill it later. */
    public List<ListingItem> parseSitemap(byte[] body, String requiredPathFragment)
            throws ParseFailedException {
        try {
            Document doc = Jsoup.parse(new String(body, StandardCharsets.UTF_8), "", Parser.xmlParser());
            Map<String, ListingItem> unique = new LinkedHashMap<>();
            for (Element url : doc.select("url")) {
                Element locEl = url.selectFirst("loc");
                if (locEl == null) continue;
                String link = locEl.text().strip().replaceFirst("^http://", "https://");
                if (link.isBlank() || (requiredPathFragment != null
                        && !link.contains(requiredPathFragment))) continue;
                Instant modified = null;
                Element lastmod = url.selectFirst("lastmod");
                if (lastmod != null) modified = parseSitemapInstant(lastmod.text());
                unique.putIfAbsent(link, new ListingItem(titleFromUrl(link), link, modified));
            }
            if (unique.isEmpty()) {
                throw new ParseFailedException("Sitemap không có URL bài viết phù hợp");
            }
            return List.copyOf(unique.values());
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("Sitemap XML lỗi: " + e.getMessage());
        }
    }

    private static Instant parseSitemapInstant(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return Instant.parse(raw.strip()); } catch (Exception ignored) {}
        try { return OffsetDateTime.parse(raw.strip()).toInstant(); } catch (Exception ignored) {}
        try { return LocalDate.parse(raw.strip()).atStartOfDay(VN_ZONE).toInstant(); }
        catch (Exception ignored) { return null; }
    }

    private static String titleFromUrl(String link) {
        try {
            String path = URI.create(link).getPath();
            String slug = path == null ? link : path.replaceFirst("/$", "")
                    .substring(path.replaceFirst("/$", "").lastIndexOf('/') + 1);
            slug = slug.replaceFirst("-\\d+$", "").replace('-', ' ').replace('_', ' ').strip();
            return slug.isBlank() ? link : Character.toUpperCase(slug.charAt(0)) + slug.substring(1);
        } catch (Exception ignored) {
            return link;
        }
    }

    /** MVI Life's AEM news cards. Dates are completed from article metadata during ingest. */
    public List<ListingItem> parseMviLife(byte[] body, String baseUrl) throws ParseFailedException {
        return parseCardLinks(body, baseUrl, "a.cmp-content-teaser__link", null, "MVI_LIFE");
    }

    /** AIA's notices are accordion rows, not the promotion cards used on its press page. */
    public List<ListingItem> parseAiaNotices(byte[] body, String baseUrl) throws ParseFailedException {
        try {
            Document doc = Jsoup.parse(new String(body, StandardCharsets.UTF_8), baseUrl);
            Map<String, ListingItem> unique = new LinkedHashMap<>();
            for (Element row : doc.select(".cmp-accordion__item")) {
                Element titleEl = row.selectFirst(".cmp-accordion__header span");
                Element linkEl = row.selectFirst(".cmp-accordion__body a[href]");
                if (titleEl == null || linkEl == null) continue;
                String title = titleEl.text().strip();
                String link = linkEl.absUrl("href");
                if (!title.isBlank() && !link.isBlank()) {
                    unique.putIfAbsent(link, new ListingItem(title, link, dateFromText(title + " " + link)));
                }
            }
            if (unique.isEmpty()) throw new ParseFailedException("AIA_NOTICES: không tìm thấy notice accordion");
            return List.copyOf(unique.values());
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("AIA_NOTICES: lỗi parse HTML: " + e.getMessage());
        }
    }

    /** Vietnam Investment Review's dedicated insurance vertical. */
    public List<ListingItem> parseVirInsurance(byte[] body, String baseUrl) throws ParseFailedException {
        try {
            Document doc = Jsoup.parse(new String(body, StandardCharsets.UTF_8), baseUrl);
            Map<String, ListingItem> unique = new LinkedHashMap<>();
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MMMM d, yyyy | HH:mm", Locale.ENGLISH);
            for (Element row : doc.select("div.article")) {
                Element a = row.selectFirst("h2.article-title a.article-link, h3.article-title a.article-link");
                if (a == null) continue;
                String title = nonBlank(a.attr("title"), a.text());
                if (!isVietnamLifeInsuranceRelevant(title)) continue;
                String link = a.absUrl("href");
                Instant date = null;
                Element dateEl = row.selectFirst("span.article-date");
                if (dateEl != null) {
                    try { date = LocalDateTime.parse(dateEl.text().strip(), fmt).atZone(VN_ZONE).toInstant(); }
                    catch (Exception ignored) {}
                }
                if (!title.isBlank() && !link.isBlank()) unique.putIfAbsent(link, new ListingItem(title, link, date));
            }
            if (unique.isEmpty()) throw new ParseFailedException("VIR_INSURANCE: không tìm thấy article card");
            return List.copyOf(unique.values());
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("VIR_INSURANCE: lỗi parse HTML: " + e.getMessage());
        }
    }

    /** VietnamNet life-insurance tag. Article detail metadata supplies publish dates. */
    public List<ListingItem> parseVietnamNetLife(byte[] body, String baseUrl) throws ParseFailedException {
        return parseCardLinks(body, baseUrl, ".horizontalPost__main-title a[href]", null, "VIETNAMNET_LIFE");
    }

    /** Tin nhanh Chứng khoán dedicated insurance category. */
    public List<ListingItem> parseTnckInsurance(byte[] body, String baseUrl) throws ParseFailedException {
        try {
            Document doc = Jsoup.parse(new String(body, StandardCharsets.UTF_8), baseUrl);
            Map<String, ListingItem> unique = new LinkedHashMap<>();
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxx");
            for (Element row : doc.select("article.story")) {
                Element a = row.selectFirst(".story__heading a[href]");
                if (a == null) continue;
                String title = nonBlank(a.attr("title"), a.text());
                // The category template appends site-wide event/market cards using the
                // same article.story markup.  Do not mistake those recommendations for
                // insurance-category evidence.
                if (!isVietnamLifeInsuranceRelevant(title)) continue;
                String link = a.absUrl("href");
                Instant date = null;
                Element time = row.selectFirst("time[datetime]");
                if (time != null) {
                    try { date = OffsetDateTime.parse(time.attr("datetime"), fmt).toInstant(); }
                    catch (Exception ignored) {}
                }
                if (!title.isBlank() && !link.isBlank()) unique.putIfAbsent(link, new ListingItem(title, link, date));
            }
            if (unique.isEmpty()) throw new ParseFailedException("TNCK_VN: không tìm thấy article.story");
            return List.copyOf(unique.values());
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("TNCK_VN: lỗi parse HTML: " + e.getMessage());
        }
    }

    /**
     * VietnamPlus' insurance tag. Keep this separate from the TNCK parser even though
     * both use article.story cards: VietnamPlus emits ISO-8601 offsets with a colon
     * (for example +07:00), while TNCK currently emits +0700.
     */
    public List<ListingItem> parseVietnamPlusInsurance(byte[] body, String baseUrl) throws ParseFailedException {
        try {
            Document doc = Jsoup.parse(new String(body, StandardCharsets.UTF_8), baseUrl);
            Map<String, ListingItem> unique = new LinkedHashMap<>();
            for (Element row : doc.select("article.story")) {
                Element a = row.selectFirst(".story__heading a[href], h2 a[href], h3 a[href]");
                if (a == null) continue;
                String title = nonBlank(a.attr("title"), a.text());
                if (!isVietnamLifeInsuranceRelevant(title)) continue;
                String link = a.absUrl("href");
                Instant date = null;
                Element time = row.selectFirst("time[datetime]");
                if (time != null) {
                    try { date = OffsetDateTime.parse(time.attr("datetime")).toInstant(); }
                    catch (Exception ignored) {}
                }
                if (!title.isBlank() && !link.isBlank()) {
                    unique.putIfAbsent(link, new ListingItem(title, link, date));
                }
            }
            if (unique.isEmpty()) {
                throw new ParseFailedException("VIETNAMPLUS_INSURANCE: khong tim thay article.story");
            }
            return List.copyOf(unique.values());
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("VIETNAMPLUS_INSURANCE: loi parse HTML: " + e.getMessage());
        }
    }

    /** Vietnam News/BizHub's dedicated Vietnam insurance vertical (English). */
    public List<ListingItem> parseBizhubInsurance(byte[] body, String baseUrl) throws ParseFailedException {
        try {
            Document doc = Jsoup.parse(new String(body, StandardCharsets.UTF_8), baseUrl);
            Map<String, ListingItem> unique = new LinkedHashMap<>();
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("EEEE, MMM d, uuuu", Locale.ENGLISH);
            for (Element row : doc.select(".vnn-small-news, .meta-data")) {
                Element a = row.selectFirst("h3.meta-data-tit a[href]");
                if (a == null) continue;
                String title = nonBlank(a.attr("title"), a.text());
                if (!isBizhubLifeOrIndustryTechnology(title)) continue;
                String link = a.absUrl("href");
                Instant date = null;
                Element time = row.selectFirst("time.meta-data-source");
                if (time != null) {
                    try {
                        date = LocalDate.parse(time.text().strip(), fmt).atStartOfDay(VN_ZONE).toInstant();
                    } catch (DateTimeParseException ignored) {
                        log.warn("BIZHUB_INSURANCE: không parse được ngày '{}'", time.text());
                    }
                }
                if (!title.isBlank() && !link.isBlank()) {
                    unique.putIfAbsent(link, new ListingItem(title, link, date));
                }
            }
            if (unique.isEmpty()) throw new ParseFailedException("BIZHUB_INSURANCE: không tìm thấy bài");
            return List.copyOf(unique.values());
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("BIZHUB_INSURANCE: lỗi parse HTML: " + e.getMessage());
        }
    }

    /**
     * Reader fallback for BizHub's dedicated insurance vertical. The origin has shown
     * intermittent DNS failures from Java, but Reader exposes the same public archive.
     * Only article-shaped post URLs are accepted; navigation and unrelated finance
     * links cannot become corpus documents.
     */
    public List<ListingItem> parseBizhubReaderListing(byte[] body) throws ParseFailedException {
        try {
            String markdown = new String(body, StandardCharsets.UTF_8).replace("\r\n", "\n");
            var headings = java.util.regex.Pattern.compile(
                    "(?m)^### \\[([^]\\r\\n]+)]\\((https://bizhub\\.vietnamnews\\.vn/"
                            + "[^)\\s]+-post\\d+\\.html)\\)\\s*$")
                    .matcher(markdown);
            Map<String, ListingItem> unique = new LinkedHashMap<>();
            while (headings.find()) {
                int next = markdown.indexOf("\n### [", headings.end());
                if (next < 0) next = markdown.length();
                String block = markdown.substring(headings.start(), next);
                String title = headings.group(1).strip();
                // Dedicated insurance page still mixes non-life stories. Preserve explicit
                // life/bancassurance evidence plus cross-sector technology shifts; claims
                // from typhoons and P&C company milestones do not serve a life-product brief.
                if (!isBizhubLifeOrIndustryTechnology(title)) continue;
                Instant date = dateFromEnglishNewsText(block);
                unique.putIfAbsent(headings.group(2).strip(),
                        new ListingItem(title, headings.group(2).strip(), date));
            }
            if (unique.isEmpty()) {
                throw new ParseFailedException("BIZHUB_INSURANCE reader returned no qualified post links");
            }
            return List.copyOf(unique.values());
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("BIZHUB_INSURANCE reader parse failed: " + e.getMessage());
        }
    }

    private static boolean isBizhubLifeOrIndustryTechnology(String title) {
        if (title == null || title.isBlank()) return false;
        String folded = title.toLowerCase(Locale.ROOT);
        // BizHub's /insurance vertical covers both life and non-life. PVI/Bảo Minh
        // milestones and property/casualty loss stories are not competitor evidence
        // for a life-insurance Product brief, even when the headline says
        // "Vietnamese insurer(s)" and would otherwise pass the broad VN predicate.
        boolean explicitNonLife = folded.contains("pvi insurance")
                || folded.contains("bao minh") || folded.contains("bảo minh")
                || folded.contains("non-life") || folded.contains("nonlife")
                || folded.contains("property and casualty") || folded.contains("p&c")
                || folded.contains("motor insurance") || folded.contains("hurricane")
                || folded.contains("typhoon");
        if (explicitNonLife) return false;
        boolean industryTechnology = folded.contains("insurance industry")
                && (folded.contains("data") || folded.matches(".*\\bai\\b.*")
                || folded.contains("digital") || folded.contains("technology"));
        return isVietnamLifeInsuranceRelevant(title) || industryTechnology;
    }

    private static Instant dateFromEnglishNewsText(String text) {
        if (text == null) return null;
        var matcher = java.util.regex.Pattern.compile(
                "(?m)(?:Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday),\\s+"
                        + "([A-Z][a-z]{2}\\s+\\d{1,2},\\s+20\\d{2})")
                .matcher(text);
        if (!matcher.find()) return null;
        try {
            return LocalDate.parse(matcher.group(1),
                    DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.ENGLISH))
                    .atStartOfDay(VN_ZONE).toInstant();
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Báo Chính phủ's official insurance tag: legislation, decrees and policy implementation. */
    public List<ListingItem> parseBaoChinhPhuInsurance(byte[] body, String baseUrl) throws ParseFailedException {
        try {
            Document doc = Jsoup.parse(new String(body, StandardCharsets.UTF_8), baseUrl);
            Map<String, ListingItem> unique = new LinkedHashMap<>();
            for (Element row : doc.select(".box-stream-item")) {
                Element a = row.selectFirst("a.box-stream-link-title[href]");
                if (a == null) continue;
                String title = nonBlank(a.attr("title"), a.text());
                String foldedTitle = foldVietnamese(title);
                // This archive mixes life, non-life and social-insurance policy. A
                // broad "doanh nghiệp bảo hiểm" headline is not enough: it previously
                // admitted a vessel-casualty story. Keep only explicit life markers or
                // cross-industry policy subjects Strategy genuinely needs.
                boolean lifePolicy = hasExplicitVietnamLifeMarker(foldedTitle)
                        || foldedTitle.contains("huu tri bo sung")
                        || foldedTitle.contains("luat kinh doanh bao hiem")
                        || (foldedTitle.contains("dai ly bao hiem")
                        && !foldedTitle.contains("bao hiem xa hoi"));
                if (!lifePolicy) continue;
                String link = a.absUrl("href");
                Element time = row.selectFirst(".box-stream-time");
                Instant date = time == null ? null : dateFromText(time.text());
                if (!title.isBlank() && !link.isBlank()) {
                    unique.putIfAbsent(link, new ListingItem(title, link, date));
                }
            }
            if (unique.isEmpty()) throw new ParseFailedException("BAOCHINHPHU_INSURANCE: không tìm thấy bài");
            return List.copyOf(unique.values());
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("BAOCHINHPHU_INSURANCE: lỗi parse HTML: " + e.getMessage());
        }
    }

    /** VnExpress' dedicated business/insurance archive. Detail metadata supplies exact dates. */
    public List<ListingItem> parseVnExpressInsurance(byte[] body, String baseUrl) throws ParseFailedException {
        try {
            Document doc = Jsoup.parse(new String(body, StandardCharsets.UTF_8), baseUrl);
            Map<String, ListingItem> unique = new LinkedHashMap<>();
            for (Element row : doc.select("article.item-news")) {
                Element a = row.selectFirst("h2.title-news a[href], h3.title-news a[href]");
                if (a == null) continue;
                String title = nonBlank(a.attr("title"), a.text());
                if (!isVietnamLifeInsuranceRelevant(title)) continue;
                String link = a.absUrl("href");
                if (!title.isBlank() && !link.isBlank()) {
                    unique.putIfAbsent(link, new ListingItem(title, link, null));
                }
            }
            if (unique.isEmpty()) throw new ParseFailedException("VNEXPRESS_INSURANCE: không tìm thấy bài");
            return List.copyOf(unique.values());
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("VNEXPRESS_INSURANCE: lỗi parse HTML: " + e.getMessage());
        }
    }

    /** Báo Đầu tư's exact life-insurance tag; article pages provide authoritative dates. */
    public List<ListingItem> parseBaoDauTuLife(byte[] body, String baseUrl) throws ParseFailedException {
        return parseCardLinks(body, baseUrl,
                "ul.list_news_home article .desc_list_news_home > a.fs22[href]",
                null, "BAODAUTU_LIFE");
    }

    /**
     * Thoi bao Tai chinh Viet Nam's category and advertised RSS currently return
     * an empty/general shell. Its own public search is the stable discovery surface.
     * Scope the selector to the result container so header tickers never become docs.
     */
    public List<ListingItem> parseTbtcoLifeSearch(byte[] body, String baseUrl)
            throws ParseFailedException {
        return parseCardLinks(body, baseUrl,
                ".bx-results .bx-cat-content.__MB_LIST_ITEM .article "
                        + "h3.article-title a.article-link[href]",
                null, "TBTCO_LIFE_SEARCH");
    }

    /**
     * VietnamFinance has a high-yield consumer-finance archive but mixes tax, credit
     * and private-insurance stories. Select only private/life-insurance evidence before
     * downloading article bodies so irrelevant news never enters the corpus.
     */
    public List<ListingItem> parseVietnamFinanceLife(byte[] body, String baseUrl)
            throws ParseFailedException {
        try {
            Document doc = Jsoup.parse(new String(body, StandardCharsets.UTF_8), baseUrl);
            Map<String, ListingItem> unique = new LinkedHashMap<>();
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            for (Element row : doc.select(".article.article_last")) {
                Element a = row.selectFirst(".article__title a[href]");
                if (a == null) continue;
                String title = nonBlank(a.attr("title"), a.text());
                if (!isVietnamLifeInsuranceRelevant(title)) continue;
                String link = a.absUrl("href");
                Instant date = null;
                String rawDate = row.attr("last-push").strip();
                if (!rawDate.isBlank()) {
                    try { date = LocalDateTime.parse(rawDate, fmt).atZone(VN_ZONE).toInstant(); }
                    catch (Exception ignored) {}
                }
                if (!title.isBlank() && !link.isBlank()) {
                    unique.putIfAbsent(link, new ListingItem(title, link, date));
                }
            }
            if (unique.isEmpty()) {
                throw new ParseFailedException("VIETNAMFINANCE_LIFE: khong tim thay bai bao hiem nhan tho");
            }
            return List.copyOf(unique.values());
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("VIETNAMFINANCE_LIFE: loi parse HTML: " + e.getMessage());
        }
    }

    /** Keep a broad RSS useful without importing an entire finance newsroom. */
    public List<RssItem> selectVietnamLifeInsurance(List<RssItem> items) throws ParseFailedException {
        List<RssItem> selected = items.stream()
                .filter(item -> isVietnamLifeInsuranceRelevant(
                        item.title() + " " + item.descriptionText()))
                .toList();
        if (selected.isEmpty()) {
            throw new ParseFailedException("Feed hien tai khong co bai bao hiem tu nhan/nhan tho");
        }
        return selected;
    }

    static boolean isVietnamLifeInsuranceRelevant(String value) {
        if (value == null || value.isBlank()) return false;
        String folded = foldVietnamese(value);
        boolean explicitLife = hasExplicitVietnamLifeMarker(folded);
        boolean explicitNonLife = folded.contains("bao hiem phi nhan tho")
                || folded.contains("phi nhan tho") || folded.contains("non-life")
                || folded.contains("nonlife") || folded.contains("property and casualty")
                || folded.contains("p&c") || folded.contains("pvi insurance")
                || folded.matches(".*\\bbao minh\\b.*") || folded.matches(".*\\bbic\\b.*")
                || folded.matches(".*\\bopes\\b.*");
        if (explicitNonLife && !explicitLife) return false;
        return folded.contains("bao hiem nhan tho")
                || folded.contains("nganh bao hiem viet nam")
                || folded.contains("thi truong bao hiem")
                || folded.contains("doanh nghiep bao hiem")
                || folded.contains("bao hiem lien ket")
                || folded.contains("bao hiem huu tri")
                || folded.contains("bao hiem nhom")
                || folded.contains("bancassurance")
                || folded.contains("insurtech")
                || folded.contains("vietnam life insurance")
                || folded.contains("vietnam's life insurance")
                || folded.contains("vietnam insurance market")
                || folded.contains("vietnamese insurer")
                || folded.contains("vietnam insurer")
                || explicitLife;
    }

    /** Caller supplies already accent-folded, lower-case text. */
    private static boolean hasExplicitVietnamLifeMarker(String folded) {
        return folded.contains("bao hiem nhan tho")
                || folded.contains("life insurance") || folded.contains("life insurer")
                || folded.contains("bao hiem huu tri") || folded.contains("huu tri bo sung")
                || folded.contains("bancassurance") || folded.contains("insurtech")
                || folded.contains("bao hiem lien ket") || folded.contains("bao hiem nhom")
                || folded.contains("prudential") || folded.contains("manulife")
                || folded.contains("dai-ichi") || folded.contains("daiichi life")
                || folded.contains("generali") || folded.contains("chubb life")
                || folded.contains("sun life") || folded.contains("fwd viet nam")
                || folded.contains("aia viet nam") || folded.contains("bao viet life")
                || folded.contains("bao viet nhan tho") || folded.contains("mb life")
                || folded.contains("mb ageas") || folded.contains("techcom life")
                || folded.contains("cathay life") || folded.contains("shinhan life")
                || folded.contains("hanwha life") || folded.contains("fubon life")
                || folded.contains("phu hung life") || folded.contains("bidv metlife")
                || folded.contains("mvi life") || folded.contains("lpbank life")
                || folded.contains("lp life");
    }

    private static String foldVietnamese(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('đ', 'd').replace('Đ', 'D')
                .toLowerCase(Locale.ROOT);
    }

    /** VnEconomy's insurance category; dates are present on article detail pages. */
    public List<ListingItem> parseVnEconomyInsurance(byte[] body, String baseUrl) throws ParseFailedException {
        List<ListingItem> cards = parseCardLinks(body, baseUrl, "article.new-item_vertical h3 a[href], "
                + "article.new-item_horizontal h3 a[href]", null, "VNECONOMY");
        // The right rail and "latest" modules reuse the category-card classes. Filter
        // before article fetch so gold, tax, equities and generic banking never enter
        // the insurance corpus merely because they were recommended on this page.
        return cards.stream()
                .filter(item -> isVietnamLifeInsuranceRelevant(item.title()))
                .toList();
    }

    /** National Statistics Office monthly macro releases—the time spine for every report. */
    public List<ListingItem> parseNsoMonthly(byte[] body, String baseUrl) throws ParseFailedException {
        try {
            Document doc = Jsoup.parse(new String(body, StandardCharsets.UTF_8), baseUrl);
            Map<String, ListingItem> unique = new LinkedHashMap<>();
            for (Element section : doc.select(".archive-container section.item")) {
                Element heading = section.selectFirst("h3");
                Element anchor = section.parent();
                while (anchor != null && !anchor.is("a[href]")) anchor = anchor.parent();
                if (heading == null || anchor == null) continue;
                String title = heading.text().strip();
                String link = anchor.absUrl("href");
                Element date = section.selectFirst(".archive-issue-date");
                Instant published = date == null ? null : dateFromText(date.text());
                if (!title.isBlank() && !link.isBlank()) {
                    unique.putIfAbsent(link, new ListingItem(title, link, published));
                }
            }
            // Some HTML parsers close the intentionally malformed <p><a><section> structure.
            // Pair anchors and sections by their original order as a deterministic fallback.
            if (unique.isEmpty()) {
                Elements anchors = doc.select(".archive-container > p > a[href]");
                Elements sections = doc.select(".archive-container section.item");
                for (int i = 0; i < Math.min(anchors.size(), sections.size()); i++) {
                    String title = sections.get(i).selectFirst("h3").text().strip();
                    String link = anchors.get(i).absUrl("href");
                    Element date = sections.get(i).selectFirst(".archive-issue-date");
                    unique.putIfAbsent(link, new ListingItem(title, link,
                            date == null ? null : dateFromText(date.text())));
                }
            }
            if (unique.isEmpty()) throw new ParseFailedException("NSO_VN: không tìm thấy monthly releases");
            return List.copyOf(unique.values());
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("NSO_VN: lỗi parse HTML: " + e.getMessage());
        }
    }

    private List<ListingItem> parseCardLinks(byte[] body, String baseUrl, String selector,
                                             String dateSelector, String code) throws ParseFailedException {
        try {
            Document doc = Jsoup.parse(new String(body, StandardCharsets.UTF_8), baseUrl);
            Map<String, ListingItem> unique = new LinkedHashMap<>();
            for (Element a : doc.select(selector)) {
                String title = nonBlank(a.attr("title"), Jsoup.parse(a.attr("aria-label")).text(), a.text());
                String link = a.absUrl("href");
                Instant date = dateFromText(title + " " + link);
                if (!title.isBlank() && !link.isBlank()) unique.putIfAbsent(link, new ListingItem(title, link, date));
            }
            if (unique.isEmpty()) throw new ParseFailedException(code + ": listing selector returned no articles");
            return List.copyOf(unique.values());
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException(code + ": lỗi parse HTML: " + e.getMessage());
        }
    }

    private static String nonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.strip();
        return "";
    }

    /**
     * Official statutory-report pages are a distinct evidence lane from company news.
     * This parser keeps only current reporting periods and returns the actual report
     * document/intermediate URL, never the generic landing page.  It intentionally does
     * not infer a publication date from the accounting period; the closest explicit page
     * date (or the HTTP Last-Modified header during download) supplies that evidence.
     */
    public List<ListingItem> parseFinancialReportLinks(byte[] body, String baseUrl, String code)
            throws ParseFailedException {
        try {
            String rawHtml = new String(body, StandardCharsets.UTF_8);
            Document doc = Jsoup.parse(rawHtml, baseUrl);
            Map<String, ListingItem> unique = new LinkedHashMap<>();
            for (Element a : doc.select("a[href]")) {
                addFinancialLink(unique, a.absUrl("href"),
                        nonBlank(a.attr("title"), a.attr("aria-label"), a.text()),
                        financialContext(a), code);
            }
            // Some official CMS pages (notably Phu Hung Life) serialise the real
            // report anchors inside a JavaScript JSON string. Jsoup correctly treats
            // script contents as data, so perform one bounded, deterministic pass over
            // decoded anchor markup rather than executing the script.
            String decoded = rawHtml.replace("\\\"", "\"")
                    .replace("\\/", "/")
                    .replace("\\u003c", "<")
                    .replace("\\u003e", ">");
            var embeddedAnchors = java.util.regex.Pattern.compile(
                    "(?is)<a\\s+[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>")
                    .matcher(decoded);
            while (embeddedAnchors.find()) {
                String href = embeddedAnchors.group(1).strip();
                String link;
                try {
                    link = URI.create(baseUrl).resolve(href.replace(" ", "%20")).toASCIIString();
                } catch (Exception invalid) {
                    continue;
                }
                int from = Math.max(0, embeddedAnchors.start() - 240);
                int to = Math.min(decoded.length(), embeddedAnchors.end() + 240);
                addFinancialLink(unique, link, Jsoup.parse(embeddedAnchors.group(2)).text(),
                        Jsoup.parse(decoded.substring(from, to)).text(), code);
            }
            // Dai-ichi uses a same-host PDF.js wrapper whose only real document URL is
            // carried in iframe ?file=.  Resolve and store that official PDF URL.
            for (Element iframe : doc.select("iframe[src*='file=']")) {
                String src = iframe.absUrl("src");
                int fileAt = src.indexOf("file=");
                if (fileAt < 0) continue;
                String encoded = src.substring(fileAt + 5);
                int amp = encoded.indexOf('&');
                if (amp >= 0) encoded = encoded.substring(0, amp);
                String decodedFile = java.net.URLDecoder.decode(encoded, StandardCharsets.UTF_8);
                String link = URI.create(baseUrl).resolve(decodedFile.replace(" ", "%20")).toString();
                addFinancialLink(unique, link, titleFromUrl(link), doc.title(), code);
            }
            // AIA Vietnam publishes the statutory summary as a first-party Dynamic
            // Media image. Scene7 deterministically serves the exact same asset as PDF
            // with fmt=pdf, allowing the existing bounded PDF/OCR path to extract the
            // figures instead of storing an empty HTML wrapper or relying on vision AI.
            if ("AIA_VN_FINANCIALS".equals(code)) {
                for (Element image : doc.select("img[src*='scene7.com/is/image/aia/']")) {
                    String src = image.absUrl("src");
                    try {
                        URI uri = URI.create(src);
                        if (!"s7ap1.scene7.com".equalsIgnoreCase(uri.getHost())) continue;
                        String link = new URI("https", uri.getAuthority(), uri.getPath(),
                                "fmt=pdf", null).toASCIIString();
                        String title = nonBlank(doc.title(), image.attr("alt"), titleFromUrl(link));
                        addFinancialLink(unique, link, title, doc.text(), code);
                    } catch (Exception ignored) {
                        // A malformed image URL is skipped; it never weakens host checks.
                    }
                }
            }
            if (unique.isEmpty()) {
                throw new ParseFailedException(code + ": no current statutory financial-report links found");
            }
            return List.copyOf(unique.values());
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException(code + ": financial listing parse failed: " + e.getMessage());
        }
    }

    /**
     * Techcombank investor releases are an official view of bancassurance economics.
     * Keep only current quarterly press-release PDFs; the bank's generic IR page also
     * contains ratings, shareholder notices and unrelated bank documents.
     */
    public List<ListingItem> parseTechcombankLifeResults(byte[] body, String baseUrl)
            throws ParseFailedException {
        try {
            Document doc = Jsoup.parse(new String(body, StandardCharsets.UTF_8), baseUrl);
            Map<String, ListingItem> unique = new LinkedHashMap<>();
            for (Element a : doc.select("a.list-row-content_item[href$=.pdf]")) {
                String link = a.absUrl("href");
                String title = nonBlank(a.select("h3.list-row-content_title").text(), a.attr("title"));
                String primary = title + " " + link;
                int currentYear = LocalDate.now(VN_ZONE).getYear();
                boolean current = primary.contains(String.valueOf(currentYear))
                        || link.toLowerCase(Locale.ROOT).matches(".*(?:1q|2q|3q|4q)"
                                + String.valueOf(currentYear).substring(2) + ".*");
                boolean resultRelease = link.toLowerCase(Locale.ROOT).contains("press-release")
                        || Normalizer.normalize(title, Normalizer.Form.NFD)
                        .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT)
                        .contains("cap nhat kqkd");
                if (!current || !resultRelease || link.isBlank()) continue;
                unique.putIfAbsent(link, new ListingItem(title, link, dateFromText(a.text())));
            }
            if (unique.isEmpty()) throw new ParseFailedException(
                    "TECHCOMBANK_IR_LIFE_RESULTS: no current quarterly press-release PDF");
            return List.copyOf(unique.values());
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("TECHCOMBANK_IR_LIFE_RESULTS parse failed: " + e.getMessage());
        }
    }

    /** FWD's Next.js payload publishes official statutory PDFs on its fixed CDN. */
    public List<ListingItem> parseFwdFinancialLinks(byte[] body) throws ParseFailedException {
        try {
            String text = new String(body, StandardCharsets.UTF_8)
                    .replace("\\u002D", "-").replace("\\/", "/");
            var links = java.util.regex.Pattern.compile(
                    "https://assets\\.contentstack\\.io/[A-Za-z0-9_./%~-]+\\.pdf",
                    java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
            Map<String, ListingItem> unique = new LinkedHashMap<>();
            while (links.find()) {
                String link = links.group().strip();
                String title = titleFromUrl(link);
                if (!isCurrentFinancialReport(title + " " + link)) continue;
                unique.putIfAbsent(link, new ListingItem(title, link, null));
            }
            if (unique.isEmpty()) throw new ParseFailedException(
                    "FWD_VN_FINANCIALS: no current official CDN report PDFs");
            return List.copyOf(unique.values());
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("FWD_VN_FINANCIALS parse failed: " + e.getMessage());
        }
    }

    /** Generali's Next.js HTML serialises the report routes as React-flight data. */
    public List<ListingItem> parseGeneraliFinancialLinks(byte[] body, String baseUrl)
            throws ParseFailedException {
        try {
            String text = new String(body, StandardCharsets.UTF_8)
                    .replace("\\\"", "\"")
                    .replace("\\u003c", "<")
                    .replace("\\u003e", ">");
            var matcher = java.util.regex.Pattern.compile(
                    "\\\"display_name\\\":\\\"([^\\\"]+)\\\",\\\"slug\\\":\\\"([^\\\"]+)\\\",\\\"announce\\\":(null|\\\"([^\\\"]*)\\\")")
                    .matcher(text);
            Map<String, ListingItem> unique = new LinkedHashMap<>();
            URI base = URI.create(baseUrl);
            String origin = base.getScheme() + "://" + base.getAuthority();
            while (matcher.find()) {
                String title = matcher.group(1).strip();
                if (!isCurrentFinancialReport(title)) continue;
                String link = origin + "/page/thu-vien-thong-tin/tai-lieu-bieu-mau/"
                        + matcher.group(2).strip();
                String announce = matcher.group(4) == null ? "" : Jsoup.parse(matcher.group(4)).text();
                unique.putIfAbsent(link, new ListingItem(title, link, dateFromText(announce)));
            }
            if (unique.isEmpty()) {
                throw new ParseFailedException("GENERALI_VN_FINANCIALS: no current report routes in Next data");
            }
            return List.copyOf(unique.values());
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("GENERALI_VN_FINANCIALS parse failed: " + e.getMessage());
        }
    }

    /** BIDV MetLife's public forms-library endpoint is the data source used by its own UI. */
    public List<ListingItem> parseBidvMetlifeFinancials(byte[] body, String baseUrl)
            throws ParseFailedException {
        try {
            JsonNode docs = JSON.readTree(body).path("response").path("docs");
            if (!docs.isArray()) throw new ParseFailedException("BIDV_METLIFE_FINANCIALS: docs array missing");
            URI base = URI.create(baseUrl);
            String origin = base.getScheme() + "://" + base.getAuthority();
            Map<String, ListingItem> unique = new LinkedHashMap<>();
            for (JsonNode doc : docs) {
                String title = doc.path("file_title").asText("").strip();
                String file = doc.path("file_url").asText("").strip();
                if (title.isBlank() || file.isBlank() || !isCurrentFinancialReport(title + " " + file)) continue;
                JsonNode uploaded = doc.path("file_uploaded");
                Instant date = null;
                if (uploaded.isObject()) {
                    try {
                        // The MetLife component exposes java.time.MonthValue (zero-based in JSON).
                        date = LocalDate.of(uploaded.path("year").asInt(),
                                        uploaded.path("month").asInt() + 1,
                                        uploaded.path("dayOfMonth").asInt())
                                .atStartOfDay(VN_ZONE).toInstant();
                    } catch (Exception ignored) {}
                }
                String link = URI.create(origin + "/")
                        .resolve(file.replace(" ", "%20")).toASCIIString();
                unique.putIfAbsent(link, new ListingItem(title, link, date));
            }
            if (unique.isEmpty()) throw new ParseFailedException("BIDV_METLIFE_FINANCIALS: no current reports");
            return List.copyOf(unique.values());
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("BIDV_METLIFE_FINANCIALS parse failed: " + e.getMessage());
        }
    }

    /** Sun Life's WAF requires the explicitly allow-listed Reader transport. */
    public List<ListingItem> parseSunLifeFinancialReader(byte[] body) throws ParseFailedException {
        return parseReaderFinancialLinks(body, "www.sunlife.com.vn", "SUNLIFE_VN_FINANCIALS");
    }

    /** Reader-rendered official report page; PDF host must still match the declared publisher. */
    public List<ListingItem> parseReaderFinancialLinks(byte[] body, String officialHost, String code)
            throws ParseFailedException {
        try {
            String markdown = new String(body, StandardCharsets.UTF_8);
            Map<String, ListingItem> unique = new LinkedHashMap<>();
            // Bind each link to its own heading block. A sliding character window can
            // leak "2025" from the adjacent section into a 2023/2024 report and falsely
            // classify old evidence as current.
            var sections = java.util.regex.Pattern.compile(
                    "(?ms)^#{2,6}\\s+([^\\r\\n]+)\\R(.*?)(?=^#{2,6}\\s+|\\z)")
                    .matcher(markdown);
            while (sections.find()) {
                // Some publisher headings are themselves links to "#". Preserve the
                // visible label; deleting the whole Markdown link erased the report year
                // and made a valid current PDF look undated (Bảo Việt Nhân thọ).
                String heading = sections.group(1)
                        .replaceAll("\\[([^]]*)]\\([^)]*\\)", "$1").strip();
                String section = sections.group(2);
                var links = java.util.regex.Pattern.compile(
                        "\\[([^]\\r\\n]*)]\\((https://[^)]+\\.pdf)\\)",
                        java.util.regex.Pattern.CASE_INSENSITIVE).matcher(section);
                while (links.find()) {
                    String link = links.group(2).strip();
                    String host;
                    try { host = URI.create(link).getHost(); } catch (Exception invalid) { continue; }
                    if (host == null || !host.equalsIgnoreCase(officialHost)) continue;
                    String title = nonBlank(heading, links.group(1), titleFromUrl(link));
                    String context = heading + "\n" + section;
                    addFinancialLink(unique, link, title, context, code);
                }
            }
            if (unique.isEmpty()) {
                throw new ParseFailedException(code + ": Reader exposed no current official PDF");
            }
            return List.copyOf(unique.values());
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException(code + " parse failed: " + e.getMessage());
        }
    }

    /** Shinhan's Reader page is a flat link list rather than heading-delimited sections. */
    public List<ListingItem> parseShinhanFinancialReader(byte[] body) throws ParseFailedException {
        try {
            String markdown = new String(body, StandardCharsets.UTF_8).replace("\r\n", "\n");
            var links = java.util.regex.Pattern.compile(
                    "(?m)^\\[([^]\\r\\n]+)]\\((https://www\\.shinhanlifevn\\.com\\.vn/"
                            + "media/[^)]+\\.pdf)\\)\\s*$",
                    java.util.regex.Pattern.CASE_INSENSITIVE).matcher(markdown);
            Map<String, ListingItem> unique = new LinkedHashMap<>();
            while (links.find()) {
                int to = Math.min(markdown.length(), links.end() + 100);
                String context = markdown.substring(links.start(), to);
                addFinancialLink(unique, links.group(2).strip(), links.group(1).strip(),
                        context, "SHINHAN_VN_FINANCIALS");
            }
            if (unique.isEmpty()) throw new ParseFailedException(
                    "SHINHAN_VN_FINANCIALS: Reader exposed no current HTTPS official PDF");
            return List.copyOf(unique.values());
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("SHINHAN_VN_FINANCIALS parse failed: " + e.getMessage());
        }
    }

    private static void addFinancialLink(Map<String, ListingItem> unique, String link,
                                         String title, String context, String code) {
        if (link == null || link.isBlank() || !link.startsWith("https://")) return;
        try {
            URI uri = URI.create(link);
            String path = uri.getPath() == null ? "" : uri.getPath();
            String foldedPath = path.toLowerCase(Locale.ROOT);
            // Navigation/login links often sit inside the same DOM card as the report
            // title. They are not evidence documents and must not consume crawl budget.
            if (path.isBlank() || "/".equals(path) || foldedPath.contains("/auth/login")) return;
            // Empty fragments create a second URL for the exact same page.
            if (uri.getFragment() != null) {
                link = new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(),
                        uri.getQuery(), null).toASCIIString();
            }
        } catch (Exception invalid) {
            return;
        }
        String candidate = nonBlank(title, titleFromUrl(link));
        // The URL is the strongest period signal, but the visible label still carries
        // the semantic marker (for example "Báo cáo tài chính").  Preserve those words
        // while stripping competing label/context years whenever the URL has its own
        // year. This prevents a 2024 PDF under a 2025 heading from passing, without
        // losing the document type merely because its filename uses English hyphens.
        String decodedLink;
        try {
            decodedLink = java.net.URLDecoder.decode(link, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            decodedLink = link;
        }
        boolean urlHasYear = java.util.regex.Pattern.compile("(?<!\\d)20\\d{2}(?!\\d)")
                .matcher(decodedLink).find();
        String combined = urlHasYear
                ? (candidate + " " + (context == null ? "" : context))
                        .replaceAll("20\\d{2}", "") + " " + decodedLink
                : financialEvidenceText(candidate + " " + decodedLink, context);
        if (!isCurrentFinancialReport(combined)) return;
        unique.putIfAbsent(link, new ListingItem(candidate, link, dateFromText(context)));
    }

    /**
     * Prefer the report's own title/URL year. Only use surrounding section text when
     * the primary link has no explicit year. This prevents a current section heading
     * from making an adjacent 2023/2024 report look current.
     */
    private static String financialEvidenceText(String primary, String context) {
        String safePrimary = primary == null ? "" : primary;
        if (java.util.regex.Pattern.compile("20\\d{2}").matcher(safePrimary).find()) {
            return safePrimary;
        }
        return safePrimary + " " + (context == null ? "" : context);
    }

    private static String financialContext(Element element) {
        Element parent = element.closest("tr, li, article, .cmp-accordion__item, .document, .report-item, .item");
        if (parent == null) parent = element.parent();
        return parent == null ? element.text() : parent.text();
    }

    private static boolean isCurrentFinancialReport(String raw) {
        if (raw == null) return false;
        String folded = Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        boolean financial = folded.contains("bao cao tai chinh")
                || folded.contains("financial statement")
                || folded.contains("financial report")
                || folded.contains("statutory report")
                || folded.contains("ket qua kinh doanh")
                || folded.contains("bc-kqkd")
                || folded.contains("bc kqkd")
                || folded.contains("bc-tc")
                || folded.contains("bc tc")
                || folded.matches("(?s).*\\bbctc\\b.*");
        if (!financial || folded.contains("quy lien ket") || folded.contains("fund report")) return false;
        int floor = LocalDate.now(VN_ZONE).getYear() - 1;
        var year = java.util.regex.Pattern.compile("20\\d{2}").matcher(folded);
        while (year.find()) {
            int value = Integer.parseInt(year.group());
            if (value >= floor && value <= floor + 1) return true;
        }
        return false;
    }

    private static Instant dateFromText(String text) {
        if (text == null) return null;
        var dmy = java.util.regex.Pattern.compile("(?<!\\d)(\\d{1,2}/\\d{1,2}/20\\d{2})(?!\\d)").matcher(text);
        if (dmy.find()) {
            try { return plausiblePublishedInstant(LocalDate.parse(
                    dmy.group(1), DateTimeFormatter.ofPattern("d/M/uuuu"))); }
            catch (Exception ignored) {}
        }
        var dotted = java.util.regex.Pattern.compile("(?<!\\d)(\\d{1,2}\\.\\d{1,2}\\.20\\d{2})(?!\\d)")
                .matcher(text);
        if (dotted.find()) {
            try { return plausiblePublishedInstant(LocalDate.parse(
                    dotted.group(1), DateTimeFormatter.ofPattern("d.M.uuuu"))); }
            catch (Exception ignored) {}
        }
        var iso = ISO_YMD.matcher(text);
        if (iso.find()) {
            try { return plausiblePublishedInstant(LocalDate.parse(iso.group(1))); }
            catch (Exception ignored) {}
        }
        return null;
    }

    private static Instant plausiblePublishedInstant(LocalDate date) {
        if (date == null || date.isAfter(LocalDate.now(VN_ZONE).plusDays(1))) return null;
        return date.atStartOfDay(VN_ZONE).toInstant();
    }

    /**
     * Jina Reader is an explicitly allow-listed transport fallback for official sites whose
     * WAF rejects our server-side client.  It returns deterministic Markdown; attribution and
     * the stored URL remain the official publisher URL.  These parsers deliberately accept
     * only the two known official archive shapes, never arbitrary Reader search results.
     */
    public List<ListingItem> parseSunLifeReaderListing(byte[] body) throws ParseFailedException {
        try {
            String markdown = new String(body, StandardCharsets.UTF_8);
            var pattern = java.util.regex.Pattern.compile(
                    "(?m)^(\\d{1,2}/\\d{1,2}/20\\d{2})\\s*\\R+"
                    + "\\[([^]\\r\\n]+)]\\((https://www\\.sunlife\\.com\\.vn/"
                    + "vn/ve-chung-toi/tin-tuc-su-kien/20\\d{2}/(?!\\d+/)[^)]+)\\)");
            var matcher = pattern.matcher(markdown);
            Map<String, ListingItem> unique = new LinkedHashMap<>();
            while (matcher.find()) {
                String link = matcher.group(3).strip();
                unique.putIfAbsent(link, new ListingItem(matcher.group(2).strip(), link,
                        dateFromText(matcher.group(1))));
            }
            if (unique.isEmpty()) {
                throw new ParseFailedException("SUNLIFE_VN reader listing returned no dated official articles");
            }
            return List.copyOf(unique.values());
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("SUNLIFE_VN reader listing parse failed: " + e.getMessage());
        }
    }

    /** Return only same-year archive pagination links; article links are excluded by shape. */
    public List<String> parseSunLifeReaderPagination(byte[] body) {
        String markdown = new String(body, StandardCharsets.UTF_8);
        var pattern = java.util.regex.Pattern.compile(
                "https://www\\.sunlife\\.com\\.vn/vn/ve-chung-toi/tin-tuc-su-kien/20\\d{2}/\\d+/");
        var matcher = pattern.matcher(markdown);
        java.util.LinkedHashSet<String> unique = new java.util.LinkedHashSet<>();
        while (matcher.find()) unique.add(matcher.group());
        return List.copyOf(unique);
    }

    public List<ListingItem> parseBaoVietReaderListing(byte[] body) throws ParseFailedException {
        try {
            String markdown = new String(body, StandardCharsets.UTF_8);
            var pattern = java.util.regex.Pattern.compile(
                    "\\[([^]\\r\\n]+)]\\((https://www\\.baovietnhantho\\.com\\.vn/"
                    + "tin-tuc/(?!danh-muc(?:/|\\)))[^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");
            var matcher = pattern.matcher(markdown);
            Map<String, ListingItem> unique = new LinkedHashMap<>();
            while (matcher.find()) {
                String title = matcher.group(1).strip();
                String link = matcher.group(2).strip();
                if (!title.isBlank() && !title.equalsIgnoreCase("Xem thêm")) {
                    unique.putIfAbsent(link, new ListingItem(title, link, null));
                }
            }
            if (unique.isEmpty()) {
                throw new ParseFailedException("BVNT reader listing returned no official articles");
            }
            return List.copyOf(unique.values());
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("BVNT reader listing parse failed: " + e.getMessage());
        }
    }

    /**
     * Bao Viet Holdings is a separate legal/reporting entity from Bao Viet Life. Keep the
     * source identity explicit and select only group results that contain useful life-insurer
     * evidence; procurement and securities-administration notices are excluded upstream.
     */
    public List<ListingItem> parseBaoVietHoldingsReaderListing(byte[] body)
            throws ParseFailedException {
        try {
            String markdown = new String(body, StandardCharsets.UTF_8);
            var pattern = java.util.regex.Pattern.compile(
                    "(?m)^### \\[([^]\\r\\n]+)]\\((https://www\\.baoviet\\.com\\.vn/"
                    + "(?:vi|en)/[^)#?]+)\\)\\s*\\R+(\\d{1,2}[./]\\d{1,2}[./]20\\d{2})\\s*$");
            var matcher = pattern.matcher(markdown);
            Map<String, ListingItem> unique = new LinkedHashMap<>();
            while (matcher.find()) {
                String title = matcher.group(1).strip();
                if (!isBaoVietHoldingsLifeEvidence(title)) continue;
                String link = matcher.group(2).strip();
                unique.putIfAbsent(link, new ListingItem(title, link, dateFromText(matcher.group(3))));
            }
            return List.copyOf(unique.values());
        } catch (Exception e) {
            throw new ParseFailedException("BAOVIET_HOLDINGS_NEWS reader parse failed: " + e.getMessage());
        }
    }

    private static boolean isBaoVietHoldingsLifeEvidence(String title) {
        if (isVietnamLifeInsuranceRelevant(title)) return true;
        String folded = Normalizer.normalize(title == null ? "" : title, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('đ', 'd').replace('Đ', 'D')
                .toLowerCase(Locale.ROOT);
        boolean correctGroup = folded.contains("tap doan bao viet")
                || folded.contains("bao viet (bvh)")
                || folded.contains("baoviet holdings");
        boolean resultEvidence = folded.contains("ket qua kinh doanh")
                || folded.contains("doanh thu")
                || folded.contains("loi nhuan")
                || folded.contains("tong tai san")
                || folded.contains("financial result");
        return correctGroup && resultEvidence;
    }

    /** Parse one Reader-rendered official article into clean text and reliable page metadata. */
    public ReaderArticle parseReaderArticle(byte[] body) throws ParseFailedException {
        try {
            String response = new String(body, StandardCharsets.UTF_8).replace("\r\n", "\n");
            String title = "";
            var titleMatcher = java.util.regex.Pattern.compile("(?m)^Title:\\s*(.+)$").matcher(response);
            if (titleMatcher.find()) title = titleMatcher.group(1).strip();
            int markdownMarker = response.indexOf("Markdown Content:");
            String markdown = markdownMarker >= 0
                    ? response.substring(markdownMarker + "Markdown Content:".length()).strip()
                    : response.strip();

            // Navigation precedes the article on both official sites.  The first H1 is the
            // article's semantic boundary; keep no menus above it.
            Instant boundaryPublishedAt = null;
            var h1 = java.util.regex.Pattern.compile("(?m)^#\\s+(.+)$").matcher(markdown);
            if (h1.find()) {
                if (title.isBlank()) title = h1.group(1).strip();
                // Some official Reader pages put the true publication date immediately
                // before the article H1 (for example "Khuyến mãi 31/07/2026").  Dates
                // inside the body may instead be programme end dates. Prefer the semantic
                // boundary metadata before falling back to article text.
                int contextStart = Math.max(0, h1.start() - 400);
                boundaryPublishedAt = dateFromText(markdown.substring(contextStart, h1.start()));
                markdown = markdown.substring(h1.start());
            }
            int cut = markdown.length();
            for (String marker : List.of("\n### Truy cập nhanh", "\n## Tin liên quan",
                    "\n### Liên hệ", "\nCopyright ©", "\n* * *\n### Sản phẩm")) {
                int at = markdown.indexOf(marker);
                if (at >= 0 && at < cut) cut = at;
            }
            markdown = markdown.substring(0, cut);
            Instant publishedAt = boundaryPublishedAt == null
                    ? dateFromText(markdown) : boundaryPublishedAt;

            String text = markdown
                    .replaceAll("!\\[[^]]*]\\([^)]*\\)", " ")
                    .replaceAll("\\[([^]]+)]\\([^)]*\\)", "$1")
                    .replaceAll("(?m)^#{1,6}\\s*", "")
                    .replaceAll("(?m)^\\s*[-*]\\s+", "• ")
                    .replaceAll("(?m)^\\s*\\|?[-: ]{3,}(?:\\|[-: ]{3,})+\\|?\\s*$", "")
                    .replace("**", "")
                    .replace("__", "")
                    .replaceAll("[ \\t]+", " ")
                    .replaceAll("\\n{3,}", "\\n\\n")
                    .strip();
            if (text.length() < 300) {
                throw new ParseFailedException("Reader article contained only " + text.length()
                        + " clean characters after navigation removal");
            }
            return new ReaderArticle(title, text, publishedAt);
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("Reader article parse failed: " + e.getMessage());
        }
    }

    /** PDF → text thuần, giới hạn trang. */
    public ParsedText parsePdf(byte[] body) throws ParseFailedException {
        try (PDDocument doc = PDDocument.load(body)) {
            if (doc.isEncrypted()) {
                throw new ParseFailedException("PDF mã hoá — không xử lý");
            }
            int pages = Math.min(doc.getNumberOfPages(), PDF_MAX_PAGES);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(pages);
            String text = stripper.getText(doc);
            if (text == null || text.strip().length() < 200) {
                int ocrPages = Math.min(doc.getNumberOfPages(), PDF_OCR_MAX_PAGES);
                var ocr = MacVisionPdfOcr.extract(body, ocrPages);
                if (ocr.isPresent()) {
                    String note = "OCR cục bộ bằng macOS Vision: " + ocr.get().pages()
                            + "/" + doc.getNumberOfPages() + " trang; không dùng API";
                    return new ParsedText(null, ocr.get().text(), note);
                }
                throw new ParseFailedException(
                        "PDF không có text nhúng và local OCR không khả dụng/không đọc được");
            }
            String note = doc.getNumberOfPages() > PDF_MAX_PAGES
                    ? "Cắt ở " + PDF_MAX_PAGES + "/" + doc.getNumberOfPages() + " trang" : null;
            return new ParsedText(null, text, note);
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("PDFBox lỗi: " + e.getMessage());
        }
    }

    /**
     * IAV (iav.vn) — trang danh sách tin, WordPress-theme, KHÔNG có RSS (Group B).
     * Cấu trúc xác nhận qua fetch trực tiếp 05/07/2026 (không đoán từ registry notes):
     * mỗi tin là {@code <article class="post-item">} (có thêm biến thể class
     * "no-image" / "post-new" — 3 biến thể markup khác nhau cho cùng khái niệm
     * "1 tin"), tiêu đề nằm trong thẻ {@code <h5>} hoặc {@code <h6>} bên trong
     * article (không phụ thuộc class cụ thể của heading). Ngày nằm trong
     * {@code <time datetime="...">} nhưng attribute datetime có LẪN 2 định dạng
     * khác nhau trên cùng 1 trang ("Jul 02, 2026" và "02/07/2026 9:14:54 SA" —
     * SA/CH là sáng/chiều tiếng Việt cho AM/PM) — thử cả hai, không throw nếu
     * ngày parse lỗi (publishedAt = null), vì input tối thiểu của 1 tin hợp lệ
     * là tiêu đề + link, không phải ngày (giống policy publishedAt nullable của
     * RSS ở trên).
     */
    public List<ListingItem> parseIav(byte[] body, String baseUrl) throws ParseFailedException {
        try {
            Document doc = Jsoup.parse(new String(body, StandardCharsets.UTF_8), baseUrl);
            Elements articles = doc.select("article.post-item");
            List<ListingItem> items = new ArrayList<>();
            for (Element article : articles) {
                Element heading = article.selectFirst("h5, h6");
                if (heading == null) continue;
                Element a = heading.selectFirst("a");
                if (a == null) continue;
                // Trang gốc tự ý rút gọn text hiển thị bằng "..." (thấy trên biến thể
                // post-new/no-image) nhưng attribute title luôn giữ full text — ưu tiên title.
                String titleAttr = a.attr("title").strip();
                String title = !titleAttr.isBlank() ? titleAttr : a.text().strip();
                String link = a.absUrl("href");
                if (title.isBlank() || link.isBlank()) continue; // thiếu định danh tin — bỏ qua item này, không phải cả trang
                Element timeEl = article.selectFirst("time[datetime]");
                Instant publishedAt = timeEl != null ? parseIavDate(timeEl.attr("datetime")) : null;
                items.add(new ListingItem(title, link, publishedAt));
            }
            if (items.isEmpty()) {
                throw new ParseFailedException("IAV: không tìm thấy article.post-item nào — cấu trúc trang có thể đã đổi");
            }
            return items;
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("Jsoup lỗi khi parse IAV: " + e.getMessage());
        }
    }

    private Instant parseIavDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return java.time.LocalDate.parse(raw.strip(), IAV_FMT_EN).atStartOfDay(VN_ZONE).toInstant();
        } catch (DateTimeParseException ignored) {
            // không đúng format "MMM d, yyyy" — thử format thứ 2 bên dưới
        }
        try {
            String normalized = raw.strip().replace("SA", "AM").replace("CH", "PM");
            return LocalDateTime.parse(normalized, IAV_FMT_VI).atZone(VN_ZONE).toInstant();
        } catch (DateTimeParseException e) {
            log.warn("IAV: không parse được ngày '{}' — publishedAt để null, vẫn giữ lại item", raw);
            return null;
        }
    }

    /**
     * AIA Việt Nam (aia.com.vn) — trang "Sự kiện nổi bật", KHÔNG có RSS (Group B).
     * Cấu trúc xác nhận qua fetch trực tiếp 05/07/2026: mỗi tin là
     * {@code <div class="cmp-promotioncard">} chứa {@code <a class="cmp-promotioncard__link">}
     * (title + href), ngày trong {@code <div class="cmp-promotioncard__date">} dạng "dd/MM/yyyy" —
     * NHƯNG một số card cũ hơn không có div ngày (site tự bỏ) → publishedAt = null cho các item đó,
     * không throw, cùng policy nullable-date như IAV.
     * BUG thật bắt được qua test (AiaParserTest, không phải đoán): cùng class cmp-promotioncard
     * còn được tái dùng cho card ĐIỀU HƯỚNG theo tháng (vd. title "Tháng 7", href ".../2026/07.html")
     * — không phải tin thật. Lọc bỏ bằng pattern href kết thúc "/YYYY/MM.html".
     */
    public List<ListingItem> parseAia(byte[] body, String baseUrl) throws ParseFailedException {
        try {
            Document doc = Jsoup.parse(new String(body, StandardCharsets.UTF_8), baseUrl);
            Elements cards = doc.select("div.cmp-promotioncard");
            List<ListingItem> items = new ArrayList<>();
            for (Element card : cards) {
                Element a = card.selectFirst("a.cmp-promotioncard__link");
                if (a == null) continue;
                String link = a.absUrl("href");
                if (AIA_MONTH_ARCHIVE_LINK.matcher(link).find()) continue; // card điều hướng tháng, không phải tin
                Element titleEl = a.selectFirst(".cmp-promotioncard__title");
                String title = titleEl != null ? titleEl.text().strip() : a.text().strip();
                if (title.isBlank() || link.isBlank()) continue;
                Element dateEl = a.selectFirst(".cmp-promotioncard__date");
                Instant publishedAt = null;
                if (dateEl != null) {
                    try {
                        publishedAt = java.time.LocalDate.parse(dateEl.text().strip(), AIA_FMT)
                                .atStartOfDay(VN_ZONE).toInstant();
                    } catch (DateTimeParseException e) {
                        log.warn("AIA_VN: không parse được ngày '{}' — thử fallback năm từ URL", dateEl.text());
                    }
                }
                if (publishedAt == null) {
                    // Card cũ không có div ngày → lấy năm từ path URL (.../2024/...) làm mốc thô
                    // (năm-01-01). Đủ để bộ lọc độ mới loại đúng tin cũ; KHÔNG để null (sẽ rơi
                    // về fetchedAt=hôm nay và hiện như tin mới — lỗi "mọi tin đều cũ").
                    var ym = AIA_URL_YEAR.matcher(link);
                    if (ym.find()) {
                        publishedAt = java.time.LocalDate.of(Integer.parseInt(ym.group(1)), 1, 1)
                                .atStartOfDay(VN_ZONE).toInstant();
                    } else {
                        log.warn("AIA_VN: card không có ngày và URL không có năm ({}) — publishedAt để null", link);
                    }
                }
                items.add(new ListingItem(title, link, publishedAt));
            }
            if (items.isEmpty()) {
                throw new ParseFailedException("AIA_VN: không tìm thấy div.cmp-promotioncard nào — cấu trúc trang có thể đã đổi");
            }
            return items;
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("Jsoup lỗi khi parse AIA_VN: " + e.getMessage());
        }
    }

    /**
     * Manulife Việt Nam (manulife.com.vn) — trang "Thông cáo báo chí", AEM site, KHÔNG có RSS
     * (Group B). Cấu trúc xác nhận qua fetch trực tiếp 05/07/2026: mỗi tin là
     * {@code <div class="cmp-content-teaser cmp-content-teaser__general">} chứa
     * {@code <a class="cmp-content-teaser__link">} (href) và {@code .cmp-content-teaser__title p}
     * (title) — NHƯNG không có ngày cụ thể trên card. Trang nhóm tin theo NĂM qua các
     * {@code <h2>Năm YYYY</h2>} nằm TRƯỚC các nhóm card tương ứng trong DOM order — publishedAt lấy
     * ở độ chính xác NĂM (01/01 của năm đó), không phải ngày thật; ngày thật đòi hỏi fetch từng
     * trang chi tiết (out of scope cho listing parser 1-fetch). Flag rõ, không giả vờ chính xác hơn.
     */
    public List<ListingItem> parseManulife(byte[] body, String baseUrl) throws ParseFailedException {
        try {
            Document doc = Jsoup.parse(new String(body, StandardCharsets.UTF_8), baseUrl);
            List<ListingItem> items = new ArrayList<>();
            Integer currentYear = null;
            for (Element el : doc.select("h2, div.cmp-content-teaser")) {
                if (el.tagName().equals("h2")) {
                    var m = MANULIFE_YEAR.matcher(el.text());
                    if (m.find()) currentYear = Integer.parseInt(m.group(1));
                    continue;
                }
                Element a = el.selectFirst("a.cmp-content-teaser__link");
                if (a == null) continue;
                Element titleEl = el.selectFirst(".cmp-content-teaser__title");
                String title = titleEl != null ? titleEl.text().strip() : a.text().strip();
                String link = a.absUrl("href");
                if (title.isBlank() || link.isBlank()) continue;
                Instant publishedAt = currentYear != null
                        ? java.time.LocalDate.of(currentYear, 1, 1).atStartOfDay(VN_ZONE).toInstant()
                        : null;
                items.add(new ListingItem(title, link, publishedAt));
            }
            if (items.isEmpty()) {
                throw new ParseFailedException("MANULIFE_VN: không tìm thấy div.cmp-content-teaser nào — cấu trúc trang có thể đã đổi");
            }
            return items;
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("Jsoup lỗi khi parse MANULIFE_VN: " + e.getMessage());
        }
    }

    /**
     * Prudential Việt Nam (prudential.com.vn) — trang "Thông cáo báo chí", KHÔNG có RSS (Group B).
     * Cấu trúc xác nhận qua fetch trực tiếp 05/07/2026: mỗi tin là
     * {@code <article class="article-card" data-date="dd-MM-yyyy">}, title trong
     * {@code <h3 class="article-heading">}, link trong {@code <a class="cta-button" href="...">} —
     * cấu trúc sạch nhất trong 3 site VN insurer đợt này (ngày rõ ràng ở cả attribute lẫn text).
     */
    public List<ListingItem> parsePrudential(byte[] body, String baseUrl) throws ParseFailedException {
        try {
            Document doc = Jsoup.parse(new String(body, StandardCharsets.UTF_8), baseUrl);
            Elements articles = doc.select("article.article-card");
            List<ListingItem> items = new ArrayList<>();
            for (Element article : articles) {
                Element titleEl = article.selectFirst("h3.article-heading");
                Element a = article.selectFirst("a.cta-button");
                if (titleEl == null || a == null) continue;
                String title = titleEl.text().strip();
                String link = a.absUrl("href");
                if (title.isBlank() || link.isBlank()) continue;
                String dateAttr = article.attr("data-date").strip();
                Instant publishedAt = null;
                if (!dateAttr.isBlank()) {
                    try {
                        publishedAt = java.time.LocalDate.parse(dateAttr, PRU_FMT).atStartOfDay(VN_ZONE).toInstant();
                    } catch (DateTimeParseException e) {
                        log.warn("PRUDENTIAL_VN: không parse được ngày '{}' — publishedAt để null", dateAttr);
                    }
                }
                items.add(new ListingItem(title, link, publishedAt));
            }
            if (items.isEmpty()) {
                throw new ParseFailedException("PRUDENTIAL_VN: không tìm thấy article.article-card nào — cấu trúc trang có thể đã đổi");
            }
            return items;
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("Jsoup lỗi khi parse PRUDENTIAL_VN: " + e.getMessage());
        }
    }

    /**
     * Mirae Asset Prévoir (map-life.com.vn) — trang "Tin tức", KHÔNG có RSS (Group B).
     * Cấu trúc xác nhận qua fetch trực tiếp 05/07/2026: mỗi tin là
     * {@code <div class="post-list-right__item">}, title trong
     * {@code .post-list-right__item--title} (có attribute {@code title} full text, ưu tiên hơn
     * .text() phòng trường hợp bị truncate như IAV), ngày trong
     * {@code .post-list-right__item--date} dạng "dd/MM/yyyy".
     */
    public List<ListingItem> parseMapLife(byte[] body, String baseUrl) throws ParseFailedException {
        try {
            Document doc = Jsoup.parse(new String(body, StandardCharsets.UTF_8), baseUrl);
            Elements items = doc.select("div.post-list-right__item");
            List<ListingItem> result = new ArrayList<>();
            for (Element item : items) {
                Element titleEl = item.selectFirst(".post-list-right__item--title");
                if (titleEl == null) continue;
                Element a = titleEl.selectFirst("a");
                if (a == null) continue;
                String titleAttr = titleEl.attr("title").strip();
                String title = !titleAttr.isBlank() ? titleAttr : a.text().strip();
                String link = a.absUrl("href");
                if (title.isBlank() || link.isBlank()) continue;
                Element dateEl = item.selectFirst(".post-list-right__item--date");
                Instant publishedAt = null;
                if (dateEl != null) {
                    try {
                        publishedAt = java.time.LocalDate.parse(dateEl.text().strip(), AIA_FMT)
                                .atStartOfDay(VN_ZONE).toInstant();
                    } catch (DateTimeParseException e) {
                        log.warn("MAP_LIFE: không parse được ngày '{}' — publishedAt để null", dateEl.text());
                    }
                }
                result.add(new ListingItem(title, link, publishedAt));
            }
            if (result.isEmpty()) {
                throw new ParseFailedException("MAP_LIFE: không tìm thấy div.post-list-right__item nào — cấu trúc trang có thể đã đổi");
            }
            return result;
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("Jsoup lỗi khi parse MAP_LIFE: " + e.getMessage());
        }
    }

    /**
     * Fubon Life Việt Nam (fubonlife.com.vn) — trang "Tin tức", KHÔNG có RSS (Group B).
     * Cấu trúc xác nhận qua fetch trực tiếp 05/07/2026: mỗi tin là {@code <div class="news">},
     * title trong {@code h3 a} (attribute {@code title} ưu tiên hơn .text()), ngày trong
     * {@code div.time} nhưng LẪN với icon text (vd. {@code <i class="fa fa-clock-o"></i> 14/05/2026})
     * — trích ngày bằng regex "dd/MM/yyyy" trên text thay vì strip cố định, an toàn hơn nếu icon
     * markup đổi.
     */
    public List<ListingItem> parseFubonVn(byte[] body, String baseUrl) throws ParseFailedException {
        try {
            Document doc = Jsoup.parse(new String(body, StandardCharsets.UTF_8), baseUrl);
            Elements items = doc.select("div.news");
            List<ListingItem> result = new ArrayList<>();
            for (Element item : items) {
                Element h3 = item.selectFirst("h3");
                if (h3 == null) continue;
                Element a = h3.selectFirst("a");
                if (a == null) continue;
                String titleAttr = a.attr("title").strip();
                String title = !titleAttr.isBlank() ? titleAttr : a.text().strip();
                String link = a.absUrl("href");
                if (title.isBlank() || link.isBlank()) continue;
                Element dateEl = item.selectFirst("div.time");
                Instant publishedAt = null;
                if (dateEl != null) {
                    var m = DDMMYYYY.matcher(dateEl.text());
                    if (m.find()) {
                        try {
                            publishedAt = java.time.LocalDate.parse(m.group(1), AIA_FMT)
                                    .atStartOfDay(VN_ZONE).toInstant();
                        } catch (DateTimeParseException e) {
                            log.warn("FUBON_VN: không parse được ngày '{}' — publishedAt để null", m.group(1));
                        }
                    }
                }
                result.add(new ListingItem(title, link, publishedAt));
            }
            if (result.isEmpty()) {
                throw new ParseFailedException("FUBON_VN: không tìm thấy div.news nào — cấu trúc trang có thể đã đổi");
            }
            return result;
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("Jsoup lỗi khi parse FUBON_VN: " + e.getMessage());
        }
    }

    /**
     * Chubb Life Việt Nam (chubb.com/vn-en) — trang "Press Release", server-rendered.
     * Cấu trúc xác nhận qua fetch trực tiếp 2026-07-14: mỗi tin là
     * {@code <li class="news-list"><span class="news-time">MM/dd/yyyy</span>
     * <div class="news-content"><p><a href="...">Title</a></p></div></li>}.
     * Bản tiếng Anh (vn-en) → ngày kiểu Mỹ MM/dd/yyyy (xác nhận qua item "09/22/2023" —
     * 22 không thể là tháng). Link trỏ ra NGOÀI host (chubb.mediaroom.com) — ingestListing
     * tự động rơi về title-only (vẫn có ngày thật để lọc), không mở rộng whitelist.
     */
    public List<ListingItem> parseChubbVn(byte[] body, String baseUrl) throws ParseFailedException {
        try {
            Document doc = Jsoup.parse(new String(body, StandardCharsets.UTF_8), baseUrl);
            Elements items = doc.select("li.news-list");
            List<ListingItem> result = new ArrayList<>();
            for (Element item : items) {
                Element a = item.selectFirst(".news-content a");
                if (a == null) continue;
                String title = a.text().strip();
                String link = a.absUrl("href");
                if (title.isBlank() || link.isBlank()) continue;
                Element dateEl = item.selectFirst(".news-time");
                Instant publishedAt = null;
                if (dateEl != null) {
                    try {
                        publishedAt = java.time.LocalDate.parse(dateEl.text().strip(), CHUBB_FMT)
                                .atStartOfDay(VN_ZONE).toInstant();
                    } catch (DateTimeParseException e) {
                        log.warn("CHUBB_VN: không parse được ngày '{}' — publishedAt để null", dateEl.text());
                    }
                }
                result.add(new ListingItem(title, link, publishedAt));
            }
            if (result.isEmpty()) {
                throw new ParseFailedException("CHUBB_VN: không tìm thấy li.news-list nào — cấu trúc trang có thể đã đổi");
            }
            return result;
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("Jsoup lỗi khi parse CHUBB_VN: " + e.getMessage());
        }
    }

    /**
     * Thời báo Ngân hàng (thoibaonganhang.vn) — trang chủ, server-rendered, KHÔNG có
     * chuyên mục bảo hiểm riêng nên lấy trang chủ, nhưng lọc tiêu đề bảo hiểm NGAY TẠI
     * acquisition để tin ngân hàng chung không bao giờ làm bẩn corpus.
     * Cấu trúc xác nhận qua fetch trực tiếp 2026-07-14: mỗi tin là
     * {@code <div id="article-NNN" class="article"><h3 class="article-title">
     * <a class="article-link" href="...">Title</a></h3>...
     * <span class="format_date">dd/MM/yyyy</span>...</div>}. Một số item (banner/không tin)
     * thiếu format_date → publishedAt null, giữ nguyên chính sách nullable-date.
     */
    public List<ListingItem> parseTbnh(byte[] body, String baseUrl) throws ParseFailedException {
        try {
            Document doc = Jsoup.parse(new String(body, StandardCharsets.UTF_8), baseUrl);
            Elements items = doc.select("div.article[id^=article-]");
            List<ListingItem> result = new ArrayList<>();
            for (Element item : items) {
                Element a = item.selectFirst("h3.article-title a.article-link");
                if (a == null) continue;
                String title = a.text().strip();
                String link = a.absUrl("href");
                if (title.isBlank() || link.isBlank()) continue;
                if (!isVietnamLifeInsuranceRelevant(title)) continue;
                Element dateEl = item.selectFirst("span.format_date");
                Instant publishedAt = null;
                if (dateEl != null) {
                    try {
                        publishedAt = java.time.LocalDate.parse(dateEl.text().strip(), AIA_FMT)
                                .atStartOfDay(VN_ZONE).toInstant();
                    } catch (DateTimeParseException e) {
                        log.warn("TBNH: không parse được ngày '{}' — publishedAt để null", dateEl.text());
                    }
                }
                result.add(new ListingItem(title, link, publishedAt));
            }
            // A valid homepage can have no insurance story today. That is a truthful zero-yield
            // cycle, not a parser failure. Structural breakage is detected from the raw cards.
            if (items.isEmpty()) throw new ParseFailedException(
                    "TBNH: không tìm thấy div.article[id^=article-] nào — cấu trúc trang có thể đã đổi");
            return result;
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("Jsoup lỗi khi parse TBNH: " + e.getMessage());
        }
    }

    /**
     * HNX media-centre feed, reduced at acquisition time to the monthly Government-bond
     * market review.  Those releases carry the auction yield curve and primary/secondary
     * market volumes needed by an insurer's macro/asset-allocation briefing; issuer
     * notices and exchange-event posts on the same page are intentionally excluded.
     */
    public List<ListingItem> parseHnxGovernmentBondMonthly(byte[] body, String baseUrl)
            throws ParseFailedException {
        try {
            Document doc = Jsoup.parse(new String(body, StandardCharsets.UTF_8), baseUrl);
            Elements cards = doc.select("div.Box-Sukien");
            Map<String, ListingItem> unique = new LinkedHashMap<>();
            for (Element card : cards) {
                Element a = card.selectFirst("a.Box-News-Title[href]");
                if (a == null) continue;
                String title = a.text().strip();
                String normalized = title.toLowerCase(java.util.Locale.ROOT);
                if (!normalized.contains("thị trường trái phiếu chính phủ tháng")) continue;
                String link = a.absUrl("href");
                if (link.isBlank()) continue;
                Element date = card.selectFirst("div.Box-Times p");
                Instant publishedAt = date == null ? null : dateFromText(date.text());
                unique.putIfAbsent(link, new ListingItem(title, link, publishedAt));
            }
            if (cards.isEmpty()) throw new ParseFailedException(
                    "HNX_GOVERNMENT_BONDS: không tìm thấy div.Box-Sukien — cấu trúc media center đã đổi");
            return List.copyOf(unique.values());
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("HNX_GOVERNMENT_BONDS: lỗi parse HTML: " + e.getMessage());
        }
    }

    /**
     * State Bank of Vietnam market-operation releases. These are the authoritative
     * current inputs for credit conditions, deposit/lending rates, FX and interbank
     * liquidity—the macro/asset-allocation spine of a life-insurance briefing.
     */
    public List<ListingItem> parseSbvMarketOperations(byte[] body, String baseUrl)
            throws ParseFailedException {
        try {
            Document doc = Jsoup.parse(new String(body, StandardCharsets.UTF_8), baseUrl);
            Elements links = doc.select(".policy-title a.policy-title-link[href]");
            Map<String, ListingItem> unique = new LinkedHashMap<>();
            for (Element a : links) {
                String title = a.ownText().strip();
                if (title.isBlank()) title = a.text().strip();
                String folded = Normalizer.normalize(title, Normalizer.Form.NFD)
                        .replaceAll("\\p{M}+", "").replace('đ', 'd').replace('Đ', 'D')
                        .toLowerCase(Locale.ROOT);
                boolean relevant = folded.contains("dien bien lai suat")
                        || folded.contains("thi truong ngoai te")
                        || folded.contains("thi truong lien ngan hang")
                        || folded.contains("tang truong tin dung")
                        || folded.contains("dieu hanh tin dung");
                if (!relevant) continue;
                String link = a.absUrl("href");
                Element date = a.selectFirst(".policy-date");
                if (!link.isBlank()) unique.putIfAbsent(link,
                        new ListingItem(title, link, date == null ? null : dateFromText(date.text())));
            }
            if (links.isEmpty()) throw new ParseFailedException(
                    "SBV_MARKET_OPERATIONS: policy listing structure changed");
            return List.copyOf(unique.values());
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("SBV_MARKET_OPERATIONS parse failed: " + e.getMessage());
        }
    }

    /** The Investor's dedicated insurance search archive, constrained to Vietnam evidence. */
    public List<ListingItem> parseTheInvestorInsurance(byte[] body, String baseUrl)
            throws ParseFailedException {
        try {
            Document doc = Jsoup.parse(new String(body, StandardCharsets.UTF_8), baseUrl);
            Map<String, ListingItem> unique = new LinkedHashMap<>();
            Elements candidates = doc.select("a[href~=-d[0-9]+\\.html(?:[?#].*)?$]");
            for (Element a : candidates) {
                String title = nonBlank(a.attr("title"), a.text());
                String link = a.absUrl("href");
                if (title.isBlank() || link.isBlank() || !isVietnamLifeInsuranceRelevant(title)) continue;
                Element container = a.closest("article, .article, .item, .news-item, li");
                Instant date = dateFromText((container == null ? "" : container.text()) + " " + link);
                unique.putIfAbsent(link, new ListingItem(title, link, date));
            }
            if (candidates.isEmpty()) throw new ParseFailedException(
                    "THEINVESTOR_INSURANCE: article URL structure changed");
            return List.copyOf(unique.values());
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("THEINVESTOR_INSURANCE: lỗi parse HTML: " + e.getMessage());
        }
    }

    /** Diễn đàn Doanh nghiệp financial-services archive; keep only Vietnam life-insurance rows. */
    public List<ListingItem> parseDddFinancialServices(byte[] body, String baseUrl)
            throws ParseFailedException {
        try {
            Document doc = Jsoup.parse(new String(body, StandardCharsets.UTF_8), baseUrl);
            Map<String, ListingItem> unique = new LinkedHashMap<>();
            Elements candidates = doc.select("h2 a[href$=.html], h3 a[href$=.html], h4 a[href$=.html]");
            for (Element a : candidates) {
                String title = nonBlank(a.attr("title"), a.text());
                String link = a.absUrl("href");
                if (title.isBlank() || link.isBlank() || !isVietnamLifeInsuranceRelevant(title)) continue;
                Element container = a.closest("article, .article, .item, .news-item, li");
                Instant date = dateFromText((container == null ? "" : container.text()) + " " + link);
                unique.putIfAbsent(link, new ListingItem(title, link, date));
            }
            if (candidates.isEmpty()) throw new ParseFailedException(
                    "DDD_FINANCIAL_SERVICES: article cards changed");
            return List.copyOf(unique.values());
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("DDD_FINANCIAL_SERVICES: lỗi parse HTML: " + e.getMessage());
        }
    }

    /** AIA Group results are parent-company evidence, kept separate from AIA Vietnam. */
    public List<ListingItem> parseAiaGroupPress(byte[] body, String baseUrl)
            throws ParseFailedException {
        try {
            Document doc = Jsoup.parse(new String(body, StandardCharsets.UTF_8), baseUrl);
            Map<String, ListingItem> unique = new LinkedHashMap<>();
            Elements cards = doc.select(".cmp-promotioncard[data-usage=press-release-nav]");
            for (Element card : cards) {
                Element a = card.selectFirst("a.cmp-promotioncard__link[href*=/press-releases/2026/]");
                Element titleEl = card.selectFirst(".cmp-promotioncard__title");
                if (a == null || titleEl == null) continue;
                String title = titleEl.text().strip();
                String link = a.absUrl("href");
                Instant publishedAt = null;
                Element dateEl = card.selectFirst(".cmp-promotioncard__date");
                if (dateEl != null) {
                    try {
                        publishedAt = LocalDate.parse(dateEl.text().strip(), AIA_HK_FMT)
                                .atStartOfDay(VN_ZONE).toInstant();
                    } catch (DateTimeParseException ignored) {}
                }
                if (!title.isBlank() && !link.isBlank()) {
                    unique.putIfAbsent(link, new ListingItem(title, link, publishedAt));
                }
            }
            if (cards.isEmpty()) throw new ParseFailedException("AIA_GROUP_RESULTS: press cards changed");
            return List.copyOf(unique.values());
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("AIA_GROUP_RESULTS: lỗi parse HTML: " + e.getMessage());
        }
    }

    /**
     * MB Ageas Life (mblife.vn) — trang "Góc báo chí" (/goc-bao-chi), Next.js + Apollo
     * GraphQL. KHÔNG phải trang render bằng client-side fetch như BIDV — dữ liệu bài viết
     * đã NẰM SẴN trong &lt;script id="__NEXT_DATA__"&gt; của chính trang này (props.pageProps.
     * initialApolloState), dạng cache Apollo phẳng: object "Articles:&lt;id&gt;" giữ postedDate,
     * object "ArticleTranslations:&lt;id&gt;" giữ title/urlSlug/metaDescription + articleId trỏ
     * ngược về Articles. Link bài = "/goc-bao-chi/{urlSlug}" (xác nhận qua href thật trên trang).
     * Fix 2026-07-14 (Hanh: ưu tiên VN competitor).
     */
    public List<ListingItem> parseMbAgeasPress(byte[] body, String baseUrl) throws ParseFailedException {
        try {
            JsonNode state = readNextDataApolloState(body);
            java.util.Map<String, Instant> postedDateByArticleId = new java.util.HashMap<>();
            var fields = state.fields();
            while (fields.hasNext()) {
                var e = fields.next();
                JsonNode v = e.getValue();
                if (v.path("__typename").asText("").equals("Articles")) {
                    Instant d = parseFlexibleInstant(v.path("postedDate").asText(""));
                    if (d != null) postedDateByArticleId.put(v.path("id").asText(""), d);
                }
            }
            URI base = URI.create(baseUrl);
            String origin = base.getScheme() + "://" + base.getAuthority();
            List<ListingItem> items = new ArrayList<>();
            fields = state.fields();
            while (fields.hasNext()) {
                var e = fields.next();
                JsonNode v = e.getValue();
                if (!v.path("__typename").asText("").equals("ArticleTranslations")) continue;
                String title = v.path("title").asText("").strip();
                String slug = v.path("urlSlug").asText("").strip();
                if (title.isBlank() || slug.isBlank()) continue;
                Instant publishedAt = postedDateByArticleId.get(v.path("articleId").asText(""));
                items.add(new ListingItem(title, origin + "/goc-bao-chi/" + slug, publishedAt));
            }
            if (items.isEmpty()) {
                throw new ParseFailedException("MB_AGEAS: không tìm thấy ArticleTranslations nào trong __NEXT_DATA__ — cấu trúc trang có thể đã đổi");
            }
            return items;
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("MB_AGEAS: lỗi parse __NEXT_DATA__: " + e.getMessage());
        }
    }

    /**
     * FWD Việt Nam (fwd.com.vn) — trang /vi/blog/, Next.js + Contentstack CMS. Route ngoài
     * /vi/blog/ đều trả CÙNG MỘT app-shell HTTP 200 (routing hoàn toàn client-side — kể cả
     * URL không tồn tại), nên phải fetch ĐÚNG /vi/blog/. Toàn bộ ~331 bài (cho lọc phía
     * client) nằm sẵn trong &lt;script id="__NEXT_DATA__"&gt;, RẢI RÁC lồng nhau trong cây
     * layout (không phải 1 mảng phẳng) — quét đệ quy tìm mọi object có
     * "_content_type_uid":"article" kèm "post_date". Field tên "title" của các object này
     * THỰC RA là ĐƯỜNG DẪN bài ("/blog/.../slug/", không phải tiêu đề — đặc thù Contentstack
     * content-type "article") — tiêu đề thật nằm ở "display_title". Trang nặng (~7-8MB, vượt
     * cap 5MB mặc định của SafeFetcher) — IngestionJob gọi fetch() với maxBytesOverride cho
     * riêng nguồn này (xác nhận thủ công đây là nội dung thật, không phải payload tấn công).
     * Fix 2026-07-14 (Hanh: ưu tiên VN competitor).
     */
    public List<ListingItem> parseFwdVn(byte[] body, String baseUrl) throws ParseFailedException {
        try {
            JsonNode root = readNextData(body);
            List<JsonNode> found = new ArrayList<>();
            collectArticleNodes(root, found, 0);
            URI base = URI.create(baseUrl);
            String origin = base.getScheme() + "://" + base.getAuthority();
            java.util.Set<String> seenUrls = new java.util.HashSet<>();
            List<ListingItem> items = new ArrayList<>();
            for (JsonNode a : found) {
                String urlPath = a.path("title").asText("").strip(); // tên field gây nhầm — xem javadoc
                String displayTitle = a.path("display_title").asText("").strip();
                String postDate = a.path("post_date").asText("").strip();
                if (urlPath.isBlank() || displayTitle.isBlank() || !seenUrls.add(urlPath)) continue;
                // post_date đa số dạng "yyyy-MM-dd" thuần, nhưng vài bài (created_at kiểu cũ?)
                // lại là ISO datetime đầy đủ "...T...Z" — thử cả hai, không bỏ bài chỉ vì khác format.
                Instant publishedAt = null;
                if (!postDate.isBlank()) {
                    try {
                        publishedAt = LocalDate.parse(postDate).atStartOfDay(VN_ZONE).toInstant();
                    } catch (DateTimeParseException e) {
                        publishedAt = parseFlexibleInstant(postDate);
                        if (publishedAt == null) log.warn("FWD_VN: không parse được post_date '{}'", postDate);
                    }
                }
                items.add(new ListingItem(displayTitle, origin + urlPath, publishedAt,
                        embeddedFwdArticleText(a)));
            }
            if (items.isEmpty()) {
                throw new ParseFailedException("FWD_VN: không tìm thấy article node nào trong __NEXT_DATA__ — cấu trúc trang có thể đã đổi");
            }
            return items;
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("FWD_VN: lỗi parse __NEXT_DATA__: " + e.getMessage());
        }
    }

    /** FWD's Contentstack payload already contains each article's rich-text blocks. */
    private String embeddedFwdArticleText(JsonNode article) {
        List<String> parts = new ArrayList<>();
        String subtitle = article.path("subtitle").asText("").strip();
        String shortDescription = article.path("short_description").asText("").strip();
        if (!subtitle.isBlank()) parts.add(Jsoup.parse(subtitle).text());
        if (!shortDescription.isBlank()) parts.add(Jsoup.parse(shortDescription).text());
        collectFwdRichText(article.path("article_content"), parts, 0);
        return String.join("\n\n", parts).strip();
    }

    private void collectFwdRichText(JsonNode node, List<String> parts, int depth) {
        if (node == null || node.isMissingNode() || depth > 30) return;
        if (node.isObject()) {
            JsonNode rich = node.get("rich_text_content");
            if (rich != null && rich.isObject()) {
                String html = rich.path("content").asText("");
                String text = html.isBlank() ? "" : Jsoup.parse(html).text().strip();
                if (!text.isBlank()) parts.add(text);
            }
            var fields = node.fields();
            while (fields.hasNext()) collectFwdRichText(fields.next().getValue(), parts, depth + 1);
        } else if (node.isArray()) {
            for (JsonNode child : node) collectFwdRichText(child, parts, depth + 1);
        }
    }

    /** Quét đệ quy cây JSON tìm object {"_content_type_uid":"article", "post_date":...}. */
    private void collectArticleNodes(JsonNode node, List<JsonNode> out, int depth) {
        if (node == null || depth > 80) return; // chặn đệ quy quá sâu (an toàn, không phải giới hạn thật)
        if (node.isObject()) {
            if ("article".equals(node.path("_content_type_uid").asText(""))
                    && node.has("post_date")) {
                out.add(node);
            }
            var it = node.fields();
            while (it.hasNext()) collectArticleNodes(it.next().getValue(), out, depth + 1);
        } else if (node.isArray()) {
            for (JsonNode child : node) collectArticleNodes(child, out, depth + 1);
        }
    }

    /** Đọc &lt;script id="__NEXT_DATA__"&gt; của trang Next.js thành JsonNode (nội dung script raw). */
    private JsonNode readNextData(byte[] body) throws Exception {
        Document doc = Jsoup.parse(new String(body, StandardCharsets.UTF_8));
        Element script = doc.selectFirst("script#__NEXT_DATA__");
        if (script == null) throw new IllegalStateException("không tìm thấy script#__NEXT_DATA__");
        return JSON.readTree(script.data());
    }

    /** Như readNextData nhưng đi thẳng vào props.pageProps.initialApolloState (pattern Apollo). */
    private JsonNode readNextDataApolloState(byte[] body) throws Exception {
        JsonNode state = readNextData(body).path("props").path("pageProps").path("initialApolloState");
        if (state.isMissingNode() || !state.isObject()) {
            throw new IllegalStateException("không tìm thấy props.pageProps.initialApolloState");
        }
        return state;
    }

    /**
     * BIDV MetLife (bidvmetlife.com.vn) — trang "Tin tức" render bằng JS (nền tảng AEM),
     * HTML tĩnh KHÔNG có bài. JS gọi endpoint JSON nội bộ
     * ({@code /bin/MLApp/.../fetchArticleColumnGridArticleListing}) trả sẵn danh sách bài —
     * ta gọi thẳng endpoint đó (fetchUrl của source = URL JSON này). Mỗi phần tử có
     * {@code headlineTitle}, {@code publishedDate} ("MAY 12, 2026"), {@code path}
     * ("/about-us/news/2026/..."). Link tuyệt đối = resolve path trên host của baseUrl.
     * Fix 2026-07-14 (feedback Hanh: mắt thấy ngày mà crawler không thấy — vì nó ở JSON, không ở HTML).
     */
    public List<ListingItem> parseBidvMetlife(byte[] body, String baseUrl) throws ParseFailedException {
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode arts = root.get("articles");
            if (arts == null || !arts.isArray() || arts.isEmpty()) {
                throw new ParseFailedException("BIDV_METLIFE: JSON không có mảng 'articles' — endpoint có thể đã đổi");
            }
            URI base = URI.create(baseUrl);
            List<ListingItem> items = new ArrayList<>();
            for (JsonNode a : arts) {
                String title = a.path("headlineTitle").asText("").strip();
                String path = a.path("path").asText("").strip();
                if (title.isBlank() || path.isBlank()) continue;
                String link = base.resolve(path).toString();
                Instant publishedAt = null;
                String pd = a.path("publishedDate").asText("").strip();
                if (!pd.isBlank()) {
                    try {
                        publishedAt = LocalDate.parse(pd, BIDV_FMT).atStartOfDay(VN_ZONE).toInstant();
                    } catch (DateTimeParseException e) {
                        log.warn("BIDV_METLIFE: không parse được ngày '{}' — publishedAt để null", pd);
                    }
                }
                items.add(new ListingItem(title, link, publishedAt));
            }
            if (items.isEmpty()) {
                throw new ParseFailedException("BIDV_METLIFE: 'articles' rỗng sau khi lọc — không có bài hợp lệ");
            }
            return items;
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("BIDV_METLIFE: lỗi parse JSON: " + e.getMessage());
        }
    }

    /**
     * Dai-ichi Life Việt Nam — trang /tin-tuc server-rendered chứa các card có ngày thật.
     * Mỗi tin là {@code <div class="... item-news ...">} hoặc {@code item-news-horizontal}
     * (2 layout khác nhau cho cùng loại thẻ) chứa {@code <h3 class="card-title-2"><a href="...">
     * Title</a></h3>} và {@code <p class="publish_at">...&lt;span&gt;dd/MM/yyyy&lt;/span&gt;...}
     * (bản horizontal không có &lt;span&gt; quanh ngày — lấy text() rồi regex ngày, không phụ
     * thuộc cấu trúc con). Dùng archive này thay cho /api/news/home vốn chỉ là widget nhỏ,
     * thiên về health content và bỏ sót thông báo sản phẩm/hoạt động công ty.
     */
    public List<ListingItem> parseDaiichiVn(byte[] body, String baseUrl) throws ParseFailedException {
        try {
            Document doc = Jsoup.parse(new String(body, StandardCharsets.UTF_8), baseUrl);
            Elements cards = doc.select(".item-news, .item-news-horizontal");
            List<ListingItem> items = new ArrayList<>();
            for (Element card : cards) {
                Element a = card.selectFirst("h3.card-title-2 a");
                if (a == null) continue;
                String title = a.text().strip();
                String link = a.absUrl("href").strip();
                if (title.isBlank() || link.isBlank()) continue;
                Instant publishedAt = null;
                Element dateEl = card.selectFirst(".publish_at");
                if (dateEl != null) {
                    var m = DDMMYYYY.matcher(dateEl.text());
                    if (m.find()) {
                        try {
                            publishedAt = java.time.LocalDate.parse(m.group(1), AIA_FMT)
                                    .atStartOfDay(VN_ZONE).toInstant();
                        } catch (DateTimeParseException e) {
                            log.warn("DAIICHI_VN: không parse được ngày '{}' — publishedAt để null", m.group(1));
                        }
                    }
                }
                items.add(new ListingItem(title, link, publishedAt));
            }
            if (items.isEmpty()) {
                throw new ParseFailedException("DAIICHI_VN: không tìm thấy item-news nào — cấu trúc trang có thể đã đổi");
            }
            return items;
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("DAIICHI_VN: lỗi parse HTML: " + e.getMessage());
        }
    }

    /**
     * Generali Việt Nam (generali.vn) — trang /thong-cao-bao-chi, Next.js App Router (RSC
     * streaming — KHÁC Pages Router của MB Ageas/BIDV, không có __NEXT_DATA__ nên không đọc
     * được kiểu cũ). Danh sách bài KHÔNG nằm trong HTML/RSC payload ban đầu — nạp bằng client
     * fetch riêng: GET /api/cms/api/thong-cao-bao-chis?fields[...]&amp;pagination[...]&amp;
     * sort[0]=published_date:desc — Strapi CMS chuẩn, response {data:[{attributes:{title,slug,
     * published_date,summary}}]}. published_date đã là "yyyy-MM-dd" sạch. Link bài =
     * "/thong-cao-bao-chi/{slug}" (xác nhận live).
     * Fix 2026-07-14 (Hanh: cụm "tìm URL tin thật" — tin không nằm ở trang chủ).
     */
    public List<ListingItem> parseGeneraliVn(byte[] body, String baseUrl) throws ParseFailedException {
        try {
            JsonNode data = JSON.readTree(body).get("data");
            if (data == null || !data.isArray() || data.isEmpty()) {
                throw new ParseFailedException("GENERALI_VN: JSON không có mảng 'data' — endpoint có thể đã đổi");
            }
            URI base = URI.create(baseUrl);
            String origin = base.getScheme() + "://" + base.getAuthority();
            List<ListingItem> items = new ArrayList<>();
            for (JsonNode n : data) {
                JsonNode attr = n.path("attributes");
                String title = attr.path("title").asText("").strip();
                String slug = attr.path("slug").asText("").strip();
                if (title.isBlank() || slug.isBlank()) continue;
                Instant publishedAt = null;
                String pd = attr.path("published_date").asText("").strip();
                if (!pd.isBlank()) {
                    try {
                        publishedAt = java.time.LocalDate.parse(pd).atStartOfDay(VN_ZONE).toInstant();
                    } catch (DateTimeParseException e) {
                        log.warn("GENERALI_VN: không parse được published_date '{}'", pd);
                    }
                }
                items.add(new ListingItem(title, origin + "/thong-cao-bao-chi/" + slug, publishedAt));
            }
            if (items.isEmpty()) {
                throw new ParseFailedException("GENERALI_VN: 'data' rỗng sau khi lọc — không có bài hợp lệ");
            }
            return items;
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("GENERALI_VN: lỗi parse JSON: " + e.getMessage());
        }
    }

    /**
     * Hanwha Life Việt Nam (hanwhalife.com.vn) — trang /vi/news, HTML server-rendered thường
     * (KHÔNG cần API riêng). Cấu trúc xác nhận qua fetch trực tiếp 2026-07-14: mỗi tin là
     * {@code <div class="thumb col-md-4 ..."><div class="item">...<p class="time">dd/MM/yyyy</p>
     * <h3 class="title ..."><a href="...">Title</a></h3>...}. Dùng selector div.thumb (khớp
     * đúng số lượng p.time trên trang, tránh vơ nhầm h3.title lặp ở khối khác).
     * Fix 2026-07-14 (Hanh: cụm "tìm URL tin thật" — trang chủ không có tin).
     */
    public List<ListingItem> parseHanwhaVn(byte[] body, String baseUrl) throws ParseFailedException {
        try {
            Document doc = Jsoup.parse(new String(body, StandardCharsets.UTF_8), baseUrl);
            Elements cards = doc.select("div.thumb");
            List<ListingItem> items = new ArrayList<>();
            for (Element card : cards) {
                Element a = card.selectFirst("h3.title a");
                if (a == null) continue;
                String title = a.text().strip();
                String link = a.absUrl("href");
                if (title.isBlank() || link.isBlank()) continue;
                Instant publishedAt = null;
                Element dateEl = card.selectFirst("p.time");
                if (dateEl != null) {
                    try {
                        publishedAt = java.time.LocalDate.parse(dateEl.text().strip(), AIA_FMT)
                                .atStartOfDay(VN_ZONE).toInstant();
                    } catch (DateTimeParseException e) {
                        log.warn("HANWHA_VN: không parse được ngày '{}' — publishedAt để null", dateEl.text());
                    }
                }
                items.add(new ListingItem(title, link, publishedAt));
            }
            if (items.isEmpty()) {
                throw new ParseFailedException("HANWHA_VN: không tìm thấy div.thumb nào — cấu trúc trang có thể đã đổi");
            }
            return items;
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("Jsoup lỗi khi parse HANWHA_VN: " + e.getMessage());
        }
    }

    /**
     * Shinhan Life Việt Nam (shinhanlifevn.com.vn) — trang /press-release. HTML tĩnh KHÔNG có
     * bài (widget Angular gọi GET /api/v1/application/getContent/press-release). Response KHÁC
     * mọi nguồn khác: KHÔNG phải danh sách bài — là MỘT "post" DUY NHẤT
     * (data.listSitePost[0].contentVn) mà nội dung là HTML tự soạn kiểu WordPress, trong đó
     * MỖI "tin" là một khối {@code <div class="... dropshadowboxes-container ...">} chứa tiêu đề
     * trong {@code <strong><a>...</a></strong>}, ngày dạng dd/MM/yyyy ở đâu đó trong text khối
     * (không cố định class — regex trên text() cả khối), và link THẬT nằm ở nút "Xem thêm"
     * ({@code <a class="... btn-shinhan ...">}) — LƯU Ý: href trên chính thẻ tiêu đề là slug
     * cũ/sai (xác nhận: cùng href lặp lại cho nhiều tiêu đề khác nhau — lỗi copy-paste CMS phía
     * họ), không dùng. Đã validate cấu trúc trên dữ liệu thật (67 khối, đủ 3 trường).
     * Fix 2026-07-14 (Hanh: cụm "tìm URL tin thật").
     */
    public List<ListingItem> parseShinhanVn(byte[] body, String baseUrl) throws ParseFailedException {
        try {
            JsonNode posts = JSON.readTree(body).path("data").path("listSitePost");
            if (!posts.isArray() || posts.isEmpty()) {
                throw new ParseFailedException("SHINHAN_VN: JSON không có data.listSitePost — endpoint có thể đã đổi");
            }
            String contentVn = posts.get(0).path("contentVn").asText("");
            if (contentVn.isBlank()) {
                throw new ParseFailedException("SHINHAN_VN: listSitePost[0].contentVn rỗng");
            }
            Document doc = Jsoup.parse(contentVn, baseUrl);
            URI base = URI.create(baseUrl);
            String origin = base.getScheme() + "://" + base.getAuthority();
            Elements cards = doc.select(".dropshadowboxes-container");
            List<ListingItem> items = new ArrayList<>();
            for (Element card : cards) {
                Element titleA = card.selectFirst("strong a");
                Element linkA = card.selectFirst("a[class*=btn-shinhan]");
                if (titleA == null || linkA == null) continue;
                String title = titleA.text().strip();
                String link = normalizeSameSiteLink(linkA.attr("href").strip(), origin, base.getHost());
                if (title.isBlank() || link.isBlank()) continue;
                Instant publishedAt = null;
                var m = DDMMYYYY.matcher(card.text());
                if (m.find()) {
                    try {
                        publishedAt = java.time.LocalDate.parse(m.group(1), AIA_FMT)
                                .atStartOfDay(VN_ZONE).toInstant();
                    } catch (DateTimeParseException e) {
                        log.warn("SHINHAN_VN: không parse được ngày '{}' — publishedAt để null", m.group(1));
                    }
                }
                items.add(new ListingItem(title, link, publishedAt));
            }
            if (items.isEmpty()) {
                throw new ParseFailedException("SHINHAN_VN: không tìm thấy dropshadowboxes-container nào — cấu trúc có thể đã đổi");
            }
            return items;
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("SHINHAN_VN: lỗi parse response: " + e.getMessage());
        }
    }

    /**
     * Shinhan's public article URLs are client-side routes: a plain HTML fetch returns the
     * same application shell for every slug.  The site's own public content API exposes the
     * real Vietnamese body.  Prefer the editorial dateline inside that body over the API's
     * {@code publishedDate}, which can be a CMS creation timestamp several months earlier.
     */
    public ListingItem parseShinhanVnDetail(byte[] body, String officialArticleUrl)
            throws ParseFailedException {
        try {
            JsonNode posts = JSON.readTree(body).path("data").path("listSitePost");
            if (!posts.isArray() || posts.isEmpty()) {
                throw new ParseFailedException("SHINHAN_VN detail: JSON không có data.listSitePost");
            }
            JsonNode post = posts.get(0);
            String title = post.path("titleVn").asText("").strip();
            String html = post.path("contentVn").asText("");
            String text = Jsoup.parse(html).text().strip();
            if (title.isBlank() || text.length() < 300) {
                throw new ParseFailedException("SHINHAN_VN detail: title/body rỗng hoặc quá ngắn");
            }

            Instant publishedAt = vietnameseEditorialDateline(text);
            if (publishedAt == null) {
                publishedAt = parseFlexibleInstant(post.path("publishedDate").asText(""));
            }
            return new ListingItem(title, officialArticleUrl, publishedAt, text);
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("SHINHAN_VN detail parse failed: " + e.getMessage());
        }
    }

    private static Instant vietnameseEditorialDateline(String text) {
        var matcher = java.util.regex.Pattern.compile(
                "(?iu)ngày\\s+(\\d{1,2})\\s+tháng\\s+(\\d{1,2})\\s+năm\\s+(20\\d{2})")
                .matcher(text == null ? "" : text);
        if (!matcher.find()) return null;
        try {
            return LocalDate.of(Integer.parseInt(matcher.group(3)),
                            Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(1)))
                    .atStartOfDay(VN_ZONE).toInstant();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String normalizeSameSiteLink(String raw, String origin, String allowedHost) {
        if (raw == null || raw.isBlank()) return "";
        try {
            URI candidate = URI.create(raw.strip());
            if (!candidate.isAbsolute()) {
                String path = raw.startsWith("/") ? raw : "/" + raw;
                return URI.create(origin).resolve(path).toString();
            }
            String host = candidate.getHost();
            if (host != null && (host.equalsIgnoreCase(allowedHost)
                    || ("www." + host).equalsIgnoreCase(allowedHost))) {
                String path = candidate.getRawPath() == null ? "/" : candidate.getRawPath();
                String query = candidate.getRawQuery() == null ? "" : "?" + candidate.getRawQuery();
                return origin + path + query;
            }
            return candidate.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * Phú Hưng Life (phuhunglife.com) — trang /vn/tin-tuc/?categoryId=2907 ("Thông cáo báo chí"),
     * HTML server-rendered NHƯNG dữ liệu KHÔNG ở trong thẻ HTML thường — nằm trong một
     * &lt;script&gt; dạng gán biến JS thuần (không phải &lt;script type="application/json"&gt;):
     * {@code window.globalData.newsPage.newsList = {"items":[{"title":...,"date":"dd.MM.yyyy",
     * "href":"/vn/tin-tuc/{slug}/"}]};} — trích bằng quét ngoặc cân bằng từ dấu "{" đầu tiên sau
     * "newsList = " (an toàn hơn regex phi-tham-lam vì JSON có thể chứa "};" bên trong chuỗi).
     * Ngày dạng "dd.MM.yyyy" (DẤU CHẤM — khác mọi nguồn khác, đã verify). Trang chỉ trả 3 tin/lần
     * (pageSize cố định phía server, query param không đổi được — đã thử) trên tổng 41 tin.
     * Fix 2026-07-14 (Hanh: cụm "tìm URL tin thật" — trang chủ không có link tin ở HTML tĩnh,
     * nav "Tin Tức - Sự Kiện" là JS dropdown).
     */
    public List<ListingItem> parsePhuHungLife(byte[] body, String baseUrl) throws ParseFailedException {
        try {
            String html = new String(body, StandardCharsets.UTF_8);
            String marker = "newsPage.newsList = ";
            int markerIdx = html.indexOf(marker);
            if (markerIdx < 0) {
                throw new ParseFailedException("PHU_HUNG_LIFE: không tìm thấy 'newsPage.newsList = ' trong HTML — cấu trúc có thể đã đổi");
            }
            String jsonStr = extractBalancedJsonObject(html, markerIdx + marker.length());
            JsonNode itemsNode = JSON.readTree(jsonStr).path("items");
            if (!itemsNode.isArray() || itemsNode.isEmpty()) {
                throw new ParseFailedException("PHU_HUNG_LIFE: newsList.items rỗng hoặc không phải mảng");
            }
            URI base = URI.create(baseUrl);
            String origin = base.getScheme() + "://" + base.getAuthority();
            List<ListingItem> items = new ArrayList<>();
            for (JsonNode it : itemsNode) {
                String title = it.path("title").asText("").strip();
                String href = it.path("href").asText("").strip();
                if (title.isBlank() || href.isBlank()) continue;
                String link = href.startsWith("http") ? href : origin + href;
                Instant publishedAt = null;
                String dateStr = it.path("date").asText("").strip();
                if (!dateStr.isBlank()) {
                    try {
                        publishedAt = java.time.LocalDate.parse(dateStr, PHU_HUNG_FMT)
                                .atStartOfDay(VN_ZONE).toInstant();
                    } catch (DateTimeParseException e) {
                        log.warn("PHU_HUNG_LIFE: không parse được ngày '{}' — publishedAt để null", dateStr);
                    }
                }
                items.add(new ListingItem(title, link, publishedAt));
            }
            if (items.isEmpty()) {
                throw new ParseFailedException("PHU_HUNG_LIFE: không có item hợp lệ sau khi lọc");
            }
            return items;
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("PHU_HUNG_LIFE: lỗi parse response: " + e.getMessage());
        }
    }

    /** Phú Hưng article body is embedded as JSON in a deterministic JS assignment. */
    public ParsedText parsePhuHungDetail(byte[] body) throws ParseFailedException {
        try {
            String html = new String(body, StandardCharsets.UTF_8);
            String marker = "newsDetailPage.detailNews = ";
            int markerIdx = html.indexOf(marker);
            if (markerIdx < 0) throw new ParseFailedException("PHU_HUNG_LIFE: thiếu detailNews payload");
            JsonNode detail = JSON.readTree(extractBalancedJsonObject(html, markerIdx + marker.length()));
            String title = detail.path("title").asText("").strip();
            String text = Jsoup.parse(detail.path("editor").asText("")).text().strip();
            if (text.isBlank()) throw new ParseFailedException("PHU_HUNG_LIFE: editor rỗng");
            return new ParsedText(title, text, "Full text extracted from embedded detail JSON");
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("PHU_HUNG_LIFE: lỗi parse detail payload: " + e.getMessage());
        }
    }

    /**
     * Cathay Life Việt Nam (cathaylife.com.vn) — trang /cathay/news, Vue SPA. Danh sách tin nạp
     * qua GraphQL: POST /cathay/api/graphql, body cố định (query + variables — xem query bên
     * dưới, bắt được bằng cách vá window.fetch rồi bấm tab chuyên mục thật trên trang, KHÔNG đoán
     * schema). ncategory_id="1" = "Hoạt động kinh doanh" (tin công ty/PR — sát nghĩa insurance
     * news nhất trong 4 chuyên mục). Mỗi item có "content" là CHUỖI JSON LỒNG dạng
     * {"vi_VN":{"title":...},"en_US":{"title":...}} (parse 2 lần, giống MOF articleContent).
     * posted_at đã là "yyyy-MM-dd" sạch. Link chi tiết = "/cathay/news-detail?news_id={id}"
     * (route Vue Router "news-detail", bắt được từ OfficialNews-*.js, xác nhận live 200).
     * Fix 2026-07-14 (Hanh: cụm "tìm URL tin thật" — trang chủ trống, /cathay/news JS-render).
     */
    public List<ListingItem> parseCathayVn(byte[] body, String baseUrl) throws ParseFailedException {
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode newsArr = root.path("data").path("news");
            if (!newsArr.isArray() || newsArr.isEmpty()) {
                throw new ParseFailedException("CATHAY_VN: GraphQL response không có data.news — schema có thể đã đổi");
            }
            URI base = URI.create(baseUrl);
            String origin = base.getScheme() + "://" + base.getAuthority();
            List<ListingItem> items = new ArrayList<>();
            for (JsonNode n : newsArr) {
                long newsId = n.path("news_id").asLong(-1);
                if (newsId < 0) continue;
                String contentRaw = n.path("content").asText("");
                String title = "";
                String embeddedText = "";
                if (!contentRaw.isBlank()) {
                    try {
                        JsonNode content = JSON.readTree(contentRaw);
                        JsonNode localized = content.path("vi_VN");
                        if (localized.isMissingNode() || localized.isNull()) localized = content.path("en_US");
                        title = localized.path("title").asText("");
                        embeddedText = Jsoup.parse(localized.path("summary").asText("")).text().strip();
                    } catch (Exception e) {
                        log.warn("CATHAY_VN: content của news_id={} không phải JSON hợp lệ", newsId);
                    }
                }
                if (title.isBlank()) continue;
                Instant publishedAt = null;
                String posted = n.path("posted_at").asText("").strip();
                if (!posted.isBlank()) {
                    try {
                        publishedAt = java.time.LocalDate.parse(posted).atStartOfDay(VN_ZONE).toInstant();
                    } catch (DateTimeParseException e) {
                        log.warn("CATHAY_VN: không parse được posted_at '{}'", posted);
                    }
                }
                items.add(new ListingItem(title.strip(), origin + "/cathay/news-detail?news_id=" + newsId,
                        publishedAt, embeddedText));
            }
            if (items.isEmpty()) {
                throw new ParseFailedException("CATHAY_VN: không có item hợp lệ sau khi lọc");
            }
            return items;
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("CATHAY_VN: lỗi parse GraphQL response: " + e.getMessage());
        }
    }

    /**
     * Prudential Hong Kong (prudential.com.hk) — trang /en/about-us/newsroom/, AEM server-rendered
     * (cùng nền tảng BIDV MetLife, nhưng ở đây HTML tĩnh CÓ đủ dữ liệu, không cần gọi API riêng).
     * Mỗi tin là {@code <article class="article-card" data-date="dd-MM-yyyy">...<h3 class=
     * "article-heading">Title</h3>...<a class="cta-button" href="...">}. Trang là ARCHIVE ĐẦY ĐỦ
     * (115 bài, 2018–2026, không sắp theo thời gian) — phần lớn sẽ bị bộ lọc độ mới loại đúng,
     * chỉ ~26 bài 2025–2026 lọt qua, đúng như thiết kế (không phải bug).
     * Fix 2026-07-14 (Hanh: mở rộng sang khu vực — Hong Kong).
     * Tái dùng nguyên vẹn cho PRULIFE_PH (prulifeuk.com.ph — cùng nền tảng AEM Prudential,
     * cùng cấu trúc data-date/article-heading/cta-button hệt nhau, xác nhận live: 211 bài,
     * ~50 bài 2025–2026) — không cần parser riêng.
     */
    public List<ListingItem> parsePruHk(byte[] body, String baseUrl) throws ParseFailedException {
        try {
            Document doc = Jsoup.parse(new String(body, StandardCharsets.UTF_8), baseUrl);
            Elements cards = doc.select("article.article-card");
            List<ListingItem> items = new ArrayList<>();
            for (Element card : cards) {
                Element h = card.selectFirst("h3.article-heading");
                Element a = card.selectFirst("a.cta-button");
                if (h == null || a == null) continue;
                String title = h.text().strip();
                String link = a.absUrl("href");
                if (title.isBlank() || link.isBlank()) continue;
                Instant publishedAt = null;
                String dateAttr = card.attr("data-date").strip();
                if (!dateAttr.isBlank()) {
                    try {
                        publishedAt = java.time.LocalDate.parse(dateAttr, PRU_FMT).atStartOfDay(VN_ZONE).toInstant();
                    } catch (DateTimeParseException e) {
                        log.warn("PRU_HK: không parse được ngày '{}' — publishedAt để null", dateAttr);
                    }
                }
                items.add(new ListingItem(title, link, publishedAt));
            }
            if (items.isEmpty()) {
                throw new ParseFailedException("PRU_HK: không tìm thấy article.article-card nào — cấu trúc trang có thể đã đổi");
            }
            return items;
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("Jsoup lỗi khi parse PRU_HK: " + e.getMessage());
        }
    }

    /**
     * Insurance Authority Hong Kong / HKIA (ia.org.hk) — trang /en/infocenter/press_releases.html
     * tự nó gần rỗng (4.4KB, không phải SPA — trang HTML cũ dùng jQuery). Nội dung nạp qua
     * POST /en/infocenter/press_releases.php, body RỖNG là đủ (xác nhận thủ công, không cần
     * tham số nào). Response {"press":[{"id","date":"d/M/yyyy" (KHÔNG số 0 đệm),"name","url"
     * (đường dẫn TƯƠNG ĐỐI kiểu "../../en/infocenter/press_releases/20260708.html")}]} — 490 bài,
     * sort mới nhất trước, ĐÃ xác nhận có bài tháng 7/2026. Link giải bằng URI.resolve() trên
     * chính URL trang (không phải URL API .php) vì "../../" tính từ /en/infocenter/.
     * Fix 2026-07-14 (Hanh: mở rộng khu vực — regulator T1 Hong Kong).
     */
    public List<ListingItem> parseHkia(byte[] body, String baseUrl) throws ParseFailedException {
        try {
            JsonNode press = JSON.readTree(body).path("press");
            if (!press.isArray() || press.isEmpty()) {
                throw new ParseFailedException("HKIA: JSON không có mảng 'press' — endpoint có thể đã đổi");
            }
            URI base = URI.create(baseUrl);
            List<ListingItem> items = new ArrayList<>();
            for (JsonNode n : press) {
                String title = n.path("name").asText("").strip();
                String relUrl = n.path("url").asText("").strip();
                if (title.isBlank() || relUrl.isBlank()) continue;
                String link = base.resolve(relUrl).toString();
                Instant publishedAt = null;
                String dateStr = n.path("date").asText("").strip();
                if (!dateStr.isBlank()) {
                    try {
                        publishedAt = java.time.LocalDate.parse(dateStr, HKIA_FMT).atStartOfDay(VN_ZONE).toInstant();
                    } catch (DateTimeParseException e) {
                        log.warn("HKIA: không parse được ngày '{}' — publishedAt để null", dateStr);
                    }
                }
                items.add(new ListingItem(title, link, publishedAt));
            }
            if (items.isEmpty()) {
                throw new ParseFailedException("HKIA: 'press' rỗng sau khi lọc — không có bài hợp lệ");
            }
            return items;
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("HKIA: lỗi parse response: " + e.getMessage());
        }
    }

    /**
     * AIA Group HK (aia.com.hk) — trang /en/about-aia/about-us/media-centre/press-releases,
     * AEM server-rendered (cùng nền tảng AIA_VN — cmp-promotioncard — nhưng ở đây HTML tĩnh CÓ
     * đủ dữ liệu, không cần fallback năm-từ-URL như AIA_VN). Mỗi tin là
     * {@code <div class="cmp-promotioncard cmp-promotioncard__hk-card"><a class=
     * "cmp-promotioncard__link" href="..."><div class="cmp-promotioncard__title...">Title</div>
     * <div class="cmp-promotioncard__date">d MMMM yyyy</div></a></div>}.
     * Fix 2026-07-14 (Hanh: tiếp tục Hong Kong/Korea/Japan).
     */
    public List<ListingItem> parseAiaHk(byte[] body, String baseUrl) throws ParseFailedException {
        try {
            Document doc = Jsoup.parse(new String(body, StandardCharsets.UTF_8), baseUrl);
            Elements cards = doc.select("div.cmp-promotioncard__hk-card");
            List<ListingItem> items = new ArrayList<>();
            for (Element card : cards) {
                Element a = card.selectFirst("a.cmp-promotioncard__link");
                Element titleEl = card.selectFirst(".cmp-promotioncard__title");
                if (a == null || titleEl == null) continue;
                String title = titleEl.text().strip();
                String link = a.absUrl("href");
                if (title.isBlank() || link.isBlank()) continue;
                Instant publishedAt = null;
                Element dateEl = card.selectFirst(".cmp-promotioncard__date");
                if (dateEl != null) {
                    try {
                        publishedAt = java.time.LocalDate.parse(dateEl.text().strip(), AIA_HK_FMT)
                                .atStartOfDay(VN_ZONE).toInstant();
                    } catch (DateTimeParseException e) {
                        log.warn("AIA_HK: không parse được ngày '{}' — publishedAt để null", dateEl.text());
                    }
                }
                items.add(new ListingItem(title, link, publishedAt));
            }
            if (items.isEmpty()) {
                throw new ParseFailedException("AIA_HK: không tìm thấy cmp-promotioncard__hk-card nào — cấu trúc trang có thể đã đổi");
            }
            return items;
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("Jsoup lỗi khi parse AIA_HK: " + e.getMessage());
        }
    }

    /**
     * Financial Services Commission Korea / FSC_KR (fsc.go.kr) — trang /eng/pr010101 (Press
     * Releases) tự nó gần rỗng (board-list container không có &lt;tr&gt; nào ở HTML tĩnh — nạp
     * qua JS). Nội dung nạp qua GET /humanframe-cms/getMiniBBS.json?bbsNo=2&amp;bbsListId=1
     * (bbsNo/bbsListId dò được bằng cách quét các giá trị nhỏ trên trang thật, KHÔNG có trong
     * HTML tĩnh — site dùng chung 1 "mini-BBS" component cho nhiều mục, mỗi mục 1 cặp id khác
     * nhau). Response {"title":"Press Releases","list":[{"sj":title,"sumry":full text sẵn (không
     * cần fetch chi tiết riêng!),"creatDttm":"yyyy-MM-dd","nttNo":id}]} — chỉ trả 5 tin gần nhất
     * (không có tham số tăng số lượng đã thử). Link chi tiết = "/eng/pr010101/{nttNo}" (bắt được
     * từ href thật trên trang, xác nhận live 200).
     * Fix 2026-07-14 (Hanh: tiếp tục Hong Kong/Korea/Japan — regulator T1 Korea).
     */
    public List<ListingItem> parseFscKr(byte[] body, String baseUrl) throws ParseFailedException {
        try {
            JsonNode list = JSON.readTree(body).path("list");
            if (!list.isArray() || list.isEmpty()) {
                throw new ParseFailedException("FSC_KR: JSON không có mảng 'list' — endpoint có thể đã đổi");
            }
            URI base = URI.create(baseUrl);
            String origin = base.getScheme() + "://" + base.getAuthority();
            List<ListingItem> items = new ArrayList<>();
            for (JsonNode n : list) {
                String title = n.path("sj").asText("").strip();
                long nttNo = n.path("nttNo").asLong(-1);
                if (title.isBlank() || nttNo < 0) continue;
                Instant publishedAt = null;
                String dateStr = n.path("creatDttm").asText("").strip();
                if (!dateStr.isBlank()) {
                    try {
                        publishedAt = java.time.LocalDate.parse(dateStr).atStartOfDay(VN_ZONE).toInstant();
                    } catch (DateTimeParseException e) {
                        log.warn("FSC_KR: không parse được ngày '{}' — publishedAt để null", dateStr);
                    }
                }
                items.add(new ListingItem(title, origin + "/eng/pr010101/" + nttNo, publishedAt));
            }
            if (items.isEmpty()) {
                throw new ParseFailedException("FSC_KR: 'list' rỗng sau khi lọc — không có bài hợp lệ");
            }
            return items;
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("FSC_KR: lỗi parse response: " + e.getMessage());
        }
    }

    /**
     * Financial Supervisory Service Korea / FSS_KR (fss.or.kr) — nguồn KHÁC FSC_KR (regulator
     * riêng, nền tảng CMS khác — bảng &lt;table&gt; egovframework thường gặp, không phải mini-BBS
     * JSON). Trang chủ chỉ có 3 tin (widget preview) nhưng trang danh sách đầy đủ
     * /eng/bbs/B0000211/list.do?menuNo=400010 server-rendered SẴN 10 tin trong bảng:
     * {@code <tr><td>cate</td><td>cate2</td><td class="title"><a href="...">Title</a></td>
     * <td>yyyy-MM-dd</td>...</tr>}. Ngày không có class riêng — lấy bằng regex trên text() cả
     * hàng (ổn định hơn dò đúng cột thứ mấy, tránh vỡ khi số cột đổi).
     * Fix 2026-07-14 (Hanh: tiếp tục Hong Kong/Korea/Japan — regulator T1 Korea, nguồn thứ 2).
     */
    public List<ListingItem> parseFssKr(byte[] body, String baseUrl) throws ParseFailedException {
        try {
            Document doc = Jsoup.parse(new String(body, StandardCharsets.UTF_8), baseUrl);
            Elements rows = doc.select("tr:has(td.title)");
            List<ListingItem> items = new ArrayList<>();
            for (Element row : rows) {
                Element a = row.selectFirst("td.title a");
                if (a == null) continue;
                String title = a.text().strip();
                String link = a.absUrl("href");
                if (title.isBlank() || link.isBlank()) continue;
                Instant publishedAt = null;
                var m = ISO_YMD.matcher(row.text());
                if (m.find()) {
                    try {
                        publishedAt = java.time.LocalDate.parse(m.group(1)).atStartOfDay(VN_ZONE).toInstant();
                    } catch (DateTimeParseException e) {
                        log.warn("FSS_KR: không parse được ngày '{}' — publishedAt để null", m.group(1));
                    }
                }
                items.add(new ListingItem(title, link, publishedAt));
            }
            if (items.isEmpty()) {
                throw new ParseFailedException("FSS_KR: không tìm thấy tr:has(td.title) nào — cấu trúc trang có thể đã đổi");
            }
            return items;
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("Jsoup lỗi khi parse FSS_KR: " + e.getMessage());
        }
    }

    /**
     * Nippon Life (nissay.co.jp) — trang /global/news/, article list JS-rendered từ một file
     * JSON TĨNH (không phải API động, không cần POST/tham số gì): GET /global/news/json/
     * index.json → mảng phẳng {@code [{"date":"yyyy-MM-dd","title":...,"link":"/..."}]}. Nhiều
     * link trỏ thẳng tới PDF (báo cáo tài chính) — vẫn hợp lệ, ingestListing tự fetch full-text
     * nếu link cùng host, hoặc title-only nếu SafeFetcher từ chối content-type PDF qua nhánh HTML.
     * Fix 2026-07-14 (Hanh: tiếp tục Hong Kong/Korea/Japan — Japan).
     */
    public List<ListingItem> parseNipponLife(byte[] body, String baseUrl) throws ParseFailedException {
        try {
            JsonNode arr = JSON.readTree(body);
            if (!arr.isArray() || arr.isEmpty()) {
                throw new ParseFailedException("NIPPON_LIFE: JSON không phải mảng hoặc rỗng — endpoint có thể đã đổi");
            }
            URI base = URI.create(baseUrl);
            String origin = base.getScheme() + "://" + base.getAuthority();
            List<ListingItem> items = new ArrayList<>();
            for (JsonNode n : arr) {
                String title = n.path("title").asText("").strip();
                String relLink = n.path("link").asText("").strip();
                if (title.isBlank() || relLink.isBlank()) continue;
                String link = relLink.startsWith("http") ? relLink : origin + relLink;
                Instant publishedAt = null;
                String dateStr = n.path("date").asText("").strip();
                if (!dateStr.isBlank()) {
                    try {
                        publishedAt = java.time.LocalDate.parse(dateStr).atStartOfDay(VN_ZONE).toInstant();
                    } catch (DateTimeParseException e) {
                        log.warn("NIPPON_LIFE: không parse được ngày '{}' — publishedAt để null", dateStr);
                    }
                }
                items.add(new ListingItem(title, link, publishedAt));
            }
            if (items.isEmpty()) {
                throw new ParseFailedException("NIPPON_LIFE: không có item hợp lệ sau khi lọc");
            }
            return items;
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("NIPPON_LIFE: lỗi parse response: " + e.getMessage());
        }
    }

    /**
     * NFRA Trung Quốc (nfra.gov.cn) — regulator ngân hàng+bảo hiểm hợp nhất. Trang chủ SPA
     * (Vue), HTML tĩnh gần rỗng. Đã thử 2 chuyên mục KHÔNG phù hợp trước khi tìm ra đúng:
     *  • itemId=914 "时政要闻" (tin thời sự) — link ra NGOÀI gov.cn, không phải nội dung NFRA.
     *  • itemId=950 "征求意见" (dự thảo lấy ý kiến) — chỉ có file .doc/.pdf, không có trang HTML.
     * ĐÚNG: itemId=915 "监管动态" (Động thái giám sát) — nội dung THẬT của NFRA (họp báo, gặp
     * cơ quan giám sát bảo hiểm nước ngoài, hướng dẫn AI ngân hàng/bảo hiểm...), endpoint
     * GET /cn/static/data/DocInfo/SelectDocByItemIdAndChild/data_itemId=915,pageIndex=1,
     * pageSize=18.json — bắt được bằng cách mở ĐÚNG trang danh mục (không phải trang chủ) và
     * xem network tab, vì trang chủ chỉ gọi itemId=914. isTitleLink="0" (không có titleLink) →
     * URL chi tiết build bằng tay: ItemDetail.html?docId={docId}&amp;itemId=915 (mẫu bắt được
     * từ href thật trên trang, xác nhận live 200). publishDate "yyyy-MM-dd HH:mm:ss" (dấu cách,
     * không phải "T" — cần NFRA_FMT riêng).
     * Fix 2026-07-14 (Hanh: tiếp tục Trung Quốc — regulator T1, lần dò thứ 3 mới ra).
     */
    public List<ListingItem> parseNfraCn(byte[] body, String baseUrl) throws ParseFailedException {
        try {
            JsonNode rows = JSON.readTree(body).path("data").path("rows");
            if (!rows.isArray() || rows.isEmpty()) {
                throw new ParseFailedException("NFRA_CN: JSON không có data.rows — endpoint có thể đã đổi");
            }
            URI base = URI.create(baseUrl);
            String origin = base.getScheme() + "://" + base.getAuthority();
            List<ListingItem> items = new ArrayList<>();
            for (JsonNode n : rows) {
                long docId = n.path("docId").asLong(-1);
                String title = n.path("docTitle").asText("").strip().replace("\n", " ");
                if (docId < 0 || title.isBlank()) continue;
                Instant publishedAt = null;
                String dateStr = n.path("publishDate").asText("").strip();
                if (!dateStr.isBlank()) {
                    try {
                        publishedAt = java.time.LocalDateTime.parse(dateStr, NFRA_FMT).atZone(VN_ZONE).toInstant();
                    } catch (DateTimeParseException e) {
                        log.warn("NFRA_CN: không parse được ngày '{}' — publishedAt để null", dateStr);
                    }
                }
                items.add(new ListingItem(title,
                        origin + "/cn/view/pages/ItemDetail.html?docId=" + docId + "&itemId=915", publishedAt));
            }
            if (items.isEmpty()) {
                throw new ParseFailedException("NFRA_CN: 'rows' rỗng sau khi lọc — không có bài hợp lệ");
            }
            return items;
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("NFRA_CN: lỗi parse response: " + e.getMessage());
        }
    }

    /**
     * China Life (HK/overseas) (chinalife.com.hk) — trang /about-us/news-center, Drupal server-
     * rendered (KHÔNG cần API riêng). Mỗi tin là {@code <div class="views-row"><div class=
     * "views-field-title">...<a href="...">Title</a></div><div class="views-field-created">
     * <span class="field-content">yyyy-MM-dd</span></div>...</div>} — thậm chí có sẵn tag
     * "Insurance"/khác trong views-field-field-news-tags (không dùng, Classifier tự lọc).
     * Fix 2026-07-14 (Hanh: tiếp tục Trung Quốc).
     */
    public List<ListingItem> parseChinaLifeHk(byte[] body, String baseUrl) throws ParseFailedException {
        try {
            Document doc = Jsoup.parse(new String(body, StandardCharsets.UTF_8), baseUrl);
            Elements rows = doc.select("div.views-row");
            List<ListingItem> items = new ArrayList<>();
            for (Element row : rows) {
                Element a = row.selectFirst(".views-field-title a");
                if (a == null) continue;
                String title = a.text().strip();
                String link = a.absUrl("href");
                if (title.isBlank() || link.isBlank()) continue;
                Instant publishedAt = null;
                Element dateEl = row.selectFirst(".views-field-created .field-content");
                if (dateEl != null) {
                    try {
                        publishedAt = java.time.LocalDate.parse(dateEl.text().strip()).atStartOfDay(VN_ZONE).toInstant();
                    } catch (DateTimeParseException e) {
                        log.warn("CHINALIFE_HK: không parse được ngày '{}' — publishedAt để null", dateEl.text());
                    }
                }
                items.add(new ListingItem(title, link, publishedAt));
            }
            if (items.isEmpty()) {
                throw new ParseFailedException("CHINALIFE_HK: không tìm thấy div.views-row nào — cấu trúc trang có thể đã đổi");
            }
            return items;
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("Jsoup lỗi khi parse CHINALIFE_HK: " + e.getMessage());
        }
    }

    /**
     * Great Eastern (Singapore) (greateasternlife.com) — trang /sg/en/about-us/media-centre/
     * media-releases.html, AEM server-rendered NHƯNG trang gộp NHIỀU BẢNG theo từng năm, mỗi
     * bảng cũ hơn dùng markup hơi khác (&lt;td&gt;&lt;p&gt;date&lt;/p&gt;&lt;/td&gt; cho năm cũ vs
     * &lt;td&gt;date&lt;/td&gt; trần cho năm gần đây — site rõ ràng đổi markup theo thời gian
     * nhưng giữ nguyên HTML cũ). Parser lấy text() của ô đầu (đọc đúng cả 2 kiểu) làm ngày,
     * &lt;a&gt; trong ô thứ 2 làm tiêu đề/link — không phân biệt bảng năm nào. Ngày "d MMMM yyyy"
     * (dùng lại AIA_HK_FMT — cùng định dạng). Chỉ nhận &lt;tr&gt; có ĐÚNG 2 &lt;td&gt; con trực
     * tiếp để tránh vơ nhầm bảng khác trên trang.
     * Fix 2026-07-14 (Hanh: tiếp tục Đông Nam Á — Singapore).
     */
    public List<ListingItem> parseGreatEastern(byte[] body, String baseUrl) throws ParseFailedException {
        try {
            Document doc = Jsoup.parse(new String(body, StandardCharsets.UTF_8), baseUrl);
            Elements rows = doc.select("tr");
            List<ListingItem> items = new ArrayList<>();
            for (Element row : rows) {
                Elements cells = row.select("> td");
                if (cells.size() != 2) continue;
                Element a = cells.get(1).selectFirst("a");
                if (a == null) continue;
                String title = a.text().strip();
                String link = a.absUrl("href");
                if (title.isBlank() || link.isBlank()) continue;
                Instant publishedAt = null;
                String dateText = cells.get(0).text().strip();
                if (!dateText.isBlank()) {
                    try {
                        publishedAt = java.time.LocalDate.parse(dateText, AIA_HK_FMT).atStartOfDay(VN_ZONE).toInstant();
                    } catch (DateTimeParseException e) {
                        continue; // hàng không phải date (vd tiêu đề bảng khác) — bỏ qua thay vì báo lỗi
                    }
                }
                items.add(new ListingItem(title, link, publishedAt));
            }
            if (items.isEmpty()) {
                throw new ParseFailedException("GREAT_EASTERN: không tìm thấy hàng media-release nào — cấu trúc trang có thể đã đổi");
            }
            return items;
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("Jsoup lỗi khi parse GREAT_EASTERN: " + e.getMessage());
        }
    }

    /**
     * Income Insurance / NTUC Income (income.com.sg) — trang /about-us/corporate-information/
     * press-releases, server-rendered, không cần API. Mỗi tin là {@code <div class=
     * "press-release-item"><div class="press-release-item-content"><p>d MMMM yyyy</p>
     * <p>Title</p></div><a href="...">Read More</a></div>} — link là SIBLING của content div,
     * không nằm trong đó. Ngày cùng định dạng "d MMMM yyyy" (dùng lại AIA_HK_FMT).
     * Fix 2026-07-14 (Hanh: tiếp tục Đông Nam Á — Singapore).
     */
    public List<ListingItem> parseIncomeSg(byte[] body, String baseUrl) throws ParseFailedException {
        try {
            Document doc = Jsoup.parse(new String(body, StandardCharsets.UTF_8), baseUrl);
            Elements items0 = doc.select("div.press-release-item");
            List<ListingItem> items = new ArrayList<>();
            for (Element item : items0) {
                Elements ps = item.select(".press-release-item-content > p");
                Element a = item.selectFirst("a[href]");
                if (ps.size() < 2 || a == null) continue;
                String title = ps.get(1).text().strip();
                String link = a.absUrl("href");
                if (title.isBlank() || link.isBlank()) continue;
                Instant publishedAt = null;
                String dateText = ps.get(0).text().strip();
                if (!dateText.isBlank()) {
                    try {
                        publishedAt = java.time.LocalDate.parse(dateText, AIA_HK_FMT).atStartOfDay(VN_ZONE).toInstant();
                    } catch (DateTimeParseException e) {
                        log.warn("INCOME_SG: không parse được ngày '{}' — publishedAt để null", dateText);
                    }
                }
                items.add(new ListingItem(title, link, publishedAt));
            }
            if (items.isEmpty()) {
                throw new ParseFailedException("INCOME_SG: không tìm thấy div.press-release-item nào — cấu trúc trang có thể đã đổi");
            }
            return items;
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("Jsoup lỗi khi parse INCOME_SG: " + e.getMessage());
        }
    }

    /**
     * Fubon Financial Holdings (fubon.com) — trang /Fubon_Portal/financialholdings/en/news/
     * list.jsp (newsroom chung cho cả tập đoàn, gồm Fubon Life — không có trang riêng cho công
     * ty bảo hiểm, xem ghi chú trong SeedData), server-rendered, không cần API. Mỗi tin là
     * {@code <li><a href="..." class="m-list-link">Title</a><time class="m-list-item">
     * yyyy.MM.dd</time></li>}.
     * Fix 2026-07-14 (Hanh: tiếp tục Đông Nam Á — Taiwan).
     */
    public List<ListingItem> parseFubonTw(byte[] body, String baseUrl) throws ParseFailedException {
        try {
            Document doc = Jsoup.parse(new String(body, StandardCharsets.UTF_8), baseUrl);
            Elements items0 = doc.select("li:has(a.m-list-link)");
            List<ListingItem> items = new ArrayList<>();
            for (Element item : items0) {
                Element a = item.selectFirst("a.m-list-link");
                if (a == null) continue;
                String title = a.text().strip();
                String link = a.absUrl("href");
                if (title.isBlank() || link.isBlank()) continue;
                Instant publishedAt = null;
                Element timeEl = item.selectFirst("time.m-list-item");
                if (timeEl != null) {
                    try {
                        publishedAt = java.time.LocalDate.parse(timeEl.text().strip(), FUBON_TW_FMT)
                                .atStartOfDay(VN_ZONE).toInstant();
                    } catch (DateTimeParseException e) {
                        log.warn("FUBON_TW: không parse được ngày '{}' — publishedAt để null", timeEl.text());
                    }
                }
                items.add(new ListingItem(title, link, publishedAt));
            }
            if (items.isEmpty()) {
                throw new ParseFailedException("FUBON_TW: không tìm thấy li có a.m-list-link nào — cấu trúc trang có thể đã đổi");
            }
            return items;
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("Jsoup lỗi khi parse FUBON_TW: " + e.getMessage());
        }
    }

    /**
     * Swiss Re Institute (swissre.com) — trang /institute/research/sigma-research.html, AEM
     * server-rendered, không cần API. Mỗi tin là {@code <article class="ArticleTeaser">
     * <div class="ArticleTeaser--content"><h3 class="ArticleTeaser--title">
     * <span class="ArticleTeaser--category">Publication</span>Title</h3>
     * <time class="ArticleTeaser--date" datetime="yyyy-MM-dd">...</time></div>
     * <a class="ArticleTeaser--link" href="...">}. Bỏ span category trước khi lấy text() để
     * không dính chữ "Publication" vào đầu tiêu đề. datetime attribute đã ISO sẵn, khỏi parse
     * chuỗi hiển thị "30 Jun 2026".
     * Fix 2026-07-14 (Hanh: chuyển sang cụm US/global).
     */
    public List<ListingItem> parseSwissReInstitute(byte[] body, String baseUrl) throws ParseFailedException {
        try {
            Document doc = Jsoup.parse(new String(body, StandardCharsets.UTF_8), baseUrl);
            Elements articles = doc.select("article.ArticleTeaser");
            List<ListingItem> items = new ArrayList<>();
            for (Element art : articles) {
                Element h3 = art.selectFirst(".ArticleTeaser--title");
                Element a = art.selectFirst("a.ArticleTeaser--link");
                if (h3 == null || a == null) continue;
                Element category = h3.selectFirst(".ArticleTeaser--category");
                if (category != null) category.remove();
                String title = h3.text().strip();
                String link = a.absUrl("href");
                if (title.isBlank() || link.isBlank()) continue;
                Instant publishedAt = null;
                Element dateEl = art.selectFirst("time.ArticleTeaser--date");
                String dateAttr = dateEl != null ? dateEl.attr("datetime").strip() : "";
                if (!dateAttr.isBlank()) {
                    try {
                        publishedAt = java.time.LocalDate.parse(dateAttr).atStartOfDay(VN_ZONE).toInstant();
                    } catch (DateTimeParseException e) {
                        log.warn("SWISSRE_INST: không parse được ngày '{}' — publishedAt để null", dateAttr);
                    }
                }
                items.add(new ListingItem(title, link, publishedAt));
            }
            if (items.isEmpty()) {
                throw new ParseFailedException("SWISSRE_INST: không tìm thấy article.ArticleTeaser nào — cấu trúc trang có thể đã đổi");
            }
            return items;
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("Jsoup lỗi khi parse SWISSRE_INST: " + e.getMessage());
        }
    }

    /**
     * NAIC (content.naic.org) — trang /newsroom, Drupal 11 server-rendered NHƯNG theme dùng
     * Tailwind utility class thuần (không có class ngữ nghĩa như "views-row" để bắt) — mỗi tin
     * là {@code <a href="/article/{slug}">...<h3>Title</h3>...<p>MMM. d, yyyy</p>...</a>}.
     * Vì không có class riêng cho ngày, quét regex ngày trên TOÀN BỘ text() của thẻ &lt;a&gt;
     * thay vì dò đúng vị trí &lt;p&gt; thứ mấy (bền hơn khi Tailwind class đổi).
     * Fix 2026-07-14 (Hanh: tiếp tục cụm US/global).
     */
    public List<ListingItem> parseNaic(byte[] body, String baseUrl) throws ParseFailedException {
        try {
            Document doc = Jsoup.parse(new String(body, StandardCharsets.UTF_8), baseUrl);
            Elements anchors = doc.select("a[href^=/article/]");
            List<ListingItem> items = new ArrayList<>();
            java.util.Set<String> seenLinks = new java.util.HashSet<>();
            for (Element a : anchors) {
                Element h3 = a.selectFirst("h3");
                if (h3 == null) continue;
                String title = h3.text().strip();
                String link = a.absUrl("href");
                if (title.isBlank() || link.isBlank() || !seenLinks.add(link)) continue;
                Instant publishedAt = null;
                var m = NAIC_DATE_PATTERN.matcher(a.text());
                if (m.find()) {
                    try {
                        publishedAt = java.time.LocalDate.parse(m.group(), NAIC_FMT).atStartOfDay(VN_ZONE).toInstant();
                    } catch (DateTimeParseException e) {
                        log.warn("NAIC: không parse được ngày '{}' — publishedAt để null", m.group());
                    }
                }
                items.add(new ListingItem(title, link, publishedAt));
            }
            if (items.isEmpty()) {
                throw new ParseFailedException("NAIC: không tìm thấy a[href^=/article/] nào — cấu trúc trang có thể đã đổi");
            }
            return items;
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("Jsoup lỗi khi parse NAIC: " + e.getMessage());
        }
    }

    /**
     * Munich Re (munichre.com) — trang /en/company/media-relations/media-information-and-
     * corporate-news/media-information.html, AEM. Trang tự nó gần rỗng (chỉ nav), nội dung nạp
     * qua GET .../_jcr_content.fulltextsearch.json?...&amp;sorting=publicationDateDesc&amp;
     * pageCategoryTag=munichre-apps:page-type-press-release&amp;rows=30 — endpoint tìm thấy từ
     * fetchUrl gốc là trang chủ (không phải trang tin), phải mở ĐÚNG trang media-information rồi
     * xem network tab mới ra. Response {"response":[{"title","targetUrl" (đã tuyệt đối),
     * "publicationDate":"MMMM d, yyyy"}]}.
     * Fix 2026-07-14 (Hanh: tiếp tục cụm US/global).
     */
    public List<ListingItem> parseMunichRe(byte[] body, String baseUrl) throws ParseFailedException {
        try {
            JsonNode resp = JSON.readTree(body).path("response");
            if (!resp.isArray() || resp.isEmpty()) {
                throw new ParseFailedException("MUNICHRE: JSON không có mảng 'response' — endpoint có thể đã đổi");
            }
            List<ListingItem> items = new ArrayList<>();
            for (JsonNode n : resp) {
                String title = n.path("title").asText("").strip();
                String link = n.path("targetUrl").asText("").strip();
                if (title.isBlank() || link.isBlank()) continue;
                Instant publishedAt = null;
                String dateStr = n.path("publicationDate").asText("").strip();
                if (!dateStr.isBlank()) {
                    try {
                        publishedAt = java.time.LocalDate.parse(dateStr, MUNICHRE_FMT).atStartOfDay(VN_ZONE).toInstant();
                    } catch (DateTimeParseException e) {
                        log.warn("MUNICHRE: không parse được ngày '{}' — publishedAt để null", dateStr);
                    }
                }
                items.add(new ListingItem(title, link, publishedAt));
            }
            if (items.isEmpty()) {
                throw new ParseFailedException("MUNICHRE: 'response' rỗng sau khi lọc — không có bài hợp lệ");
            }
            return items;
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("MUNICHRE: lỗi parse response: " + e.getMessage());
        }
    }

    /** Quét ngoặc {} cân bằng từ vị trí "from" (phải trỏ tới hoặc trước dấu "{" đầu tiên) — trả chuỗi JSON object đầy đủ. */
    private static String extractBalancedJsonObject(String s, int from) {
        int start = s.indexOf('{', from);
        if (start < 0) throw new IllegalStateException("không tìm thấy dấu '{' bắt đầu object");
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return s.substring(start, i + 1);
            }
        }
        throw new IllegalStateException("không tìm thấy dấu '}' đóng object cân bằng");
    }

    /**
     * MOF_ISA (mof.gov.vn) — Cục QLGS Bảo hiểm. Portal là SPA (Vue), HTML tĩnh chỉ là
     * &lt;div id="app"&gt; rỗng. JS gọi REST API sạch:
     *   • DANH SÁCH: POST /api/article/reads?offset&amp;limit, body {"rootCategoryId": &lt;id BH&gt;}
     *     → data[] có title, slug, publicationTime (ISO), categorySlug, rootCategorySlug, description.
     *   • CHI TIẾT: GET /api/article/getbyslug?slug=… → data.articleContent (là chuỗi JSON
     *     lồng {"Content":"&lt;html&gt;"}), lấy full text (xem parseMofContent).
     * Link người đọc: /{rootCategorySlug}/{categorySlug}/{slug} (route SPA).
     * Fix 2026-07-14 (Hanh: ưu tiên regulator VN trước).
     */
    public List<MofArticle> parseMofList(byte[] body, String baseUrl) throws ParseFailedException {
        try {
            JsonNode data = JSON.readTree(body).get("data");
            if (data == null || !data.isArray() || data.isEmpty()) {
                throw new ParseFailedException("MOF_ISA: JSON không có mảng 'data' — endpoint có thể đã đổi");
            }
            URI base = URI.create(baseUrl);
            String origin = base.getScheme() + "://" + base.getAuthority();
            List<MofArticle> items = new ArrayList<>();
            for (JsonNode a : data) {
                String title = a.path("title").asText("").strip();
                String slug = a.path("slug").asText("").strip();
                String catSlug = a.path("categorySlug").asText("").strip();
                String rootSlug = a.path("rootCategorySlug").asText("").strip();
                if (title.isBlank() || slug.isBlank()) continue;
                String url = origin + "/" + rootSlug + "/" + catSlug + "/" + slug;
                Instant publishedAt = parseFlexibleInstant(a.path("publicationTime").asText(""));
                items.add(new MofArticle(title, url, slug, publishedAt, a.path("description").asText("").strip()));
            }
            if (items.isEmpty()) {
                throw new ParseFailedException("MOF_ISA: 'data' rỗng sau khi lọc — không có bài hợp lệ");
            }
            return items;
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("MOF_ISA: lỗi parse JSON danh sách: " + e.getMessage());
        }
    }

    /** Chi tiết MOF: data.articleContent là chuỗi JSON lồng {"Content":"&lt;html&gt;"} → text. null nếu hỏng. */
    public String parseMofContent(byte[] detailBody) {
        try {
            String articleContent = JSON.readTree(detailBody).path("data").path("articleContent").asText("");
            if (articleContent.isBlank()) return null;
            String html = JSON.readTree(articleContent).path("Content").asText("");
            if (html.isBlank()) return null;
            String text = Jsoup.parse(html).text().strip();
            return text.isBlank() ? null : text;
        } catch (Exception e) {
            log.warn("MOF_ISA: không parse được articleContent: {}", e.getMessage());
            return null;
        }
    }

    /**
     * One high-value VIDI/MOF research article is outside the regulator list root
     * used by MOF_ISA. Fetch its public detail API directly, while retaining the
     * human-facing official page as the evidence URL.
     */
    public ListingItem parseMofDirectArticle(byte[] detailBody, String browseUrl)
            throws ParseFailedException {
        try {
            JsonNode data = JSON.readTree(detailBody).path("data");
            String title = data.path("title").asText("").strip();
            String text = parseMofContent(detailBody);
            Instant publishedAt = parseFlexibleInstant(data.path("publicationTime").asText(""));
            if (title.isBlank() || text == null || text.length() < 600 || browseUrl == null
                    || !browseUrl.startsWith("https://")) {
                throw new ParseFailedException(
                        "MOF direct article missing title, dated full text, or official browse URL");
            }
            return new ListingItem(title, browseUrl, publishedAt, text);
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("MOF direct article parse failed: " + e.getMessage());
        }
    }

    /** ISO có Z/millis (Instant.parse) hoặc không zone ("2025-04-10T08:31:01") → coi giờ VN. */
    /** Dùng chung cho mọi nguồn có ISO datetime lẫn lộn có/không zone (MOF_ISA, MB_AGEAS, FWD_VN). */
    private static Instant parseFlexibleInstant(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Instant.parse(raw.strip());
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(raw.strip()).atZone(VN_ZONE).toInstant();
            } catch (DateTimeParseException e2) {
                log.warn("parseFlexibleInstant: không parse được '{}'", raw);
                return null;
            }
        }
    }

    public record ParsedText(String title, String text, String note) {}
    /** descriptionHtml giữ NGUYÊN markup của mô tả (khác descriptionText đã text-hoá) — cần cho
     * NewsDiscoveryService trích URL nhà-xuất-bản gốc từ thẻ &lt;a&gt; trong feed Google News
     * (link của item là link trung gian news.google.com, không phải bài viết thật). */
    public record RssItem(String title, String link, String descriptionText, String descriptionHtml,
                          Instant publishedAt) {}
    public record ListingItem(String title, String link, Instant publishedAt, String embeddedText) {
        public ListingItem(String title, String link, Instant publishedAt) {
            this(title, link, publishedAt, null);
        }
    }
    public record ReaderArticle(String title, String text, Instant publishedAt) {}
    public record MofArticle(String title, String url, String slug, Instant publishedAt, String description) {}

    public static class ParseFailedException extends Exception {
        public ParseFailedException(String message) { super(message); }
    }
}
