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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
    private static final java.util.regex.Pattern MANULIFE_YEAR = java.util.regex.Pattern.compile("(\\d{4})");
    private static final java.util.regex.Pattern AIA_MONTH_ARCHIVE_LINK = java.util.regex.Pattern.compile("/\\d{4}/\\d{2}\\.html$");
    // Fix 2026-07-14: URL bài AIA có năm ngay trong path (.../su-kien-noi-bat/2024/...) —
    // dùng làm fallback khi card KHÔNG có div ngày, để tin cũ vẫn có publishedAt (năm-01-01)
    // và bị bộ lọc "7 ngày" loại đúng, thay vì rơi về fetchedAt=hôm nay rồi hiện như tin mới.
    private static final java.util.regex.Pattern AIA_URL_YEAR = java.util.regex.Pattern.compile("/(20\\d{2})/");
    private static final java.util.regex.Pattern DDMMYYYY = java.util.regex.Pattern.compile("(\\d{2}/\\d{2}/\\d{4})");

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

    public record ParsedText(String title, String text, String note) {}
    public record RssItem(String title, String link, String descriptionText, Instant publishedAt) {}
    public record ListingItem(String title, String link, Instant publishedAt) {}

    public static class ParseFailedException extends Exception {
        public ParseFailedException(String message) { super(message); }
    }
}
