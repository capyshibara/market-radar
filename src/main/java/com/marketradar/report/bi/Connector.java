package com.marketradar.report.bi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Connector — gộp {@link BiFinding} đã qua Router (bucket/subjectKey) thành nhóm TƯỜNG MINH,
 * gắn với đúng vị trí thật trên report — thay cho việc {@code BiReportPageBuilder} tự
 * {@code groupingBy(...)} ẩn ngay lúc render (không audit được "section này do fact nào đóng
 * góp"). Cùng triết lý {@code MarketEventClustering}: pure code, deterministic, KHÔNG LLM,
 * không suy đoán ngữ nghĩa — chỉ gộp theo khoá đã có sẵn (bucket, subjectKey).
 *
 * 2 việc tách biệt:
 *  1. {@link #groupByBucketAndSubject} — gộp trong CÙNG 1 bucket theo subjectKey (dùng cho
 *     COMPANY_HIGHLIGHT/COMPARISON — đúng logic BiReportPageBuilder đã làm, nay tách ra đây
 *     để dùng chung + test độc lập được).
 *  2. {@link #proposeDeepDiveCandidates} — gộp XUYÊN BUCKET theo subjectKey, đề xuất ứng viên
 *     DEEP_DIVE (file mẫu CFO: slide "Deep Dive" luôn tổng hợp NHIỀU fact — có thể khác bucket
 *     nhau — thành 1 luận điểm, vd bảng thị phần + danh sách giải thưởng → suy ra ai vắng mặt).
 *     Chỉ ĐỀ XUẤT nhóm — viết narrative thật là việc của Analyst (bucket DEEP_DIVE riêng), không
 *     phải việc của Connector.
 */
public final class Connector {

    private Connector() {}

    /** Nhóm đủ material để trở thành 1 trang riêng (COMPANY_HIGHLIGHT) thay vì rơi vào bảng chung. */
    public static final int MIN_HIGHLIGHT_GROUP_SIZE = 2;

    /** Ngưỡng để 1 chủ thể (subjectKey) được đề xuất DEEP_DIVE: đủ NHIỀU fact CÙNG bucket
     *  (nhiều dữ kiện về cùng 1 việc, đáng phân tích sâu hơn 1-2 câu), HOẶC fact đến từ
     *  ≥2 bucket khác nhau (tín hiệu tương quan xuyên chủ đề — đúng mẫu slide "Insurance Asia
     *  Awards": bảng thị phần + danh sách giải thưởng cùng nói về 1 chủ đề). */
    public static final int DEEP_DIVE_MIN_SAME_BUCKET_MEMBERS = 3;
    public static final int DEEP_DIVE_MIN_CROSS_BUCKET_COUNT = 2;

    /** 1 nhóm finding cùng (bucket, subjectKey) — key rỗng/subjectKey null thì KHÔNG gộp (mỗi
     *  finding là 1 nhóm riêng, đúng hành vi cũ cho MACRO_ECONOMIC/so sánh chưa rõ cặp). */
    public record Group(String bucket, String subjectKey, List<BiFinding> members) {
        public boolean bigEnoughForOwnPage() { return members.size() >= MIN_HIGHLIGHT_GROUP_SIZE; }
    }

    /** 1 ứng viên DEEP_DIVE — gộp XUYÊN bucket theo subjectKey, kèm lý do được đề xuất (audit). */
    public record DeepDiveCandidate(String subjectKey, List<BiFinding> members, Set<String> sourceBuckets) {
        public String reason() {
            return sourceBuckets.size() >= DEEP_DIVE_MIN_CROSS_BUCKET_COUNT
                    ? "Xuyên " + sourceBuckets.size() + " chủ đề khác nhau cùng nói về \"" + subjectKey + "\""
                    : members.size() + " fact liên quan cùng chủ đề \"" + subjectKey + "\"";
        }
    }

    /** Gộp CÙNG 1 bucket theo subjectKey — bỏ qua finding không có subjectKey (giữ nguyên,
     *  không ép vào nhóm nào). Thứ tự nhóm = thứ tự subjectKey xuất hiện lần đầu. */
    public static List<Group> groupByBucketAndSubject(List<BiFinding> findings, String bucket) {
        Map<String, List<BiFinding>> bySubject = new LinkedHashMap<>();
        for (BiFinding f : findings) {
            if (!bucket.equals(f.bucket())) continue;
            if (f.subjectKey() == null || f.subjectKey().isBlank()) continue;
            bySubject.computeIfAbsent(f.subjectKey(), k -> new ArrayList<>()).add(f);
        }
        List<Group> groups = new ArrayList<>();
        bySubject.forEach((subject, members) -> groups.add(new Group(bucket, subject, List.copyOf(members))));
        return groups;
    }

    /** Đề xuất ứng viên DEEP_DIVE — gộp TẤT CẢ finding có subjectKey (bỏ qua MACRO_ECONOMIC vì
     *  không gắn 1 chủ thể, và DEEP_DIVE/STRATEGIC_COMPARISON vì đã là trang đào sâu riêng) theo
     *  subjectKey xuyên bucket, chỉ giữ nhóm đạt ngưỡng material thật (không đề xuất tràn lan). */
    public static List<DeepDiveCandidate> proposeDeepDiveCandidates(List<BiFinding> findings) {
        Map<String, List<BiFinding>> bySubject = new LinkedHashMap<>();
        for (BiFinding f : findings) {
            if (f.subjectKey() == null || f.subjectKey().isBlank()) continue;
            if (BiFinding.MACRO_ECONOMIC.equals(f.bucket())
                    || BiFinding.DEEP_DIVE.equals(f.bucket())
                    || BiFinding.STRATEGIC_COMPARISON.equals(f.bucket())) continue;
            bySubject.computeIfAbsent(f.subjectKey(), k -> new ArrayList<>()).add(f);
        }
        List<DeepDiveCandidate> candidates = new ArrayList<>();
        bySubject.forEach((subject, members) -> {
            Set<String> buckets = new LinkedHashSet<>();
            members.forEach(f -> buckets.add(f.bucket()));
            boolean qualifies = members.size() >= DEEP_DIVE_MIN_SAME_BUCKET_MEMBERS
                    || buckets.size() >= DEEP_DIVE_MIN_CROSS_BUCKET_COUNT;
            if (qualifies) candidates.add(new DeepDiveCandidate(subject, List.copyOf(members), Set.copyOf(buckets)));
        });
        return candidates;
    }
}
