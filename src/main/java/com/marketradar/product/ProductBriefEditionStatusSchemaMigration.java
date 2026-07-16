package com.marketradar.product;

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
import java.util.List;
import java.util.Locale;

/**
 * Compatibility migration for H2 databases created before Product editions gained
 * INSUFFICIENT_EVIDENCE and GENERATION_FAILED. Hibernate's ddl-auto=update does not
 * widen an existing enum-generated CHECK constraint, so a legacy database otherwise
 * rejects every newly generated Product edition.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProductBriefEditionStatusSchemaMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProductBriefEditionStatusSchemaMigration.class);
    private static final String TABLE = "PRODUCT_BRIEF_EDITIONS";
    private static final List<String> ALLOWED_STATUSES = List.of(
            "READY", "WATCH_BRIEF", "INSUFFICIENT_EVIDENCE", "GENERATION_FAILED", "INSUFFICIENT_SIGNAL");

    private final DataSource dataSource;

    public ProductBriefEditionStatusSchemaMigration(DataSource dataSource) {
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
                log.info("Migrated Product edition status constraint for legacy H2 database");
            }
        }
    }

    /** Public solely so the dependency-free H2 regression test can exercise the live migration. */
    public static boolean migrate(Connection connection) throws SQLException {
        List<StatusConstraint> statusChecks = statusChecks(connection);
        boolean alreadyCurrent = statusChecks.size() == 1
                && permitsAllCurrentStatuses(statusChecks.get(0).checkClause());
        if (alreadyCurrent) {
            return false;
        }

        try (Statement statement = connection.createStatement()) {
            for (StatusConstraint check : statusChecks) {
                statement.execute("ALTER TABLE \"" + TABLE + "\" DROP CONSTRAINT \""
                        + check.name().replace("\"", "\"\"") + "\"");
            }
            statement.execute("ALTER TABLE \"" + TABLE + "\" ADD CONSTRAINT "
                    + "\"CK_PRODUCT_BRIEF_EDITION_STATUS_V2\" CHECK (\"STATUS\" IN "
                    + "('READY','WATCH_BRIEF','INSUFFICIENT_EVIDENCE','GENERATION_FAILED','INSUFFICIENT_SIGNAL'))");
        }
        return true;
    }

    private static boolean tableExists(Connection connection) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet rs = metadata.getTables(null, null, TABLE, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    private static List<StatusConstraint> statusChecks(Connection connection) throws SQLException {
        String sql = "select tc.constraint_name, cc.check_clause "
                + "from information_schema.table_constraints tc "
                + "join information_schema.check_constraints cc "
                + "on tc.constraint_catalog = cc.constraint_catalog "
                + "and tc.constraint_schema = cc.constraint_schema "
                + "and tc.constraint_name = cc.constraint_name "
                + "where tc.table_schema = current_schema() "
                + "and tc.table_name = ? and tc.constraint_type = 'CHECK'";
        List<StatusConstraint> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, TABLE);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String clause = rs.getString("check_clause");
                    if (clause != null && clause.toUpperCase(Locale.ROOT).contains("\"STATUS\"")) {
                        result.add(new StatusConstraint(rs.getString("constraint_name"), clause));
                    }
                }
            }
        }
        return result;
    }

    private static boolean permitsAllCurrentStatuses(String clause) {
        String normalized = clause.toUpperCase(Locale.ROOT);
        return ALLOWED_STATUSES.stream().allMatch(status -> normalized.contains("'" + status + "'"));
    }

    private record StatusConstraint(String name, String checkClause) {}
}
