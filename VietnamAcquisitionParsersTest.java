package com.marketradar.parse;

import com.marketradar.intake.DocumentMetadataDetector;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;

/** Offline acquisition regression tests; no network or Spring context required. */
public class VietnamAcquisitionParsersTest {
    public static void main(String[] args) throws Exception {
        ContentParsers parsers = new ContentParsers();

        String vietnamPlus = """
                <article class='story'><h2 class='story__heading'>
                  <a href='/bao-hiem-a-post1.vnp' title='Sản phẩm bảo hiểm nhân thọ mới'>ignored</a>
                </h2><time datetime='2026-07-20T09:30:00+07:00'></time></article>
                """;
        var vp = parsers.parseVietnamPlusInsurance(bytes(vietnamPlus), "https://www.vietnamplus.vn/tag/x");
        check(vp.size() == 1, "VietnamPlus article discovered");
        check(LocalDate.ofInstant(vp.get(0).publishedAt(), ZoneId.of("Asia/Ho_Chi_Minh"))
                .equals(LocalDate.of(2026, 7, 20)), "VietnamPlus ISO date retained");

        String baoDauTu = """
                <ul class='list_news_home'><li><article><div class='desc_list_news_home'>
                  <a class='fs22 fbold' href='/bao-hiem-nhan-tho-d123.html'>Tin nhân thọ</a>
                </div></article></li></ul>
                """;
        var bdt = parsers.parseBaoDauTuLife(bytes(baoDauTu), "https://baodautu.vn/tag/bao-hiem-nhan-tho/");
        check(bdt.size() == 1 && bdt.get(0).link().endsWith("d123.html"),
                "Báo Đầu tư exact tag parsed");

        String vietnamFinance = """
                <div class='article article_last' last-push='2026-03-17 12:00:00'>
                  <h3 class='article__title'><a href='/ngan-hang-tai-dinh-hinh-bao-hiem-d1.html'
                    title='Ngân hàng Việt đang tái định hình thị trường bảo hiểm như thế nào?'>x</a></h3>
                </div>
                <div class='article article_last' last-push='2026-03-18 12:00:00'>
                  <h3 class='article__title'><a href='/thue-thu-nhap-d2.html'
                    title='Mức giảm trừ thuế thu nhập cá nhân'>x</a></h3>
                </div>
                """;
        var vnf = parsers.parseVietnamFinanceLife(bytes(vietnamFinance),
                "https://vietnamfinance.vn/tai-chinh-tieu-dung/");
        check(vnf.size() == 1, "VietnamFinance rejects unrelated consumer-finance articles");
        check(vnf.get(0).publishedAt() != null, "VietnamFinance listing date retained");

        check(ContentParsers.isVietnamLifeInsuranceRelevant("Prudential Việt Nam công bố sản phẩm"),
                "named insurer accepted");
        check(!ContentParsers.isVietnamLifeInsuranceRelevant("Bảo hiểm y tế siết chi phí khám chữa bệnh"),
                "public health insurance noise rejected");
        check(!ContentParsers.isVietnamLifeInsuranceRelevant(
                        "PVI dẫn đầu doanh thu bảo hiểm phi nhân thọ Việt Nam"),
                "non-life company milestone rejected even when it mentions Vietnam insurance");

        String aiaVietnam = """
                <div class='cmp-promotioncard'><a class='cmp-promotioncard__link'
                  href='/vi/ve-chung-toi/truyen-thong/thong-cao-bao-chi/2026/aia-test.html'>
                  <div class='cmp-promotioncard__title'>AIA Việt Nam công bố kết quả</div>
                  <div class='cmp-promotioncard__date'>7/4/2026</div>
                </a></div>
                """;
        var aiaVietnamItems = parsers.parseAia(bytes(aiaVietnam), "https://www.aia.com.vn/");
        check(LocalDate.ofInstant(aiaVietnamItems.get(0).publishedAt(), ZoneId.of("Asia/Ho_Chi_Minh"))
                .equals(LocalDate.of(2026, 4, 7)),
                "AIA accepts non-zero-padded Vietnamese dates without year-only fallback");
        var vietnameseMetadata = DocumentMetadataDetector.html(bytes("""
                <html lang='vi'><head><title>Thông cáo sản phẩm</title></head>
                <body><article>TP. Hồ Chí Minh, ngày 03 tháng 04 năm 2026 – Nội dung chính.</article></body></html>
                """), "TP. Hồ Chí Minh, ngày 03 tháng 04 năm 2026 – Nội dung chính.",
                "Thông cáo sản phẩm", "https://insurer.example/news/product");
        check(LocalDate.of(2026, 4, 3).equals(vietnameseMetadata.publishedDate()),
                "generic metadata detector retains Vietnamese editorial dateline");

        String categoryNoise = """
                <article class='story'><h3 class='story__heading'><a href='/prudential.html'
                  title='Prudential Việt Nam ra mắt sản phẩm bảo hiểm liên kết'>P</a></h3></article>
                <article class='story'><h3 class='story__heading'><a href='/tax.html'
                  title='Doanh nghiệp cần làm gì trước chiến dịch thanh lọc mã số thuế'>T</a></h3></article>
                """;
        check(parsers.parseTnckInsurance(bytes(categoryNoise),
                "https://www.tinnhanhchungkhoan.vn/bao-hiem/").size() == 1,
                "TNCK excludes site-wide recommendations that reuse insurance-card markup");

        String vnEconomyNoise = """
                <article class='new-item_vertical'><h3><a href='/ai-insurance.htm'>
                  Ngành bảo hiểm Việt Nam được tái định hình bởi dữ liệu và AI</a></h3></article>
                <article class='new-item_horizontal'><h3><a href='/gold.htm'>
                  Giá vàng giảm gần một triệu đồng trong phiên</a></h3></article>
                """;
        check(parsers.parseVnEconomyInsurance(bytes(vnEconomyNoise),
                "https://vneconomy.vn/bao-hiem-tai-chinh.htm?page=1").size() == 1,
                "VnEconomy excludes generic finance rails before fetching article bodies");

        String tbtco = """
                <html><head><base href='https://thoibaotaichinhvietnam.vn/'></head><body>
                <header><h3 class='article-title'><a class='article-link' href='noise.html'>Noise</a></h3></header>
                <div class='bx-results'><div class='bx-cat-content __MB_LIST_ITEM'><div class='article'>
                  <h3 class='article-title'><a class='article-link' href='life-200858.html'
                    title='Đổi mới kênh phân phối bảo hiểm nhân thọ'>Life</a></h3>
                </div></div></div></body></html>
                """;
        var tbtcoItems = parsers.parseTbtcoLifeSearch(bytes(tbtco),
                "https://thoibaotaichinhvietnam.vn/search_enginer.html?p=search&q=x");
        check(tbtcoItems.size() == 1, "TBTCO parser excludes header headlines");
        check(tbtcoItems.get(0).link().endsWith("life-200858.html"),
                "TBTCO relative article URL resolved");

        String bizhub = """
                <div class='vnn-small-news'><div class='meta-data'>
                  <h3 class='meta-data-tit'><a href='/bancassurance-post1.html'>Bancassurance in Việt Nam</a></h3>
                  <time class='meta-data-source'>Tuesday, Jun 16, 2026</time>
                </div></div>
                """;
        var bizhubItems = parsers.parseBizhubInsurance(bytes(bizhub),
                "https://bizhub.vietnamnews.vn/insurance");
        check(bizhubItems.size() == 1 && bizhubItems.get(0).publishedAt() != null,
                "BizHub insurance card and English date parsed");

        String baoChinhPhu = """
                <div class='box-stream-item'><div class='box-stream-content'>
                  <a class='box-stream-link-title' href='/sua-luat-bao-hiem.htm'
                     title='Sửa Luật Kinh doanh bảo hiểm'>x</a>
                  <span class='box-stream-time'>09/07/2026 08:02</span>
                </div></div>
                """;
        var bcp = parsers.parseBaoChinhPhuInsurance(bytes(baoChinhPhu),
                "https://baochinhphu.vn/bao-hiem.html");
        check(bcp.size() == 1 && bcp.get(0).publishedAt() != null,
                "Báo Chính phủ policy card parsed");

        String baoChinhPhuNonLife = """
                <div class='box-stream-item'><div class='box-stream-content'>
                  <a class='box-stream-link-title' href='/lat-tau.htm'
                     title='Yêu cầu DN bảo hiểm hỗ trợ thiệt hại vụ lật tàu'>x</a>
                  <span class='box-stream-time'>20/07/2025 08:02</span>
                </div></div>
                """;
        try {
            parsers.parseBaoChinhPhuInsurance(bytes(baoChinhPhuNonLife),
                    "https://baochinhphu.vn/bao-hiem.html");
            throw new AssertionError("Failed: Báo Chính phủ vessel-casualty noise admitted");
        } catch (ContentParsers.ParseFailedException expected) {
            // Structurally valid card, intentionally no life/policy evidence.
        }

        String vnExpress = """
                <article class='item-news'><h2 class='title-news'>
                  <a href='https://vnexpress.net/manulife-ra-mat-san-pham-1.html'
                     title='Manulife ra mắt sản phẩm'>x</a>
                </h2></article>
                """;
        check(parsers.parseVnExpressInsurance(bytes(vnExpress),
                "https://vnexpress.net/kinh-doanh/bao-hiem").size() == 1,
                "VnExpress insurance archive parsed");

        String daiichi = """
                <div class='item-news'><h3 class='card-title-2'>
                  <a href='/hoat-dong-15/san-pham-1'>Dai-ichi ra mắt sản phẩm</a></h3>
                  <p class='publish_at'><span>01/08/2026</span></p></div>
                """;
        var daiichiItems = parsers.parseDaiichiVn(bytes(daiichi),
                "https://dai-ichi-life.com.vn/tin-tuc");
        check(daiichiItems.size() == 1 && daiichiItems.get(0).publishedAt() != null,
                "Dai-ichi server-rendered archive parsed");

        String phuHungDetail = """
                <script>window.globalData.newsDetailPage.detailNews = {"title":"Sản phẩm mới",
                  "date":"16.06.2026","editor":"<p>Quyền lợi bảo vệ chi tiết cho khách hàng.</p>"};</script>
                """;
        check(parsers.parsePhuHungDetail(bytes(phuHungDetail)).text().contains("Quyền lợi bảo vệ"),
                "Phú Hưng embedded detail text parsed");

        String tbnh = """
                <div id='article-1' class='article'><h3 class='article-title'>
                  <a class='article-link' href='/lai-suat.html'>Điều hành lãi suất ngân hàng</a></h3>
                  <span class='format_date'>01/08/2026</span></div>
                <div id='article-2' class='article'><h3 class='article-title'>
                  <a class='article-link' href='/bancassurance.html'>Tái cấu trúc hoạt động bancassurance</a></h3>
                  <span class='format_date'>02/08/2026</span></div>
                """;
        var tbnhItems = parsers.parseTbnh(bytes(tbnh), "https://thoibaonganhang.vn/");
        check(tbnhItems.size() == 1 && tbnhItems.get(0).title().contains("bancassurance"),
                "TBNH acquisition rejects non-insurance homepage noise");

        String hnx = """
                <div class='Box-Sukien'><div class='Box-Times'><p>16:31 07/07/2026</p></div>
                  <div class='Box-News'><a class='Box-News-Title'
                    href='/trung-tam-truyen-thong/chi-tiet-tin-bc-60023015-0.html'>
                    Thị trường trái phiếu Chính phủ tháng 6/2026: lợi suất và thanh khoản</a></div></div>
                <div class='Box-Sukien'><div class='Box-Times'><p>10:00 08/07/2026</p></div>
                  <div class='Box-News'><a class='Box-News-Title' href='/event.html'>HNX tổ chức hội nghị</a></div></div>
                """;
        var hnxItems = parsers.parseHnxGovernmentBondMonthly(bytes(hnx), "https://hnx.vn/vi-vn/media");
        check(hnxItems.size() == 1 && hnxItems.get(0).publishedAt() != null,
                "HNX retains dated monthly Government-bond evidence and rejects event noise");

        String sbv = """
                <div class='policy-title'><a class='policy-title-link' href='/w/rates'>
                  Diễn biến lãi suất của tổ chức tín dụng đối với khách hàng tháng 6/2026
                  <span class='policy-date'>(21/07/2026)</span></a></div>
                <div class='policy-title'><a class='policy-title-link' href='/w/admin'>
                  Thông báo tuyển dụng <span class='policy-date'>(20/07/2026)</span></a></div>
                """;
        var sbvItems = parsers.parseSbvMarketOperations(bytes(sbv), "https://www.sbv.gov.vn/");
        check(sbvItems.size() == 1 && sbvItems.get(0).publishedAt() != null,
                "SBV retains dated rate evidence and excludes administration news");

        String bizhubReader = """
                ### [Bancassurance operations undergo restructuring](https://bizhub.vietnamnews.vn/bancassurance-operations-undergo-restructuring-post405497.html)
                Tuesday, Jun 16, 2026 Banks are moving into long-term insurance services.

                ### [Cyber insurance gains urgency](https://bizhub.vietnamnews.vn/cyber-insurance-post402862.html)
                Friday, May 08, 2026 Cyber cover for businesses.

                ### [Data, AI reshape the future of insurance industry](https://bizhub.vietnamnews.vn/data-ai-insurance-post409535.html)
                Friday, Jul 24, 2026 Technology is changing customer journeys.

                ### [PVI Insurance hits $1 billion revenue first among Vietnamese insurers](https://bizhub.vietnamnews.vn/pvi-insurance-post394740.html)
                Friday, Jan 02, 2026 A non-life company milestone.
                """;
        var bizhubReaderItems = parsers.parseBizhubReaderListing(bytes(bizhubReader));
        check(bizhubReaderItems.size() == 2 && bizhubReaderItems.get(0).publishedAt() != null,
                "BizHub Reader keeps life/bancassurance and industry-tech, rejects P&C milestones");

        String bvReaderWithMarkdownTitle = """
                [Ra mắt sản phẩm mới](https://www.baovietnhantho.com.vn/tin-tuc/ra-mat-san-pham \"Ra mắt sản phẩm mới\")
                """;
        check(parsers.parseBaoVietReaderListing(bytes(bvReaderWithMarkdownTitle)).get(0).link()
                        .equals("https://www.baovietnhantho.com.vn/tin-tuc/ra-mat-san-pham"),
                "Bao Viet Reader strips optional Markdown link titles from the article URL");

        String investor = """
                <article><h3><a href='/techcom-life-ai-d19411.html'>
                  Techcom Life wins awards for AI insurance innovation</a></h3></article>
                <article><h3><a href='/steel-output-d19412.html'>Vietnam steel output rises</a></h3></article>
                """;
        check(parsers.parseTheInvestorInsurance(bytes(investor),
                "https://theinvestor.vn/insurance-search1/").size() == 1,
                "The Investor keeps relevant Vietnam insurer evidence only");

        String ddd = """
                <article><h2><a href='/techcom-life-bancassurance-10180494.html'>
                  Techcom Life giữ vững vị trí số 1 bancassurance</a></h2></article>
                <article><h2><a href='/tin-dung-ngan-hang-10180495.html'>Tăng trưởng tín dụng</a></h2></article>
                """;
        check(parsers.parseDddFinancialServices(bytes(ddd),
                "https://diendandoanhnghiep.vn/ngan-hang-chung-khoan/dich-vu-tai-chinh").size() == 1,
                "Diễn đàn Doanh nghiệp financial page is filtered before download");

        String sunReader = """
                21/07/2026

                [Thông báo cập nhật Sun Reward](https://www.sunlife.com.vn/vn/ve-chung-toi/tin-tuc-su-kien/2026/sun-reward/)
                [Page 2](https://www.sunlife.com.vn/vn/ve-chung-toi/tin-tuc-su-kien/2026/2/)
                """;
        var sunItems = parsers.parseSunLifeReaderListing(bytes(sunReader));
        check(sunItems.size() == 1 && sunItems.get(0).publishedAt() != null,
                "Sun Life Reader archive retains official URL and date");
        check(parsers.parseSunLifeReaderPagination(bytes(sunReader)).size() == 1,
                "Sun Life Reader pagination discovered");

        String bvReader = """
                [Chương trình Chăm sóc Khách hàng 2026](https://www.baovietnhantho.com.vn/tin-tuc/chuong-trinh-cham-soc-khach-hang-2026)
                [Sản phẩm](https://www.baovietnhantho.com.vn/tin-tuc/danh-muc/san-pham)
                """;
        check(parsers.parseBaoVietReaderListing(bytes(bvReader)).size() == 1,
                "Bao Viet Reader archive excludes category navigation");

        String bvHoldingsReader = """
                ### [Tập đoàn Bảo Việt (BVH): Lợi nhuận Quý I/2026 tăng trưởng 18,7%](https://www.baoviet.com.vn/vi/bao-viet-ket-qua-q1-2026)

                29.04.2026

                ### [Thông báo giao dịch cổ phiếu của người nội bộ](https://www.baoviet.com.vn/vi/giao-dich-co-phieu)

                15.05.2026
                """;
        var bvhItems = parsers.parseBaoVietHoldingsReaderListing(bytes(bvHoldingsReader));
        check(bvhItems.size() == 1 && bvhItems.get(0).publishedAt() != null,
                "Bao Viet Holdings retains financial evidence and rejects securities admin noise");

        String aiaGroup = """
                <div class='cmp-promotioncard' data-usage='press-release-nav'>
                  <a class='cmp-promotioncard__link'
                     href='/en/media-centre/press-releases/2026/aia-group-press-release-20260430'>
                    <div class='cmp-promotioncard__title'>AIA delivers Q1 growth</div>
                    <div class='cmp-promotioncard__date'>30 April 2026</div>
                  </a>
                </div>
                """;
        var aiaGroupItems = parsers.parseAiaGroupPress(bytes(aiaGroup),
                "https://www.aia.com/en/media-centre/press-releases");
        check(aiaGroupItems.size() == 1 && aiaGroupItems.get(0).publishedAt() != null,
                "AIA Group result card parsed with parent identity and date");

        String readerArticle = """
                Title: Sun Life Việt Nam ra mắt Bảo hiểm Hưu trí Sun Life
                URL Source: https://www.sunlife.com.vn/example
                Markdown Content:
                menu text that must be removed
                # Sun Life Việt Nam ra mắt Bảo hiểm Hưu trí Sun Life
                28/05/2026

                Sản phẩm giúp doanh nghiệp kiến tạo phúc lợi dài hạn và giữ chân nhân tài.
                Khách hàng doanh nghiệp có thể thiết kế mức đóng góp phù hợp cho từng nhóm nhân viên,
                trong khi người lao động được tích lũy cho tuổi hưu trí và nhận quyền lợi theo điều khoản.
                Nội dung công bố giải thích bối cảnh thiếu hụt phúc lợi dài hạn, vai trò của doanh nghiệp,
                cách thức đóng phí, tích lũy và chi trả. Đây là thông tin chính thức để so sánh cấu trúc
                sản phẩm, phân khúc khách hàng và mô hình phân phối tại thị trường Việt Nam.

                ### Truy cập nhanh
                footer noise
                """;
        var parsedReader = parsers.parseReaderArticle(bytes(readerArticle));
        check(parsedReader.publishedAt() != null && !parsedReader.text().contains("menu text"),
                "Reader detail strips navigation and retains article date");

        String readerBoundaryDate = """
                Title: Chương trình tri ân
                Markdown Content:
                navigation
                Khuyến mãi 31/07/2026
                # Chương trình tri ân khách hàng
                Chương trình được gia hạn đến hết ngày 30/09/2026. Nội dung đầy đủ mô tả
                đối tượng, điều kiện, địa điểm áp dụng và cách khách hàng nhận quyền lợi.
                Đây là phần nội dung bài viết đủ dài để bộ parser chấp nhận làm full text,
                trong khi ngày ở phía trên tiêu đề mới là ngày xuất bản chính thức.
                """;
        var boundaryDated = parsers.parseReaderArticle(bytes(readerBoundaryDate));
        check(LocalDate.ofInstant(boundaryDated.publishedAt(), ZoneId.of("Asia/Ho_Chi_Minh"))
                        .equals(LocalDate.of(2026, 7, 31)),
                "Reader boundary publication date wins over future programme end date");

        String cathay = """
                {"data":{"news":[{"news_id":981,"posted_at":"2026-07-01",
                 "content":"{\\"vi_VN\\":{\\"title\\":\\"Cathay công bố sản phẩm\\",\\"summary\\":\\"<p>Quyền lợi bảo vệ mới</p>\\"}}"}]}}
                """;
        var cathayItems = parsers.parseCathayVn(bytes(cathay), "https://www.cathaylife.com.vn/cathay/api/graphql");
        check(cathayItems.get(0).embeddedText().contains("Quyền lợi bảo vệ"),
                "Cathay GraphQL summary retained as content");

        String financialListing = """
                <div class='report-item'><a href='/docs/financial-statements-2025.pdf'
                   title='Báo cáo tài chính năm 2025'>Tải báo cáo</a><time>03/04/2026</time></div>
                <div><a href='/docs/fund-report-2025.pdf'>Báo cáo quỹ liên kết chung 2025</a></div>
                <div><a href='/docs/financial-statements-2023.pdf'>Báo cáo tài chính năm 2023</a></div>
                """;
        var financialItems = parsers.parseFinancialReportLinks(bytes(financialListing),
                "https://insurer.example/reports", "TEST_FINANCIALS");
        check(financialItems.size() == 1 && financialItems.get(0).publishedAt() != null,
                "Financial listing keeps current statutory PDF, date, and rejects fund/old reports");

        String contextualYearLeak = """
                <div class='report-item'><h2>Báo cáo tài chính 2025</h2>
                  <a href='/docs/BCTC-2024.pdf'>Báo cáo tài chính 2024</a>
                </div>
                """;
        try {
            parsers.parseFinancialReportLinks(bytes(contextualYearLeak),
                    "https://insurer.example/reports", "YEAR_LEAK");
            throw new AssertionError("Failed: old financial link inherited current section year");
        } catch (ContentParsers.ParseFailedException expected) {
            // expected: primary link/title year wins over surrounding current section
        }

        String embeddedFinancial = """
                <script>window.globalData.newsDetailPage.detailNews = {"editor":"<p><a href=\\"/media/full-report-2025.pdf\\">Báo cáo tài chính hợp nhất năm 2025</a></p>"};</script>
                """;
        check(parsers.parseFinancialReportLinks(bytes(embeddedFinancial),
                "https://www.phuhunglife.com/vn/report/", "PHU_HUNG_LIFE_FINANCIALS")
                .get(0).link().contains("full-report-2025.pdf"),
                "Financial parser extracts PDF anchors embedded in CMS script data");

        String aiaImageFinancial = """
                <html><head><title>Báo cáo tài chính Kết quả Kinh doanh năm 2025</title></head>
                <body><img alt='Báo cáo tài chính tóm tắt 2025'
                  src='https://s7ap1.scene7.com/is/image/aia/Bao-cao-tai-chinh-tom-tat-2025?qlt=85'></body></html>
                """;
        var aiaFinancials = parsers.parseFinancialReportLinks(bytes(aiaImageFinancial),
                "https://www.aia.com.vn/vi/ve-chung-toi/truyen-thong/bao-cao-tai-chinh/2025.html",
                "AIA_VN_FINANCIALS");
        check(aiaFinancials.size() == 1
                        && aiaFinancials.get(0).link().equals(
                        "https://s7ap1.scene7.com/is/image/aia/Bao-cao-tai-chinh-tom-tat-2025?fmt=pdf"),
                "AIA Scene7 summary is converted to the same official asset as OCR-ready PDF");

        String abbreviatedFinancial = """
                <a href='/docs/Financial_Results_2025_VN.pdf'>BC-KQKD 2025</a>
                """;
        check(parsers.parseFinancialReportLinks(bytes(abbreviatedFinancial),
                "https://www.manulife.com.vn/reports", "MANULIFE_VN_FINANCIALS").size() == 1,
                "Financial parser recognises official BC-KQKD abbreviation");

        String pdfWrapper = """
                <title>Báo cáo tài chính năm 2025</title>
                <iframe src='/pdfjs/web/viewer.html?file=/storage/Financial Statement 2025.pdf'></iframe>
                """;
        check(parsers.parseFinancialReportLinks(bytes(pdfWrapper),
                "https://insurer.example/view-file/1", "TEST_WRAPPER").get(0).link()
                .endsWith("Financial%20Statement%202025.pdf"),
                "PDF.js wrapper resolves the actual same-host statutory document");

        String generaliFlight = """
                self.__next_f.push([1,"{\\\"display_name\\\":\\\"Báo cáo tài chính năm 2025\\\",\\\"slug\\\":\\\"bao-cao-tai-chinh-nam-2025\\\",\\\"announce\\\":\\\"<p>Công bố ngày 26/03/2026</p>\\\"}"])
                """;
        var generaliFinancials = parsers.parseGeneraliFinancialLinks(bytes(generaliFlight),
                "https://generali.vn/page/company/bao-cao-tai-chinh");
        check(generaliFinancials.size() == 1 && generaliFinancials.get(0).publishedAt() != null,
                "Generali Next-flight report route and publication date parsed");

        String metlifeReports = """
                {"response":{"docs":[
                  {"file_url":"/reports/2025 Statutory Report.pdf","file_type":"PDF",
                   "file_uploaded":{"year":2026,"month":2,"dayOfMonth":31},
                   "file_title":"BÁO CÁO TÀI CHÍNH 2025"},
                  {"file_url":"/reports/2023.pdf","file_type":"PDF",
                   "file_title":"BÁO CÁO TÀI CHÍNH 2023"}]}}
                """;
        var metlifeFinancials = parsers.parseBidvMetlifeFinancials(bytes(metlifeReports),
                "https://www.bidvmetlife.com.vn/bin/fetchFormsLibrary");
        check(metlifeFinancials.size() == 1 && metlifeFinancials.get(0).publishedAt() != null
                        && metlifeFinancials.get(0).link().contains("%20"),
                "BIDV MetLife public forms API retains upload date and current report only");

        String financialReader = """
                ###### [BÁO CÁO TÀI CHÍNH 2025](https://www.baovietnhantho.com.vn/bao-cao-tai-chinh#)
                [Xem tại đây](https://www.baovietnhantho.com.vn/storage/BCTC-final-2025.pdf)
                [Báo cáo năm trước](https://www.baovietnhantho.com.vn/storage/BCTC-final-2024.pdf)
                _Ngày 02/4/2026_
                """;
        var readerFinancials = parsers.parseReaderFinancialLinks(bytes(financialReader),
                "www.baovietnhantho.com.vn", "BVNT_FINANCIALS");
        check(readerFinancials.size() == 1 && readerFinancials.get(0).publishedAt() != null,
                "Reader financial listing retains official PDF and explicit publisher date");

        String shinhanReader = """
                [Báo cáo Tài chính đầy đủ 2025](https://www.shinhanlifevn.com.vn/media/SHINHAN_LIFE_VIET_NAM_BCTC_2025.pdf)
                Đã đăng 06/04/2026
                [Báo cáo Tài chính 2024](https://www.shinhanlifevn.com.vn/media/Financial_Report_2024.pdf)
                """;
        var shinhanFinancials = parsers.parseShinhanFinancialReader(bytes(shinhanReader));
        check(shinhanFinancials.size() == 1 && shinhanFinancials.get(0).publishedAt() != null,
                "Shinhan flat Reader archive retains current official PDF and date");

        String shinhanDetail = """
                {"data":{"listSitePost":[{"titleVn":"Shinhan ra mắt sản phẩm mới",
                "publishedDate":"2026-01-20T17:00:00.000+00:00",
                "contentVn":"<p>TP. Hồ Chí Minh, ngày 03 tháng 04 năm 2026 – Shinhan Life Việt Nam ra mắt giải pháp bảo vệ.</p><p>%s</p>"}]}}
                """.formatted("Nội dung quyền lợi và điều kiện sản phẩm. ".repeat(20));
        var shinhanArticle = parsers.parseShinhanVnDetail(bytes(shinhanDetail),
                "https://www.shinhanlifevn.com.vn/shinhan-ra-mat-san-pham-moi");
        check(shinhanArticle.embeddedText().length() > 300,
                "Shinhan detail API body retained instead of shared SPA shell");
        check(LocalDate.ofInstant(shinhanArticle.publishedAt(), ZoneId.of("Asia/Ho_Chi_Minh"))
                        .equals(LocalDate.of(2026, 4, 3)),
                "Shinhan editorial dateline wins over stale CMS creation date");

        String mofDirect = """
                {"data":{"title":"Khuyến nghị về AI, dữ liệu và an ninh mạng cho ngành bảo hiểm",
                 "publicationTime":"2026-04-03T03:48:05.584",
                 "articleContent":"{\\\"Content\\\":\\\"<p>%s</p>\\\"}"}}
                """.formatted("Doanh nghiệp bảo hiểm cần đầu tư vào công nghệ, dữ liệu và an ninh mạng. ".repeat(16));
        var mofDirectArticle = parsers.parseMofDirectArticle(bytes(mofDirect),
                "https://vidi.mof.gov.vn/vien-phat-trien-bao-hiem-viet-nam/nghien-cuu-trao-doi/cyber-2026");
        check(mofDirectArticle.publishedAt() != null
                        && mofDirectArticle.embeddedText().contains("an ninh mạng"),
                "MOF direct research API retains dated full text and official browse URL");

        String fwdNext = """
                {"url":"https://assets.contentstack.io/v3/assets/a/b/c/Bao-cao-tai-chinh-FWD-2025.pdf",
                 "old":"https://assets.contentstack.io/v3/assets/a/b/c/FWD-Bao-cao-tai-chinh-2024.pdf"}
                """;
        check(parsers.parseFwdFinancialLinks(bytes(fwdNext)).size() == 1,
                "FWD payload keeps only current official CDN statutory report");

        String techcomIr = """
                <a class='list-row-content_item' href='/documents/2q26-press-release-vie.pdf'>
                  <h3 class='list-row-content_title'>Cập nhật KQKD 6 tháng đầu năm 2026</h3>
                  <span class='color-gray'>2026-06-30T00:00:00+07:00</span></a>
                <a class='list-row-content_item' href='/documents/annual-report-2025.pdf'>
                  <h3 class='list-row-content_title'>Báo cáo thường niên 2025</h3></a>
                """;
        var techcomResults = parsers.parseTechcombankLifeResults(bytes(techcomIr),
                "https://techcombank.com/nha-dau-tu");
        check(techcomResults.size() == 1 && techcomResults.get(0).publishedAt() != null,
                "Techcombank IR keeps current quarterly result PDF only");

        System.out.println("VietnamAcquisitionParsersTest: ALL PASS");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError("Failed: " + message);
    }
}
