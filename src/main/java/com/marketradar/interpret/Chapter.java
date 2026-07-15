package com.marketradar.interpret;

import com.marketradar.domain.EvidenceFact;

import java.util.EnumSet;

/**
 * Batch 10 (narrative rewrite): 3 chương của Monthly Highlight — trước đây định nghĩa
 * inline, rời rạc, trong MonthlyReportController.monthly() và lặp lại ở chapter narrative
 * synthesis; hợp nhất về MỘT enum để controller và pipeline không lệch nhau.
 */
public enum Chapter {
    VN_COMPETITOR("01",
            "Việt Nam — Động thái đối thủ", "Vietnam — Competitor Moves",
            "Sản phẩm, phí, kênh phân phối của các công ty trên cùng thị trường",
            "Products, fees and distribution moves from companies in our market",
            "VN", EnumSet.of(EvidenceFact.FactType.PRODUCT_LAUNCH, EvidenceFact.FactType.FEE_CHANGE,
                    EvidenceFact.FactType.EVENT, EvidenceFact.FactType.METRIC)),
    VN_REGULATION("02",
            "Quy định trong nước", "Domestic Regulation",
            "Diễn biến pháp lý ảnh hưởng thiết kế và phân phối sản phẩm tại Việt Nam",
            "Regulatory developments affecting product design and distribution in Vietnam",
            "VN", EnumSet.of(EvidenceFact.FactType.REGULATION)),
    REGIONAL_LESSONS("03",
            "Khu vực — Bài học & Cảm hứng", "Regional — Lessons & Inspiration",
            "Không phải đối thủ trực tiếp: đọc để lấy ý tưởng sản phẩm, quy trình và mô hình vận hành",
            "Not direct competitors: read for product ideas, process and operating-model inspiration",
            "REGIONAL", EnumSet.allOf(EvidenceFact.FactType.class));

    public final String number;
    public final String titleVi;
    public final String titleEn;
    public final String subtitleVi;
    public final String subtitleEn;
    public final String market;
    public final EnumSet<EvidenceFact.FactType> factTypes;

    Chapter(String number, String titleVi, String titleEn, String subtitleVi, String subtitleEn,
            String market, EnumSet<EvidenceFact.FactType> factTypes) {
        this.number = number;
        this.titleVi = titleVi;
        this.titleEn = titleEn;
        this.subtitleVi = subtitleVi;
        this.subtitleEn = subtitleEn;
        this.market = market;
        this.factTypes = factTypes;
    }

    public String title(boolean vi) { return vi ? titleVi : titleEn; }
    public String subtitle(boolean vi) { return vi ? subtitleVi : subtitleEn; }

    /**
     * Batch 10 (feedback reader 2026-07-15): góc nhìn RIÊNG của từng chương, đưa vào prompt
     * narrative để model không viết chung chung. Nguyên nhân bản đầu nhạt: prompt generic +
     * pack quá rộng → model né chi tiết, viết "cho thấy sức mạnh và bền vững" áp cho bất kỳ ai.
     */
    public String narrativeFocusVi() {
        return switch (this) {
            case VN_COMPETITOR -> """
                Đây là chương ĐỘNG THÁI ĐỐI THỦ. DẪN DẮT bằng hành động CỤ THỂ của TỪNG công ty
                được nêu đích danh trong evidence (ra mắt sản phẩm, đổi phí, hợp tác phân phối,
                giải thưởng) — vd 'Prudential', 'Generali', 'Chubb Life', 'BIDV MetLife', 'AIA',
                'FWD'. Nêu tên công ty + hành động + con số trong CÀNG NHIỀU câu càng tốt. Số liệu
                doanh thu toàn ngành CHỈ là bối cảnh ngắn, KHÔNG được chiếm cả chương.
                CHỨC NĂNG LIÊN QUAN — rút hàm ý CHO CHÚNG TA (không phải cho đối thủ): THIẾT KẾ
                SẢN PHẨM CỦA CHÚNG TA nên phản ứng thế nào trước sản phẩm/quyền lợi đối thủ vừa ra
                mắt, ĐỊNH PHÍ/ACTUARY CỦA CHÚNG TA có cần điều chỉnh phí/quyền lợi để cạnh tranh,
                KÊNH PHÂN PHỐI CỦA CHÚNG TA nên cân nhắc hợp tác/đối trọng thế nào, MARKETING CỦA
                CHÚNG TA nên định vị lại ra sao. KHÔNG viết hàm ý như thể đối thủ tự nhắc nhở
                chính họ.""";
            case VN_REGULATION -> """
                Đây là chương QUY ĐỊNH TRONG NƯỚC. Với mỗi quy định: nói RÕ nó YÊU CẦU điều gì và
                RÀNG BUỘC AI, và ảnh hưởng CỤ THỂ tới thiết kế/phân phối sản phẩm bảo hiểm. TUYỆT
                ĐỐI không trình bày điều khoản KHUYẾN MÃI của một công ty hay quy định CHỈ dành cho
                ngân hàng như thể là quy định bảo hiểm — giữ đúng bản chất của từng fact.
                CHỨC NĂNG LIÊN QUAN — rút hàm ý CHO CHÚNG TA: PHÁP CHẾ/TUÂN THỦ CỦA CHÚNG TA có
                nghĩa vụ mới gì, thời hạn nào phải hoàn thành; THIẾT KẾ SẢN PHẨM CỦA CHÚNG TA có
                điều khoản/hợp đồng nào phải chỉnh; KÊNH PHÂN PHỐI CỦA CHÚNG TA có quy trình đại
                lý/tư vấn/bán chéo nào phải rà soát. Nêu rõ MỐC THỜI GIAN hiệu lực khi evidence có.""";
            case REGIONAL_LESSONS -> """
                Đây là chương BÀI HỌC KHU VỰC (không phải đối thủ trực tiếp). Nêu ĐÍCH DANH công ty/
                cơ quan khu vực + thực tiễn hoặc con số CỤ THỂ (vd 'Munich Re' RoE 24,2%, mô hình
                propensity của 'Pru Life UK'), rồi rút bài học rõ ràng cho doanh nghiệp BHNT Việt
                Nam. CẤM câu chung chung kiểu 'lợi nhuận cao cho thấy sức mạnh và bền vững' — phải
                gắn với con số/tên thật trong evidence.
                CHỨC NĂNG LIÊN QUAN — rút bài học tham khảo CHO CHÚNG TA (công ty BHNT Việt Nam):
                THIẾT KẾ SẢN PHẨM & DỊCH VỤ GIA TĂNG CỦA CHÚNG TA có thể học ý tưởng sản phẩm/
                healthcare/số hóa gì, VẬN HÀNH & ACTUARY CỦA CHÚNG TA có thể tham khảo mô hình định
                phí/quản trị rủi ro nào, PHÂN PHỐI CỦA CHÚNG TA có thể học mô hình kênh nào — nêu
                rõ 'chúng ta có thể áp dụng gì', không phải công ty khu vực tự nhắc nhở chính họ.""";
        };
    }

    /** Có nhận fact này vào chương không: đúng market VÀ đúng factType. */
    public boolean matches(EvidenceFact f) {
        return market.equals(com.marketradar.extract.FactExtractionJob.market(f.getRawDoc()))
                && factTypes.contains(f.getFactType());
    }
}
