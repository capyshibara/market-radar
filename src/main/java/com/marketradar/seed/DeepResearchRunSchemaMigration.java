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

/**
 * 2026-08-03: compatibility migration for `deep_research_run` databases created before the
 * Deep Research queue rewrite. Hibernate's ddl-auto=update never removes/renames an old
 * physical column when a mapped field is renamed, and never relaxes an existing column's NOT
 * NULL when the entity's own constraint is relaxed — it only ever ADDS brand-new columns for
 * brand-new fields. A first attempt at this migration patched the two columns found broken by
 * reproducing against a copy of one broken table (created_at, content_json) individually — that
 * missed at least one more on a real database with a slightly different history, still 500-ing
 * every enqueue. Whack-a-mole on individual columns isn't reliable; this table only holds
 * unverified Deep Research preview history (not evidence/claims), so there's nothing worth
 * surgically preserving — just DROP the whole table when it looks like the pre-queue shape
 * (missing the `status` column) and let Hibernate recreate it from scratch with the correct
 * schema. Non-destructive to anything that matters: Reviewer Queue claims and approvals live in
 * entirely different tables untouched by this.
 *
 * Note on timing: this ApplicationRunner runs AFTER Hibernate's own ddl-auto=update already
 * attempted (and partially failed) for THIS boot, so dropping here fixes the database for the
 * NEXT restart, not the one currently in progress — same as {@link DepartmentSchemaMigration}
 * would if it needed a full rebuild instead of a constraint swap.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 24)
public class DeepResearchRunSchemaMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DeepResearchRunSchemaMigration.class);

    private final DataSource dataSource;

    public DeepResearchRunSchemaMigration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            if (!"H2".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) return;
            if (!tableExists(connection, "DEEP_RESEARCH_RUN")) return;
            // created_at only ever existed on the pre-queue entity (renamed to queued_at) — its
            // mere presence is unambiguous proof of the legacy shape, unlike checking whether
            // `status` was added: Hibernate's ddl-auto:update CAN successfully add a new nullable
            // column to an existing table (that part alone isn't broken), so a first version of
            // this migration that checked "does status exist" wrongly treated the table as
            // healthy while content_json/created_at were still stuck NOT NULL underneath.
            if (!columnExists(connection, "DEEP_RESEARCH_RUN", "CREATED_AT")) return; // already current shape

            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE \"DEEP_RESEARCH_RUN\"");
            }
            log.warn("Dropped deep_research_run (pre-queue schema, still has the legacy created_at "
                    + "column) — only unverified Deep Research preview history was lost, nothing in "
                    + "the Reviewer Queue/claims. Hibernate will recreate it with the current schema; "
                    + "RESTART THE APP ONCE MORE for the table to actually exist again (this migration "
                    + "runs after this boot's own schema update already happened).");
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet rs = metadata.getTables(null, null, table, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    private static boolean columnExists(Connection connection, String table, String column) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet rs = metadata.getColumns(null, null, table, column)) {
            return rs.next();
        }
    }
}
