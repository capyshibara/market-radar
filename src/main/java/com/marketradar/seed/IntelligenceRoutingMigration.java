package com.marketradar.seed;

import com.marketradar.classify.Router;
import com.marketradar.domain.Category;
import com.marketradar.domain.Classification;
import com.marketradar.domain.Department;
import com.marketradar.domain.RoutingRule;
import com.marketradar.repo.ClassificationRepository;
import com.marketradar.repo.RoutingRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Curated topic-to-audience lens; adding a department does not change extraction. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 12)
public class IntelligenceRoutingMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IntelligenceRoutingMigration.class);
    private static final Map<Category, Set<Department>> ROUTES = Map.ofEntries(
            Map.entry(Category.MACRO_ECONOMIC, EnumSet.of(Department.STRATEGY)),
            Map.entry(Category.INDUSTRY_REGULATION, EnumSet.of(Department.STRATEGY, Department.COMPLIANCE, Department.PRODUCT)),
            Map.entry(Category.MARKET_STRUCTURE, EnumSet.of(Department.STRATEGY, Department.PRODUCT, Department.SALES)),
            Map.entry(Category.COMPANY_FINANCIAL_PERFORMANCE, EnumSet.of(Department.STRATEGY)),
            Map.entry(Category.CORPORATE_ACTION, EnumSet.of(Department.STRATEGY, Department.SALES)),
            Map.entry(Category.TECHNOLOGY_AI, EnumSet.of(Department.STRATEGY, Department.PRODUCT)),
            Map.entry(Category.CUSTOMER_EXPERIENCE, EnumSet.of(Department.STRATEGY, Department.PRODUCT, Department.SALES)),
            Map.entry(Category.PEOPLE_TALENT, EnumSet.of(Department.STRATEGY)),
            Map.entry(Category.BRAND_REPUTATION, EnumSet.of(Department.STRATEGY, Department.SALES)),
            Map.entry(Category.STRATEGIC_RESEARCH, EnumSet.of(Department.STRATEGY, Department.PRODUCT)));

    private final RoutingRuleRepository rules;
    private final ClassificationRepository classifications;
    private final Router router;

    public IntelligenceRoutingMigration(RoutingRuleRepository rules,
                                        ClassificationRepository classifications,
                                        Router router) {
        this.rules = rules;
        this.classifications = classifications;
        this.router = router;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Category> addedCategories = new ArrayList<>();
        int added = 0;
        for (var entry : ROUTES.entrySet()) {
            for (Department department : entry.getValue()) {
                if (!rules.existsByCategoryAndDepartment(entry.getKey(), department)) {
                    rules.save(new RoutingRule(entry.getKey(), department, false));
                    added++;
                    addedCategories.add(entry.getKey());
                }
            }
        }
        if (added == 0) return;
        List<Classification> affected = classifications.findAllForDisplay().stream()
                .filter(c -> c.getStatus() == Classification.Status.CONFIRMED)
                .filter(c -> c.getLabels().stream().anyMatch(ROUTES::containsKey))
                .toList();
        affected.forEach(router::route);
        classifications.saveAll(affected);
        log.info("Added {} curated intelligence routing rule(s); re-routed {} classification(s)",
                added, affected.size());
    }
}
