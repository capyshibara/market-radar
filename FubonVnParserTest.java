import vn.techcomlife.marketradar.parse.ContentParsers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Test thủ công cho ContentParsers.parseFubonVn — cùng style IavParserTest, không JUnit.
 * Fixture: fixtures/fubon-vn-news.html, lấy từ trang thật 05/07/2026, không hit live trong test.
 *
 * Chạy: javac -cp target/classes -d /tmp/out FubonVnParserTest.java
 *       java -cp target/classes:/tmp/out FubonVnParserTest
 */
public class FubonVnParserTest {

    public static void main(String[] args) throws Exception {
        byte[] html = Files.readAllBytes(Path.of("fixtures/fubon-vn-news.html"));
        ContentParsers parsers = new ContentParsers();

        List<ContentParsers.ListingItem> items = parsers.parseFubonVn(html,
                "https://www.fubonlife.com.vn/tin-tuc.html?tab=5");

        assertTrue(!items.isEmpty(), "parseFubonVn phải trả về ít nhất 1 item từ fixture thật");
        System.out.println("Tổng số item: " + items.size());

        for (ContentParsers.ListingItem item : items) {
            assertTrue(item.title() != null && !item.title().isBlank(), "Mỗi item phải có title không rỗng");
            assertTrue(item.link() != null && item.link().startsWith("https://www.fubonlife.com.vn"),
                    "Link phải resolve tuyệt đối về đúng host, thực tế: " + item.link());
            assertTrue(item.publishedAt() != null,
                    "Fubon VN luôn có ngày rõ ràng (regex dd/MM/yyyy trên div.time) — null nghĩa là regex sai");
        }

        ContentParsers.ListingItem first = items.get(0);
        System.out.println("Item đầu tiên: [" + first.title() + "] -> " + first.link() + " @ " + first.publishedAt());
        assertTrue(first.publishedAt().isBefore(Instant.now()), "Item đầu tiên phải có ngày đăng hợp lệ trong quá khứ");

        System.out.println("FubonVnParserTest: PASS (" + items.size() + " item, 0 ngày null)");
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FAIL: " + msg);
    }
}
