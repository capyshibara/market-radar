package com.marketradar.product;

import java.util.ArrayList;
import java.util.List;

/** Domestic/international report partition with topic groups inside each market. */
public record CurrentProductNewsScopeGroup(ProductMarketScope scope,
                                           List<CurrentProductNewsItem> items,
                                           List<CurrentProductNewsGroup> topicGroups) {
    public CurrentProductNewsScopeGroup {
        items = items == null ? List.of() : List.copyOf(items);
        topicGroups = topicGroups == null ? List.of() : List.copyOf(topicGroups);
    }

    public boolean isVietnam() { return scope == ProductMarketScope.VIETNAM; }
    public int getItemCount() { return items.size(); }
    public long getSourceCount() {
        return items.stream().map(CurrentProductNewsItem::sourceCode).distinct().count();
    }

    public String getLabelEn() {
        return isVietnam() ? "Vietnam market moves" : "International product signals";
    }

    public String getLabelVi() {
        return isVietnam() ? "Diễn biến thị trường Việt Nam" : "Tín hiệu sản phẩm quốc tế";
    }

    public String getDescriptionEn() {
        return isVietnam()
                ? "Domestic regulation, insurers, products and distribution changes that can require action now."
                : "Overseas propositions, operating models and market signals to evaluate—not copy directly.";
    }

    public String getDescriptionVi() {
        return isVietnam()
                ? "Quy định, doanh nghiệp, sản phẩm và thay đổi phân phối trong nước có thể cần hành động ngay."
                : "Đề xuất, mô hình vận hành và tín hiệu nước ngoài để đánh giá—không sao chép trực tiếp.";
    }

    public static List<CurrentProductNewsScopeGroup> from(List<CurrentProductNewsItem> news) {
        List<CurrentProductNewsItem> safe = news == null ? List.of() : news;
        List<CurrentProductNewsScopeGroup> result = new ArrayList<>();
        for (ProductMarketScope scope : ProductMarketScope.values()) {
            List<CurrentProductNewsItem> scoped = safe.stream()
                    .filter(item -> item != null && item.marketScope() == scope).toList();
            result.add(new CurrentProductNewsScopeGroup(scope, scoped,
                    CurrentProductNewsGroup.from(scoped)));
        }
        return List.copyOf(result);
    }
}
