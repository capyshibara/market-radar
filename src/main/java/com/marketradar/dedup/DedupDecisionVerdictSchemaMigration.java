package com.marketradar.dedup;

import com.marketradar.domain.DedupDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Widens the enum-generated H2 CHECK constraint after dedup verdicts were split
 * into duplicate content and independent corroboration. Hibernate's
 * {@code ddl-auto=update} does not evolve an existing enum CHECK constraint.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
public class DedupDecisionVerdictSchemaMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DedupDecisionVerdictSchemaMigration.class);
    private static final String TABLE = "DEDUP_DECISIONS";
    private static final String COLUMN = "VERDICT";

    private final DataSource dataSource;

    public DedupDecisionVerdictSchemaMigration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            if (!"H2".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())
                    || !tableExists(connection)) {
                return;
            }
            if (migrate(connection)) {
                log.info("Widened dedup decision verdict constraint to current relationship values");
            }
        }
    }

    /** Public so the dependency-free H2 regression test can exercise the live migration. */
    public static boolean migrate(Connection connection) throws SQLException {
        List<CheckConstraint> checks = verdictChecks(connection);
        boolean current = checks.size() == 1 && permitsAllCurrentVerdicts(checks.get(0).clause());
        if (current) {
            return false;
        }

        String allowed = Arrays.stream(DedupDecision.Verdict.values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(","));
        try (Statement statement = connection.createStatement()) {
            for (CheckConstraint check : checks) {
                statement.execute("ALTER TABLE \"" + TABLE + "\" DROP CONSTRAINT \""
                        + check.name().replace("\"", "\"\"") + "\"");
            }
            statement.execute("ALTER TABLE \"" + TABLE + "\" ADD CONSTRAINT "
                    + "\"CK_DEDUP_DECISION_VERDICT_V2\" CHECK (\"" + COLUMN
                    + "\" IN (" + allowed + "))");
        }
        return true;
    }

    private static boolean tableExists(Connection connection) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet rs = metadata.getTables(null, null, TABLE, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    private static List<CheckConstraint> verdictChecks(Connection connection) throws SQLException {
        String sql = "select tc.constraint_name, cc.check_clause "
                + "from information_schema.table_constraints tc "
                + "join information_schema.check_constraints cc "
                + "on tc.constraint_catalog=cc.constraint_catalog and tc.constraint_schema=cc.constraint_schema "
                + "and tc.constraint_name=cc.constraint_name "
                + "where tc.table_schema=current_schema() and tc.table_name=? "
                + "and tc.constraint_type='CHECK'";
        List<CheckConstraint> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, TABLE);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String clause = rs.getString("check_clause");
                    if (clause != null && clause.toUpperCase(Locale.ROOT).contains("\"" + COLUMN + "\"")) {
                        result.add(new CheckConstraint(rs.getString("constraint_name"), clause));
                    }
                }
            }
        }
        return result;
    }

    private static boolean permitsAllCurrentVerdicts(String clause) {
        String normalized = clause.toUpperCase(Locale.ROOT);
        return Arrays.stream(DedupDecision.Verdict.values())
                .allMatch(value -> normalized.contains("'" + value.name() + "'"));
    }

    private record CheckConstraint(String name, String clause) {}
}
