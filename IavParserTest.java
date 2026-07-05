import com.marketradar.parse.ContentParsers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Test thủ công cho ContentParsers.parseIav — theo đúng style GateL1Test/Batch5LogicTest
 * (không JUnit, project chưa có src/test + dependency test). Chạy KHÔNG cần mạng:
 * dùng fixture HTML đã lưu từ 1 lần fetch thật (fixtures/iav-home.html, lấy 05/07/2026),
 * không hit live iav.vn trong test.
 *
 * Chạy: javac -cp target/classes -d /tmp/out IavParserTest.java
 *       java -cp target/classes:/tmp/out IavParserTest
 */
public class IavParserTest {

    public static void main(String[] args) throws Exception {
        byte[] html = Files.readAllBytes(Path.of("fixtures/iav-home.html"));
        ContentParsers parsers = new ContentParsers();

        List<ContentParsers.ListingItem> items = parsers.parseIav(html, "https://iav.vn/");

        assertTrue(!items.isEmpty(), "parseIav phải trả về ít nhất 1 item từ fixture thật");
        System.out.println("Tổng số item: " + items.size());

        int nullDates = 0;
        for (ContentParsers.ListingItem item : items) {
            assertTrue(item.title() != null && !item.title().isBlank(),
                    "Mỗi item phải có title không rỗng");
            assertTrue(item.link() != null && item.link().startsWith("https://iav.vn"),
                    "Link phải resolve tuyệt đối về đúng host iav.vn, thực tế: " + item.link());
            if (item.publishedAt() == null) nullDates++;
        }
        System.out.println("Item có publishedAt = null: " + nullDates + "/" + items.size());
        assertTrue(nullDates == 0,
                "Cả 2 định dạng datetime quan sát được trên trang thật (EN 'MMM d, yyyy' và "
                + "VI 'dd/MM/yyyy h:mm:ss SA/CH') phải parse được — còn null nghĩa là có "
                + "format thứ 3 chưa xử lý, cần xem lại fixture, không được âm thầm bỏ qua.");

        // Sanity check cụ thể trên 1 item đầu tiên — bắt regression nếu selector đổi hành vi
        ContentParsers.ListingItem first = items.get(0);
        System.out.println("Item đầu tiên: [" + first.title() + "] -> " + first.link()
                + " @ " + first.publishedAt());
        assertTrue(first.publishedAt() != null && first.publishedAt().isBefore(Instant.now()),
                "Item đầu tiên phải có ngày đăng hợp lệ trong quá khứ");

        System.out.println("IavParserTest: PASS (" + items.size() + " item, 0 lỗi parse ngày)");
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FAIL: " + msg);
    }
}
