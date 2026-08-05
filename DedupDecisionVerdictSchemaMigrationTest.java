import com.marketradar.dedup.DedupDecisionVerdictSchemaMigration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/** Regression: legacy H2 dedup constraints must accept the explicit relationship verdicts. */
public class DedupDecisionVerdictSchemaMigrationTest {
    public static void main(String[] args) throws Exception {
        Class.forName("org.h2.Driver");
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:dedup_verdict_migration;DB_CLOSE_DELAY=-1")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("create table dedup_decisions (verdict varchar(64) not null "
                        + "check (verdict in ('DIFFERENT','NEEDS_REVIEW','SAME_EVENT')))" );
                statement.execute("insert into dedup_decisions(verdict) values ('DIFFERENT')");
            }

            assert DedupDecisionVerdictSchemaMigration.migrate(connection);
            assert !DedupDecisionVerdictSchemaMigration.migrate(connection);

            try (Statement statement = connection.createStatement()) {
                statement.execute("insert into dedup_decisions(verdict) values ('DUPLICATE_CONTENT')");
                statement.execute("insert into dedup_decisions(verdict) values ('SAME_EVENT_INDEPENDENT')");
                statement.execute("insert into dedup_decisions(verdict) values ('NEEDS_REVIEW')");
                statement.execute("insert into dedup_decisions(verdict) values ('SAME_EVENT')");
                try {
                    statement.execute("insert into dedup_decisions(verdict) values ('UNSUPPORTED')");
                    throw new AssertionError("unknown verdict must remain blocked");
                } catch (java.sql.SQLException expected) {
                    // The replacement constraint remains closed to unknown values.
                }
            }
        }
        System.out.println("DedupDecisionVerdictSchemaMigrationTest: ALL PASS");
    }
}
