package com.marketradar.llm;

/**
 * Sửa JSON có dấu ngoặc kép " CHƯA ESCAPE bên trong string value — lỗi thường gặp khi
 * prompt yêu cầu model chép nguyên văn / bọc tên riêng bằng "..." (đúng cấu trúc JSON
 * string cũng dùng ký tự "). Quan sát thật (2026-07-13): dù prompt đã nhắc rõ phải escape
 * kèm ví dụ, model VẪN thỉnh thoảng quên — có response escape ĐÚNG ở text_en nhưng QUÊN
 * escape ở text_vi cho CÙNG một thuật ngữ, trong CÙNG một response. Prompt-only không đủ
 * tin cậy 100% → cần lưới an toàn ở tầng code.
 *
 * CHỈ dùng làm fallback khi parse strict thất bại (gọi sau, không đụng response đã hợp lệ).
 * Dựa trên quy tắc JSON THẬT (không phải đoán mò): một dấu " ĐÓNG string hợp lệ luôn được
 * theo sau (bỏ qua khoảng trắng) bởi : , } hoặc ] — dấu " nào không thoả điều này trong
 * lúc đang ở giữa string thì chắc chắn là NỘI DUNG, phải escape. Nếu đoán sai (hiếm), kết
 * quả xấu nhất là JSON vẫn không parse được → vẫn SCHEMA_REJECTED như cũ, không tạo ra
 * dữ liệu sai lặng lẽ.
 *
 * Ca khó thật gặp phải: dấu " kết thúc MỘT CỤM TRÍCH DẪN TỰ NHIÊN trong câu tiếng Việt
 * thường theo ngay sau bởi dấu phẩy (vd `"PNJ", cho thấy…`) — giống hệt mẫu JSON
 * `"value", "nextKey"`. Vì vậy riêng trường hợp theo sau là dấu phẩy, phải nhìn thêm
 * MỘT bước nữa: chỉ coi là ĐÓNG THẬT nếu ngay sau dấu phẩy (bỏ qua khoảng trắng) là một
 * dấu " khác (mở key/phần tử tiếp theo) — nếu không, đó vẫn là dấu phẩy tự nhiên trong câu.
 */
public final class JsonRepair {

    private JsonRepair() {}

    public static String repairUnescapedQuotes(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 16);
        boolean insideString = false;
        int n = raw.length();
        for (int i = 0; i < n; i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < n) {
                out.append(c).append(raw.charAt(i + 1));
                i++;
                continue;
            }
            if (c == '"') {
                if (!insideString) {
                    insideString = true;
                    out.append(c);
                } else if (closesString(raw, i, n)) {
                    insideString = false;
                    out.append(c);
                } else {
                    out.append("\\\"");
                }
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /** true nếu dấu " tại vị trí i thực sự là dấu ĐÓNG string theo cấu trúc JSON. */
    private static boolean closesString(String raw, int i, int n) {
        int j = i + 1;
        while (j < n && Character.isWhitespace(raw.charAt(j))) j++;
        if (j >= n) return true;
        char next = raw.charAt(j);
        if (next == ':' || next == '}' || next == ']') return true;
        if (next == ',') {
            int k = j + 1;
            while (k < n && Character.isWhitespace(raw.charAt(k))) k++;
            return k < n && raw.charAt(k) == '"';
        }
        return false;
    }
}
