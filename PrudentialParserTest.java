import com.marketradar.parse.ContentParsers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Test thủ công cho ContentParsers.parsePrudential — cùng style IavParserTest, không JUnit.
 * Fixture: fixtures/prudential-vn-news.html, lấy từ trang thật 05/07/2026, không hit live trong test.
 *
 * Chạy: javac -cp target/classes -d /tmp/out PrudentialParserTest.java
 *       java -cp target/classes:/tmp/out PrudentialParserTest
 */
public class PrudentialParserTest {

    public static void main(String[] args) throws Exception {
        byte[] html = Files.readAllBytes(Path.of("fixtures/prudential-vn-news.html"));
        ContentParsers parsers = new ContentParsers();

        List<ContentParsers.ListingItem> items = parsers.parsePrudential(html,
                "https://www.prudential.com.vn/vi/ve-chung-toi/thong-cao-bao-chi/");

        assertTrue(!items.isEmpty(), "parsePrudential phải trả về ít nhất 1 item từ fixture thật");
        System.out.println("Tổng số item: " + items.size());

        for (ContentParsers.ListingItem item : items) {
            assertTrue(item.title() != null && !item.title().isBlank(), "Mỗi item phải có title không rỗng");
            assertTrue(item.link() != null && item.link().startsWith("https://www.prudential.com.vn"),
                    "Link phải resolve tuyệt đối về đúng host, thực tế: " + item.link());
            assertTrue(item.publishedAt() != null,
                    "Prudential luôn có data-date rõ ràng trên article — null nghĩa là attribute selector sai");
        }

        ContentParsers.ListingItem first = items.get(0);
        System.out.println("Item đầu tiên: [" + first.title() + "] -> " + first.link() + " @ " + first.publishedAt());
        assertTrue(first.publishedAt().isBefore(Instant.now()), "Item đầu tiên phải có ngày đăng hợp lệ trong quá khứ");

        System.out.println("PrudentialParserTest: PASS (" + items.size() + " item, 0 ngày null)");
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FAIL: " + msg);
    }
}
