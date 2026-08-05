package com.marketradar.seed;

/**
 * Historical marker for the one-off 2026-08-02 cleanup.
 *
 * <p>This is deliberately no longer a Spring component. The current Analyst contract
 * once again creates an IMPLICATION slot, but only as a cautious interpretation that
 * is explicitly separated from the source observation. Re-running the old migration
 * on every application start would therefore supersede valid new work. The original
 * cleanup remains documented in source control and in the audit rows it created.</p>
 */
@Deprecated(forRemoval = false)
final class LegacyImplicationCleanupMigration {
    private LegacyImplicationCleanupMigration() {}
}
