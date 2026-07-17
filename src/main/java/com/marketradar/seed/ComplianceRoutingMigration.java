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

import java.util.List;

/**
 * Thêm rule PRODUCT_REGULATION → COMPLIANCE cho database cũ (SeedData chỉ chạy khi
 * DB trống). Căn cứ dữ liệu 2026-07-17: corpus có 222 evidence fact loại REGULATION
 * nhưng routing hiện tại chỉ đưa chúng đến PRODUCT.
 *
 * Additive + deterministic: chỉ thêm rule còn thiếu, rồi chạy lại {@link Router}
 * (bảng tra thuần túy, không AI) cho các classification CONFIRMED có nhãn
 * PRODUCT_REGULATION để phòng Pháp chế nhìn thấy tin cũ. Không sửa nhãn, trạng thái
 * review hay nội dung nào khác.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 11)
public class ComplianceRoutingMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ComplianceRoutingMigration.class);

    private final RoutingRuleRepository rules;
    private final ClassificationRepository classifications;
    private final Router router;

    public ComplianceRoutingMigration(RoutingRuleRepository rules,
                                      ClassificationRepository classifications,
                                      Router router) {
        this.rules = rules;
        this.classifications = classifications;
        this.router = router;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (rules.count() == 0) return; // fresh DB: SeedData owns the initial table
        if (rules.existsByCategoryAndDepartment(Category.PRODUCT_REGULATION, Department.COMPLIANCE)) {
            return;
        }
        rules.save(new RoutingRule(Category.PRODUCT_REGULATION, Department.COMPLIANCE));
        List<Classification> affected =
                classifications.findConfirmedByLabel(Category.PRODUCT_REGULATION);
        affected.forEach(router::route);
        classifications.saveAll(affected);
        log.info("Routing rule PRODUCT_REGULATION → COMPLIANCE added; re-routed {} confirmed classifications",
                affected.size());
    }
}
