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
import org.jsoup.select.Elements;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter IAV_FMT_EN = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter IAV_FMT_VI = DateTimeFormatter.ofPattern("dd/MM/yyyy h:mm:ss a", Locale.ENGLISH);
    private static final DateTimeFormatter AIA_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH);
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
            if (text == null || text.isBlank()) {
                throw new ParseFailedException("PDF không trích được text (có thể là scan ảnh)");
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
     * chuyên mục bảo hiểm riêng nên lấy trang chủ (Classifier lọc liên quan sau).
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
            if (result.isEmpty()) {
                throw new ParseFailedException("TBNH: không tìm thấy div.article[id^=article-] nào — cấu trúc trang có thể đã đổi");
            }
            return result;
        } catch (ParseFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseFailedException("Jsoup lỗi khi parse TBNH: " + e.getMessage());
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
                items.add(new ListingItem(displayTitle, origin + urlPath, publishedAt));
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
     * Dai-ichi Life Việt Nam (dai-ichi-life.com.vn) — trang chủ là SPA-ish (Alpine.js), tin
     * tức "widget" nạp qua POST /api/news/home (body rỗng "{}" là đủ, xác nhận thủ công).
     * Response KHÔNG phải JSON có field sạch — là JSON BỌC MỘT CHUỖI HTML đã render sẵn:
     * {@code {"status":"success","data":"&lt;div class=\"item-news\"&gt;...&lt;/div&gt;..."}}.
     * Mỗi tin là {@code <div class="... item-news ...">} hoặc {@code item-news-horizontal}
     * (2 layout khác nhau cho cùng loại thẻ) chứa {@code <h3 class="card-title-2"><a href="...">
     * Title</a></h3>} và {@code <p class="publish_at">...&lt;span&gt;dd/MM/yyyy&lt;/span&gt;...}
     * (bản horizontal không có &lt;span&gt; quanh ngày — lấy text() rồi regex ngày, không phụ
     * thuộc cấu trúc con). Link trỏ sang subdomain KHÁC (kh.dai-ichi-life.com.vn — health
     * content) → ingestListing tự rơi về title-only (vẫn có ngày thật), không mở whitelist.
     * Fix 2026-07-14 (Hanh: ưu tiên VN competitor, Case A — hidden JSON API).
     */
    public List<ListingItem> parseDaiichiVn(byte[] body, String baseUrl) throws ParseFailedException {
        try {
            String html = JSON.readTree(body).path("data").asText("");
            if (html.isBlank()) {
                throw new ParseFailedException("DAIICHI_VN: JSON envelope không có field 'data' — endpoint có thể đã đổi");
            }
            Document doc = Jsoup.parse(html, baseUrl);
            Elements cards = doc.select(".item-news, .item-news-horizontal");
            List<ListingItem> items = new ArrayList<>();
            for (Element card : cards) {
                Element a = card.selectFirst("h3.card-title-2 a");
                if (a == null) continue;
                String title = a.text().strip();
                String link = a.attr("href").strip();
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
            throw new ParseFailedException("DAIICHI_VN: lỗi parse response: " + e.getMessage());
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
            Elements cards = doc.select(".dropshadowboxes-container");
            List<ListingItem> items = new ArrayList<>();
            for (Element card : cards) {
                Element titleA = card.selectFirst("strong a");
                Element linkA = card.selectFirst("a[class*=btn-shinhan]");
                if (titleA == null || linkA == null) continue;
                String title = titleA.text().strip();
                String link = linkA.attr("href").strip();
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
                if (!contentRaw.isBlank()) {
                    try {
                        JsonNode content = JSON.readTree(contentRaw);
                        title = content.path("vi_VN").path("title").asText("");
                        if (title.isBlank()) title = content.path("en_US").path("title").asText("");
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
                items.add(new ListingItem(title.strip(), origin + "/cathay/news-detail?news_id=" + newsId, publishedAt));
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
    public record RssItem(String title, String link, String descriptionText, Instant publishedAt) {}
    public record ListingItem(String title, String link, Instant publishedAt) {}
    public record MofArticle(String title, String url, String slug, Instant publishedAt, String description) {}

    public static class ParseFailedException extends Exception {
        public ParseFailedException(String message) { super(message); }
    }
}
