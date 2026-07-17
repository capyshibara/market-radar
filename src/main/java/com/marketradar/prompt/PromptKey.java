package com.marketradar.prompt;

/**
 * Batch 12: danh mục các prompt AI có thể XEM + SỬA trực tiếp từ ops console (/prompts).
 * Mỗi stage đăng ký prompt mặc định của mình với PromptService lúc khởi động; ops có thể
 * ghi đè runtime (lưu DB, áp dụng ngay không cần khởi động lại). Nhãn/mô tả hiển thị cho ops.
 */
public enum PromptKey {
    CLASSIFY("Phân loại chủ đề (AI#1)",
            "Quyết định một tài liệu có thuộc ngành bảo hiểm nhân thọ không và gán nhãn category. Lọc bỏ tin ngoài ngành (ngân hàng/chứng khoán)."),
    EXTRACT("Trích evidence (AI#2)",
            "Trích các đoạn NGUYÊN VĂN + tóm tắt digest từ tài liệu. Càng giàu dữ kiện (số, ngày, tên) càng dễ viết insight sắc."),
    INTERPRET_DOC("Diễn giải tài liệu (AI#3)",
            "Với mỗi tài liệu: viết câu 'why' (sự việc cụ thể) + 'implication' (hàm ý nghiệp vụ). Đây là nguyên liệu cho tóm tắt điều hành + chương."),
    INTERPRET_EXEC("Tóm tắt điều hành",
            "Tổng hợp toàn kỳ thành vài câu briefing (kể chuyện trước, kết luận sau)."),
    INTERPRET_NARRATIVE("Tổng hợp chương (bài phân tích)",
            "Viết bài phân tích liền mạch cho mỗi chương của báo cáo tháng/quý — kể chuyện, có mạch, có từ nối, gắn hàm ý cho từng phòng ban."),
    PRODUCT_INSIGHT("Product insight có cấu trúc",
            "Viết What / comparison-pattern / So what / Product-owned Now what / caveat song ngữ từ evidence pack Product; JSON schema đóng, không thêm fact."),
    STORY_EXPLAIN("Kể lại câu chuyện nguồn (song ngữ)",
            "Viết lại một bài nguồn bằng ngôn ngữ đời thường EN + VI kèm giải thích thuật ngữ, CHỈ dùng nội dung đã lưu; là lớp hỗ trợ đọc, không phải bằng chứng."),
    VERIFY("Kiểm chứng entailment (Gate L2)",
            "Xét một claim có được evidence hậu thuẫn không (ENTAILED/CONTRADICTED/NEUTRAL). PHẢI khác họ model với writer.");

    public final String label;
    public final String description;

    PromptKey(String label, String description) {
        this.label = label;
        this.description = description;
    }
}
