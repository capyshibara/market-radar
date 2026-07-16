import com.marketradar.product.ProductBriefEditionStatusSchemaMigration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/** Regression: H2 enum checks must evolve when Product edition statuses expand. */
public class ProductBriefEditionStatusSchemaMigrationTest {
    public static void main(String[] args) throws Exception {
        Class.forName("org.h2.Driver");
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:product_status_migration;DB_CLOSE_DELAY=-1")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("create table product_brief_editions (status varchar(32) not null "
                        + "check (status in ('READY','INSUFFICIENT_SIGNAL')))");
                statement.execute("insert into product_brief_editions(status) values ('READY')");
            }
            assert ProductBriefEditionStatusSchemaMigration.migrate(connection);
            assert !ProductBriefEditionStatusSchemaMigration.migrate(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("insert into product_brief_editions(status) values ('INSUFFICIENT_EVIDENCE')");
                statement.execute("insert into product_brief_editions(status) values ('GENERATION_FAILED')");
                statement.execute("insert into product_brief_editions(status) values ('WATCH_BRIEF')");
                statement.execute("insert into product_brief_editions(status) values ('INSUFFICIENT_SIGNAL')");
                try {
                    statement.execute("insert into product_brief_editions(status) values ('INVALID')");
                    throw new AssertionError("invalid status must remain blocked");
                } catch (java.sql.SQLException expected) {
                    // Expected: the replacement constraint still protects the enum boundary.
                }
            }
        }
        System.out.println("ProductBriefEditionStatusSchemaMigrationTest passed");
    }
}
