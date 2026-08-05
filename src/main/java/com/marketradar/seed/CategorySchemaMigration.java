package com.marketradar.seed;

import com.marketradar.domain.Category;
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

/** Widens legacy H2 enum checks before the cross-department taxonomy is routed. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class CategorySchemaMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CategorySchemaMigration.class);
    private static final List<String[]> TARGETS = List.of(
            new String[]{"ROUTING_RULES", "CATEGORY"},
            new String[]{"CLASSIFICATION_LABELS", "LABELS"});

    private final DataSource dataSource;

    public CategorySchemaMigration(DataSource dataSource) { this.dataSource = dataSource; }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            if (!"H2".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) return;
            for (String[] target : TARGETS) {
                if (tableExists(connection, target[0]) && migrate(connection, target[0], target[1])) {
                    log.info("Widened {}.{} check constraint to current Category values", target[0], target[1]);
                }
            }
        }
    }

    public static boolean migrate(Connection connection, String table, String column) throws SQLException {
        List<CheckConstraint> checks = columnChecks(connection, table, column);
        boolean current = checks.size() == 1 && permitsAll(checks.get(0).clause());
        if (current) return false;
        String allowed = Arrays.stream(Category.values()).map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(","));
        try (Statement statement = connection.createStatement()) {
            for (CheckConstraint check : checks) {
                statement.execute("ALTER TABLE \"" + table + "\" DROP CONSTRAINT \""
                        + check.name().replace("\"", "\"\"") + "\"");
            }
            statement.execute("ALTER TABLE \"" + table + "\" ADD CONSTRAINT \"CK_"
                    + table + "_" + column + "_CURATION\" CHECK (\"" + column
                    + "\" IN (" + allowed + "))");
        }
        return true;
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet rs = metadata.getTables(null, null, table, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    private static List<CheckConstraint> columnChecks(Connection connection, String table,
                                                      String column) throws SQLException {
        String sql = "select tc.constraint_name, cc.check_clause "
                + "from information_schema.table_constraints tc "
                + "join information_schema.check_constraints cc "
                + "on tc.constraint_catalog=cc.constraint_catalog and tc.constraint_schema=cc.constraint_schema "
                + "and tc.constraint_name=cc.constraint_name "
                + "where tc.table_schema=current_schema() and tc.table_name=? and tc.constraint_type='CHECK'";
        List<CheckConstraint> out = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String clause = rs.getString("check_clause");
                    if (clause != null && clause.toUpperCase(Locale.ROOT).contains("\"" + column + "\"")) {
                        out.add(new CheckConstraint(rs.getString("constraint_name"), clause));
                    }
                }
            }
        }
        return out;
    }

    private static boolean permitsAll(String clause) {
        String normalized = clause.toUpperCase(Locale.ROOT);
        return Arrays.stream(Category.values()).allMatch(value -> normalized.contains("'" + value.name() + "'"));
    }

    private record CheckConstraint(String name, String clause) {}
}
