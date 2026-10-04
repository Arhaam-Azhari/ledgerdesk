package com.ledgerdesk;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.assertj.core.api.Assertions.*;

class BookkeeperMigrationTest {
    @Test void populatedVersion21UpgradeRetainsAccountsMembershipsAndBooks() {
        String schema="upgrade_"+UUID.randomUUID().toString().replace("-", "");
        String url=System.getenv().getOrDefault("TEST_DATABASE_URL", "jdbc:h2:mem:"+schema+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        var source=new SingleConnectionDataSource(url,
                System.getenv().getOrDefault("TEST_DATABASE_USER", "sa"),
                System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", ""),true);
        var db=new JdbcTemplate(source);
        var latest=Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).cleanDisabled(false).load();
        try {
            Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target("21").load().migrate();
            db.execute("SET SCHEMA '"+schema+"'");
            var encoder=new BCryptPasswordEncoder(4);
            String ownerHash=encoder.encode("retained-owner-password");
            String reviewerHash=encoder.encode("retained-reviewer-password");
            db.update("INSERT INTO app_users VALUES ('old-owner','retained-owner',?,TRUE)",ownerHash);
            db.update("INSERT INTO app_users VALUES ('old-reviewer','retained-reviewer',?,FALSE)",reviewerHash);
            db.update("INSERT INTO businesses (id,name,currency) VALUES (2,'Second business','USD')");
            db.update("INSERT INTO business_memberships VALUES ('old-owner',1,'OWNER'),('old-reviewer',1,'REVIEWER'),('old-owner',2,'REVIEWER')");
            db.update("INSERT INTO journal_entries VALUES ('old-entry',1,DATE '2026-10-01','Retained funding','old-source')");
            db.update("INSERT INTO journal_lines VALUES ('old-debit','old-entry','1000',12.34,0),('old-credit','old-entry','3000',0,12.34)");
            db.update("INSERT INTO commands VALUES ('old-key','retained-fingerprint','old-source')");
            db.update("INSERT INTO audit_events VALUES ('old-event',TIMESTAMP '2026-10-01 12:00:00','retained-owner','OWNER_CONTRIBUTION_POSTED','old-source')");
            List<String> tables=List.of("app_users","business_memberships","journal_entries","journal_lines","commands","audit_events");
            var before=new java.util.LinkedHashMap<String,List<Map<String,Object>>>();
            for(String table:tables) before.put(table,db.queryForList("SELECT * FROM "+table+" ORDER BY 1,2"));
            assertThatThrownBy(() -> db.update("INSERT INTO business_memberships VALUES ('old-reviewer',2,'BOOKKEEPER')"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
            db.execute("SET SCHEMA '"+schema+"'");
            for(String table:tables) assertThat(db.queryForList("SELECT * FROM "+table+" ORDER BY 1,2")).isEqualTo(before.get(table));
            var accounts=new PersistentAccounts(db);
            accounts.bootstrap("replacement-owner","replacement-password","","",encoder);
            assertThat(accounts.load("retained-owner").getPassword()).isEqualTo(ownerHash);
            assertThat(accounts.load("retained-owner").getAuthorities()).extracting(a -> a.getAuthority()).containsExactly("ROLE_OWNER");
            assertThat(accounts.load("retained-reviewer").isEnabled()).isFalse();
            assertThat(encoder.matches("retained-reviewer-password",accounts.load("retained-reviewer").getPassword())).isTrue();
            db.update("UPDATE business_memberships SET role='BOOKKEEPER' WHERE user_id='old-reviewer' AND business_id=1");
            assertThat(accounts.load("retained-reviewer").getAuthorities()).extracting(a -> a.getAuthority()).containsExactly("ROLE_BOOKKEEPER");
            assertThatThrownBy(() -> db.update("INSERT INTO business_memberships VALUES ('missing-user',1,'BOOKKEEPER')"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(() -> db.update("INSERT INTO business_memberships VALUES ('old-reviewer',99,'BOOKKEEPER')"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(() -> db.update("UPDATE business_memberships SET role='ADMIN' WHERE user_id='old-reviewer'"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(latest.migrate().migrationsExecuted).isZero();
        } finally {
            latest.clean();
            source.destroy();
        }
    }
}
