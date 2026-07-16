package com.marketradar.product;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/** Topic-grouped current facts for a scannable Product report. */
public record CurrentProductNewsGroup(CurrentProductNewsTopic topic,
                                      List<CurrentProductNewsItem> items) {
    public CurrentProductNewsGroup {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public String getLabelEn() { return topic.getLabelEn(); }
    public String getLabelVi() { return topic.getLabelVi(); }
    public String getReviewQuestionEn() { return topic.getReviewQuestionEn(); }
    public String getReviewQuestionVi() { return topic.getReviewQuestionVi(); }
    public String getValidationStepEn() { return topic.getValidationStepEn(); }
    public String getValidationStepVi() { return topic.getValidationStepVi(); }
    public int getItemCount() { return items.size(); }

    public static List<CurrentProductNewsGroup> from(List<CurrentProductNewsItem> news) {
        if (news == null || news.isEmpty()) return List.of();
        EnumMap<CurrentProductNewsTopic, List<CurrentProductNewsItem>> grouped =
                new EnumMap<>(CurrentProductNewsTopic.class);
        for (CurrentProductNewsItem item : news) {
            if (item == null || item.topic() == null) continue;
            grouped.computeIfAbsent(item.topic(), ignored -> new ArrayList<>()).add(item);
        }
        List<CurrentProductNewsGroup> result = new ArrayList<>();
        for (CurrentProductNewsTopic topic : CurrentProductNewsTopic.values()) {
            List<CurrentProductNewsItem> items = grouped.get(topic);
            if (items != null && !items.isEmpty()) result.add(new CurrentProductNewsGroup(topic, items));
        }
        return List.copyOf(result);
    }
}
