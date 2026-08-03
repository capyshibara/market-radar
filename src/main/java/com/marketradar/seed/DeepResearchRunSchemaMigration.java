package com.marketradar.seed;

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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * 2026-08-03: compatibility migration for `deep_research_run` databases created before the
 * Deep Research queue rewrite. Hibernate's ddl-auto=update never removes/renames an old
 * physical column when a mapped field is renamed, and never RELAXES an existing column's NOT
 * NULL constraint when the entity's own annotation is relaxed — it only ever ADDS brand-new
 * columns for brand-new fields. Two columns were left stuck NOT NULL on any database that had
 * run Deep Research before this rewrite, each 500-ing every new enqueue in turn once the first
 * was fixed (found by reproducing against a copy of the actual broken table):
 *   - created_at: renamed to queued_at — new entity never supplies a value for the old column.
 *   - content_json: used to be required at insert time; now legitimately null until a run
 *     reaches DONE (a row is created at QUEUED time, before any content exists).
 * Same pattern as {@link DepartmentSchemaMigration}.
 *
 * Non-destructive: only drops the NOT NULL constraint, leaves the (now-partly-unused) column
 * and its old data in place — this table only holds unverified preview history, not
 * evidence/claims, so there's nothing worth actively migrating out of it either.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 24)
public class DeepResearchRunSchemaMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DeepResearchRunSchemaMigration.class);

    private static final List<String> COLUMNS_TO_RELAX = List.of("CREATED_AT", "CONTENT_JSON");

    private final DataSource dataSource;

    public DeepResearchRunSchemaMigration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            if (!"H2".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) return;
            if (!tableExists(connection, "DEEP_RESEARCH_RUN")) return;
            for (String column : COLUMNS_TO_RELAX) {
                if (!columnIsNotNull(connection, "DEEP_RESEARCH_RUN", column)) continue;
                try (Statement statement = connection.createStatement()) {
                    statement.execute("ALTER TABLE \"DEEP_RESEARCH_RUN\" ALTER COLUMN \"" + column + "\" SET NULL");
                }
                log.info("Dropped orphaned NOT NULL constraint on deep_research_run.{} — was blocking "
                        + "every new Deep Research enqueue on databases that ran Deep Research before "
                        + "the queue rewrite.", column);
            }
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet rs = metadata.getTables(null, null, table, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    private static boolean columnIsNotNull(Connection connection, String table, String column) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet rs = metadata.getColumns(null, null, table, column)) {
            if (!rs.next()) return false; // column doesn't exist on this DB — nothing to fix
            return rs.getInt("NULLABLE") == DatabaseMetaData.columnNoNulls;
        }
    }
}
