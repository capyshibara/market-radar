import com.marketradar.seed.DepartmentSchemaMigration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/** Regression: H2 enum checks must widen when the Department roster expands. */
public class DepartmentSchemaMigrationTest {
    public static void main(String[] args) throws Exception {
        Class.forName("org.h2.Driver");
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:department_migration;DB_CLOSE_DELAY=-1")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("create table routing_rules (department varchar(32) not null "
                        + "check (department in ('PRODUCT','SALES')))");
                statement.execute("insert into routing_rules(department) values ('PRODUCT')");
            }
            assert DepartmentSchemaMigration.migrate(connection, "ROUTING_RULES", "DEPARTMENT");
            assert !DepartmentSchemaMigration.migrate(connection, "ROUTING_RULES", "DEPARTMENT");
            try (Statement statement = connection.createStatement()) {
                statement.execute("insert into routing_rules(department) values ('COMPLIANCE')");
                statement.execute("insert into routing_rules(department) values ('SALES')");
                try {
                    statement.execute("insert into routing_rules(department) values ('INVALID')");
                    throw new AssertionError("invalid department must still be rejected");
                } catch (java.sql.SQLException expected) {
                    // the widened constraint stays closed to unknown values
                }
            }
        }
        System.out.println("DepartmentSchemaMigrationTest: ALL PASS");
    }
}
