import com.marketradar.intake.DocumentMetadataDetector;
import com.marketradar.intake.ManualDocumentRules;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;

/** Standalone regressions for zero-field URL/file metadata extraction. */
public class DocumentMetadataDetectorTest {
    public static void main(String[] args) throws Exception {
        String html = """
                <html lang="vi-VN" data-datepublished="2026-06-22T18:43:20+07:00"><head>
                <title>Product notice | AIA</title>
                <meta property="og:site_name" content="Untrusted display label"/>
                </head><body><article>Thông báo điều chỉnh sản phẩm và quyền lợi bảo hiểm.</article></body></html>
                """;
        var fromHtml = DocumentMetadataDetector.html(html.getBytes(),
                "Thông báo điều chỉnh sản phẩm và quyền lợi bảo hiểm.",
                "Product notice | AIA", "https://www.aia.com.vn/notice");
        check(fromHtml.publisher().equals("AIA Việt Nam"), "known host controls publisher identity");
        check(fromHtml.publishedDate().equals(LocalDate.of(2026, 6, 22)), "HTML publication date detected");
        check(fromHtml.language().equals("vi"), "HTML language detected");

        byte[] pdfBytes;
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            pdf.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            PDDocumentInformation info = new PDDocumentInformation();
            info.setTitle("BCG Insurance Report");
            info.setCreationDate(new java.util.Calendar.Builder().setDate(2020, 0, 1).build());
            pdf.setDocumentInformation(info);
            pdf.save(out);
            pdfBytes = out.toByteArray();
        }
        var fromPdf = DocumentMetadataDetector.pdf(pdfBytes,
                "INSURANCE INDUSTRY\nJUNE 25, 2026\n©2026 Boston Consulting Group", "report.pdf");
        check(fromPdf.publisher().equals("Boston Consulting Group"), "PDF publisher detected from content");
        check(fromPdf.publishedDate().equals(LocalDate.of(2026, 6, 25)),
                "content publication date wins; PDF creation timestamp is ignored");

        check(ManualDocumentRules.directImportUrl("https://www.bcg.com/report").contains("bcg.com"),
                "official URL accepted");
        expectFailure(() -> ManualDocumentRules.directImportUrl("https://www.linkedin.com/posts/123"));
        System.out.println("DocumentMetadataDetectorTest: ALL PASS");
    }

    private static void expectFailure(Runnable runnable) {
        try { runnable.run(); throw new AssertionError("Expected validation failure"); }
        catch (ManualDocumentRules.ValidationException expected) {}
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
