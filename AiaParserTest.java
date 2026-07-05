import com.marketradar.parse.ContentParsers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Test thủ công cho ContentParsers.parseAia — cùng style IavParserTest, không JUnit.
 * Fixture: fixtures/aia-vn-news.html, lấy từ trang thật 05/07/2026, không hit live trong test.
 *
 * Chạy: javac -cp target/classes -d /tmp/out AiaParserTest.java
 *       java -cp target/classes:/tmp/out AiaParserTest
 */
public class AiaParserTest {

    public static void main(String[] args) throws Exception {
        byte[] html = Files.readAllBytes(Path.of("fixtures/aia-vn-news.html"));
        ContentParsers parsers = new ContentParsers();

        List<ContentParsers.ListingItem> items = parsers.parseAia(html,
                "https://www.aia.com.vn/vi/ve-chung-toi/truyen-thong/su-kien-noi-bat.html");

        assertTrue(!items.isEmpty(), "parseAia phải trả về ít nhất 1 item từ fixture thật");
        System.out.println("Tổng số item: " + items.size());

        int nullDates = 0;
        for (ContentParsers.ListingItem item : items) {
            assertTrue(item.title() != null && !item.title().isBlank(), "Mỗi item phải có title không rỗng");
            assertTrue(item.link() != null && item.link().startsWith("https://www.aia.com.vn"),
                    "Link phải resolve tuyệt đối về đúng host, thực tế: " + item.link());
            assertTrue(!item.link().matches(".*/\\d{4}/\\d{2}\\.html$"),
                    "Không được lọt card điều hướng theo tháng (regression của bug đã fix), thực tế: " + item.link());
            if (item.publishedAt() == null) nullDates++;
        }
        // Trang thật có card cũ KHÔNG có div ngày — publishedAt=null cho các item đó là ĐÚNG, không phải lỗi.
        System.out.println("Item có publishedAt = null (card không có ngày trên trang gốc): " + nullDates + "/" + items.size());
        assertTrue(nullDates < items.size(), "Ít nhất một số item phải parse được ngày — nếu 100% null thì selector ngày có thể đã sai");

        ContentParsers.ListingItem first = items.get(0);
        System.out.println("Item đầu tiên: [" + first.title() + "] -> " + first.link() + " @ " + first.publishedAt());

        System.out.println("AiaParserTest: PASS (" + items.size() + " item)");
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FAIL: " + msg);
    }
}
