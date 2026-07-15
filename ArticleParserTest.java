import com.marketradar.parse.ContentParsers;

import java.nio.charset.StandardCharsets;

/**
 * Test thủ công cho ContentParsers.parseArticleHtml (fix 2026-07-15 — audit chất lượng:
 * rawText trước đây là Document.text() nguyên trang, menu/footer chiếm ngàn ký tự đầu).
 * Cùng style AiaParserTest, không JUnit. HTML tổng hợp (deterministic), không cần fixture.
 *
 * Chạy: javac -cp target/classes -d /tmp/out ArticleParserTest.java
 *       java -cp target/classes:/tmp/out ArticleParserTest
 */
public class ArticleParserTest {

    public static void main(String[] args) throws Exception {
        ContentParsers parsers = new ContentParsers();

        // Case 1: trang có nav menu dài + <article> — phải lấy ĐÚNG bài, loại menu.
        String articleBody = ("Prudential Việt Nam ngày 12/07/2026 công bố ra mắt sản phẩm "
                + "bảo hiểm liên kết chung mới với mức phí từ 5 triệu đồng mỗi năm. ").repeat(8);
        String page1 = """
                <html><head><title>Tin tức</title></head><body>
                <header><div class="menu">Sản phẩm phổ biến Khỏe Trọn Vẹn An Tâm Tài Chính
                Thanh Toán Phí Bảo Hiểm Tuyển Dụng Cơ Hội Nghề Nghiệp Liên Hệ</div></header>
                <nav>Trang chủ Về chúng tôi Dịch vụ Khách hàng Tin tức</nav>
                <article><h1>Ra mắt sản phẩm mới</h1><p>%s</p></article>
                <footer>Bản quyền 2026 — Điều khoản sử dụng — Chính sách bảo mật</footer>
                </body></html>""".formatted(articleBody);
        var parsed1 = parsers.parseArticleHtml(page1.getBytes(StandardCharsets.UTF_8));
        assertTrue(parsed1.text().contains("Prudential Việt Nam ngày 12/07/2026"),
                "Phải giữ nội dung bài viết");
        assertTrue(!parsed1.text().contains("Thanh Toán Phí Bảo Hiểm"),
                "Không được lẫn text menu header, thực tế: " + head(parsed1.text()));
        assertTrue(!parsed1.text().contains("Chính sách bảo mật"),
                "Không được lẫn text footer");
        assertTrue(parsed1.note() == null, "Có <article> đủ dài — không được rơi về fallback");
        System.out.println("Case 1 OK — article được chọn, " + parsed1.text().length() + " ký tự");

        // Case 2: trang KHÔNG có khối article/main — fallback body ĐÃ strip nav/footer.
        String page2 = """
                <html><body>
                <nav>Menu A Menu B Menu C</nav>
                <div class="random-wrapper"><p>Generali Việt Nam khai trương văn phòng
                tổng đại lý thứ 100 tại Đà Nẵng ngày 01/07/2026.</p></div>
                <footer>Liên hệ hotline</footer>
                </body></html>""";
        var parsed2 = parsers.parseArticleHtml(page2.getBytes(StandardCharsets.UTF_8));
        assertTrue(parsed2.text().contains("Generali Việt Nam khai trương"),
                "Fallback vẫn phải giữ nội dung chính");
        assertTrue(!parsed2.text().contains("Menu A"), "Fallback vẫn phải loại nav");
        assertTrue(parsed2.note() != null, "Không khớp selector — note phải ghi rõ fallback");
        System.out.println("Case 2 OK — fallback strip boilerplate, note: " + parsed2.note());

        // Case 3: nhiều ứng viên — chọn khối DÀI nhất (article thật, không phải div content rỗng).
        String longBody = "Fubon Life công bố lãi ròng 7,82 tỷ NT$ trong tháng 5 năm 2026. ".repeat(12);
        String page3 = """
                <html><body>
                <div class="content">Ngắn.</div>
                <article>%s</article>
                </body></html>""".formatted(longBody);
        var parsed3 = parsers.parseArticleHtml(page3.getBytes(StandardCharsets.UTF_8));
        assertTrue(parsed3.text().contains("Fubon Life công bố lãi ròng"),
                "Phải chọn khối nội dung dài nhất");
        System.out.println("Case 3 OK — chọn đúng khối dài nhất");

        System.out.println("ArticleParserTest: TẤT CẢ PASS");
    }

    private static String head(String s) {
        return s.length() <= 160 ? s : s.substring(0, 160) + "…";
    }

    private static void assertTrue(boolean cond, String message) {
        if (!cond) {
            System.err.println("FAIL: " + message);
            System.exit(1);
        }
    }
}
