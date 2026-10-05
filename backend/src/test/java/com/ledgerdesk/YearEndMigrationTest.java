package com.ledgerdesk;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.assertj.core.api.Assertions.*;

class YearEndMigrationTest {
    @Test void populatedVersion24UpgradeRetainsClosingEvidenceAndAllowsOneActiveCycle() {
        String schema = "year_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        String url = System.getenv().getOrDefault("TEST_DATABASE_URL", "jdbc:h2:mem:" + schema + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        var source = new SingleConnectionDataSource(url, System.getenv().getOrDefault("TEST_DATABASE_USER", "sa"), System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", ""), true);
        var db = new JdbcTemplate(source);
        var latest = Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).cleanDisabled(false).load();
        try {
            Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target("24").load().migrate();
            db.execute("SET SCHEMA '" + schema + "'");
            db.update("INSERT INTO bank_reconciliations (id,business_id,account_code,starts_on,ends_on,opening_balance,closing_balance,snapshot,status,closed_at,closed_by) VALUES ('bank',1,'1000',DATE '2026-01-01',DATE '2026-12-31',0,0,'bank proof','CLOSED',TIMESTAMP '2026-12-31 12:00:00','owner')");
            db.update("INSERT INTO accounting_period_closes (id,business_id,starts_on,ends_on,review_note,snapshot,status,closed_by,closed_at) VALUES ('period',1,DATE '2026-01-01',DATE '2026-12-31','Annual review','period proof','CLOSED','owner',TIMESTAMP '2026-12-31 12:01:00')");
            db.update("INSERT INTO journal_entries VALUES ('closing-entry',1,DATE '2026-12-31','Retained earnings close','original-close')");
            db.update("INSERT INTO journal_lines VALUES ('closing-dr','closing-entry','4000',10.10,0),('closing-cr','closing-entry','3300',0,10.10)");
            db.update("INSERT INTO year_end_closes VALUES ('original-close',1,2026,DATE '2026-01-01',DATE '2026-12-31','closing-entry','period','bank','Reviewed earnings','retained JSON snapshot','owner',TIMESTAMP '2026-12-31 12:02:00')");
            var original = db.queryForMap("SELECT * FROM year_end_closes");
            var lines = db.queryForList("SELECT * FROM journal_lines ORDER BY id");
            assertThat(latest.migrate().migrationsExecuted).isPositive();
            db.execute("SET SCHEMA '" + schema + "'");
            var migrated = db.queryForMap("SELECT * FROM year_end_closes");
            original.forEach((key, value) -> assertThat(migrated.get(key)).isEqualTo(value));
            assertThat(migrated.get("status")).isEqualTo("CLOSED");
            assertThat(((Number) migrated.get("active_year")).intValue()).isEqualTo(2026);
            assertThat(((Number) migrated.get("version")).intValue()).isEqualTo(1);
            assertThat(db.queryForList("SELECT * FROM journal_lines ORDER BY id")).isEqualTo(lines);
            String replacement = "INSERT INTO year_end_closes (id,business_id,calendar_year,starts_on,ends_on,accounting_period_id,bank_reconciliation_id,review_note,snapshot,closed_by,closed_at,active_year) VALUES ('replacement',1,2026,DATE '2026-01-01',DATE '2026-12-31','period','bank','Replacement review','new snapshot','owner',TIMESTAMP '2026-12-31 12:03:00',2026)";
            assertThatThrownBy(() -> db.update(replacement)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            db.update("UPDATE year_end_closes SET status = 'REOPENED', active_year = NULL, version = 2 WHERE id = 'original-close'");
            db.update(replacement);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM year_end_closes", Integer.class)).isEqualTo(2);
            assertThat(db.queryForObject("SELECT snapshot FROM year_end_closes WHERE id = 'original-close'", String.class)).isEqualTo("retained JSON snapshot");
            assertThat(latest.migrate().migrationsExecuted).isZero();
        } finally { latest.clean(); source.destroy(); }
    }
}
