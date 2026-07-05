package com.marketradar.classify;

import org.springframework.stereotype.Service;
import com.marketradar.domain.Category;
import com.marketradar.domain.Classification;
import com.marketradar.domain.Department;
import com.marketradar.domain.RoutingRule;
import com.marketradar.repo.RoutingRuleRepository;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * B3 — Routing category → phòng ban.
 * Invariant #4: CHỈ tra bảng deterministic. Không similarity, không AI.
 * Audit được: "tin tới SALES vì PRODUCT_LAUNCH map SALES trong routing_rules".
 * Fail-path: classification chưa CONFIRMED → không route (HELD_FOR_REVIEW);
 * category không có rule nào → ADMIN_QUEUE.
 */
@Service
public class Router {

    private final RoutingRuleRepository rules;

    public Router(RoutingRuleRepository rules) { this.rules = rules; }

    public void route(Classification c) {
        if (c.getStatus() != Classification.Status.CONFIRMED) {
            c.applyRouting(EnumSet.noneOf(Department.class),
                    Classification.RoutingStatus.HELD_FOR_REVIEW,
                    "Không route vì classification ở trạng thái " + c.getStatus());
            return;
        }

        Set<Department> depts = EnumSet.noneOf(Department.class);
        StringBuilder unmapped = new StringBuilder();
        for (Category cat : c.getLabels()) {
            List<RoutingRule> found = rules.findByCategory(cat);
            if (found.isEmpty()) {
                if (unmapped.length() > 0) unmapped.append(", ");
                unmapped.append(cat.name());
            } else {
                found.forEach(r -> depts.add(r.getDepartment()));
            }
        }

        if (unmapped.length() > 0) {
            // Có category không có trong bảng tra → toàn bộ doc vào hàng đợi admin (fail loud)
            c.applyRouting(depts, Classification.RoutingStatus.ADMIN_QUEUE,
                    "Category chưa có rule: " + unmapped + " — cần admin bổ sung bảng tra");
        } else {
            c.applyRouting(depts, Classification.RoutingStatus.ROUTED,
                    "Route theo bảng tra: " + c.getLabels() + " → " + depts);
        }
    }
}
