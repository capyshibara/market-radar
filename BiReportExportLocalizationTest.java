import com.marketradar.product.ProductMarketScope;
import com.marketradar.report.bi.BiFinding;
import com.marketradar.report.bi.BiReportContent;
import com.marketradar.report.bi.BiReportDocxService;
import com.marketradar.report.bi.BiReportPptxService;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/** Regression guard: non-PDF exports must honour the same EN/VI selection as the web report. */
public class BiReportExportLocalizationTest {

    public static void main(String[] args) throws Exception {
        BiFinding finding = new BiFinding(BiFinding.MACRO_ECONOMIC, "Vietnam market",
                "Nội dung tiếng Việt", "Clear English evidence", true, List.of(),
                ProductMarketScope.VIETNAM, "Vietnam");
        BiReportContent content = new BiReportContent(
                "Business Intelligence Report", "Year to date 2026", "Techcom Life",
                "06 Aug 2026", 1, List.of(finding), List.of(), List.of());

        String docx = docxText(new BiReportDocxService().render(content, false));
        assert docx.contains("Period:") : docx;
        assert docx.contains("Clear English evidence") : docx;
        assert !docx.contains("Nội dung tiếng Việt") : docx;
        assert !docx.contains("Tạo lúc") : docx;

        String pptx = pptxText(new BiReportPptxService().render(content, false));
        assert pptx.contains("EXECUTIVE SUMMARY") : pptx;
        assert pptx.contains("Clear English evidence") : pptx;
        assert !pptx.contains("Nội dung tiếng Việt") : pptx;
        assert !pptx.contains("Tạo lúc") : pptx;

        List<BiFinding> manyEvents = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            manyEvents.add(new BiFinding(BiFinding.COMPANY_EVENT, "Competitor " + i,
                    "Diễn biến " + i, "Development " + i, true, List.of(),
                    ProductMarketScope.VIETNAM, "Vietnam"));
        }
        try (XMLSlideShow slides = new XMLSlideShow(new ByteArrayInputStream(
                new BiReportPptxService().render(new BiReportContent(
                        "Business Intelligence Report", "Year to date 2026", "Techcom Life",
                        "06 Aug 2026", 20, manyEvents, List.of(), List.of()), false)))) {
            long executiveSlides = slides.getSlides().stream()
                    .filter(slide -> slide.getShapes().stream()
                            .filter(XSLFTextShape.class::isInstance)
                            .map(XSLFTextShape.class::cast)
                            .anyMatch(shape -> shape.getText().startsWith("EXECUTIVE SUMMARY")))
                    .count();
            assert executiveSlides <= 6 : "briefing must cap executive finding slides";
            slides.getSlides().forEach(slide -> slide.getShapes().stream()
                    .filter(XSLFTable.class::isInstance)
                    .map(XSLFTable.class::cast)
                    .forEach(table -> {
                        assert table.getNumberOfRows() <= 8
                                : "table exceeds seven data rows plus header";
                    }));
        }
        System.out.println("BiReportExportLocalizationTest: ALL PASS");
    }

    private static String docxText(byte[] bytes) throws Exception {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            return document.getParagraphs().stream()
                    .map(paragraph -> paragraph.getText())
                    .reduce("", (left, right) -> left + "\n" + right);
        }
    }

    private static String pptxText(byte[] bytes) throws Exception {
        try (XMLSlideShow slides = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            StringBuilder out = new StringBuilder();
            slides.getSlides().forEach(slide -> {
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape text) out.append(text.getText()).append('\n');
                }
            });
            return out.toString();
        }
    }
}
