package vn.techcomlife.marketradar.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.techcomlife.marketradar.domain.Category;
import vn.techcomlife.marketradar.domain.RoutingRule;
import java.util.List;

public interface RoutingRuleRepository extends JpaRepository<RoutingRule, Long> {
    List<RoutingRule> findByCategory(Category category);
}
