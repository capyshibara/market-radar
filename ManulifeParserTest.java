import com.marketradar.parse.ContentParsers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Test thủ công cho ContentParsers.parseManulife — cùng style IavParserTest, không JUnit.
 * Fixture: fixtures/manulife-vn-news.html, lấy từ trang thật 05/07/2026, không hit live trong test.
 *
 * Lưu ý: publishedAt ở đây CHỈ chính xác đến NĂM (01/01), vì trang gốc chỉ nhóm tin theo
 * <h2>Năm YYYY</h2>, không có ngày cụ thể trên từng card — test phản ánh đúng giới hạn này,
 * không giả vờ chính xác hơn.
 *
 * Chạy: javac -cp target/classes -d /tmp/out ManulifeParserTest.java
 *       java -cp target/classes:/tmp/out ManulifeParserTest
 */
public class ManulifeParserTest {

    public static void main(String[] args) throws Exception {
        byte[] html = Files.readAllBytes(Path.of("fixtures/manulife-vn-news.html"));
        ContentParsers parsers = new ContentParsers();

        List<ContentParsers.ListingItem> items = parsers.parseManulife(html,
                "https://www.manulife.com.vn/vi/ve-chung-toi/tin-tuc-va-su-kien/thong-cao-bao-chi.html");

        assertTrue(!items.isEmpty(), "parseManulife phải trả về ít nhất 1 item từ fixture thật");
        System.out.println("Tổng số item: " + items.size());

        int nullDates = 0;
        for (ContentParsers.ListingItem item : items) {
            assertTrue(item.title() != null && !item.title().isBlank(), "Mỗi item phải có title không rỗng");
            assertTrue(item.link() != null && item.link().startsWith("https://www.manulife.com.vn"),
                    "Link phải resolve tuyệt đối về đúng host, thực tế: " + item.link());
            if (item.publishedAt() == null) nullDates++;
        }
        System.out.println("Item có publishedAt = null (không tìm thấy h2 Năm YYYY đứng trước): " + nullDates + "/" + items.size());
        assertTrue(nullDates == 0, "Trang thật luôn có h2 'Năm YYYY' trước mỗi nhóm — null nghĩa là DOM order giả định sai");

        ContentParsers.ListingItem first = items.get(0);
        System.out.println("Item đầu tiên: [" + first.title() + "] -> " + first.link() + " @ " + first.publishedAt()
                + " (độ chính xác: NĂM, không phải ngày thật)");

        System.out.println("ManulifeParserTest: PASS (" + items.size() + " item, độ chính xác ngày = NĂM)");
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FAIL: " + msg);
    }
}
