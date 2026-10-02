package com.ledgerdesk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.accounts.persistent=true","app.password=owner-test-password"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountRecoveryTest {
    @Autowired AccountRecovery recovery;
    @Autowired PersistentAccounts stored;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc http;
    @BeforeEach void clean() { DatabaseFixture.reset(db); stored.bootstrap("test","owner-test-password","reviewer","reviewer-test-only",encoder); }
    @Test void ownerPasswordRecoveryKeepsDataAndInvalidatesTheOldPassword() throws Exception {
        recovery.recover("test","recovered-owner-password","Lost owner credentials");
        http.perform(get("/api/access").with(httpBasic("test","owner-test-password"))).andExpect(status().isUnauthorized());
        http.perform(get("/api/access").with(httpBasic("test","recovered-owner-password"))).andExpect(status().isOk()).andExpect(jsonPath("$.canWrite").value(true));
        assertThat(db.queryForObject("SELECT reason FROM account_recoveries",String.class)).isEqualTo("Lost owner credentials");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM journal_entries",Integer.class)).isZero();
    }
    @Test void disabledReviewerCanBeRestoredAsOwnerWithAuditHistory() {
        db.update("UPDATE app_users SET enabled=FALSE WHERE username='reviewer'");
        recovery.recover("reviewer","recovered-reviewer-password","Owner account unavailable");
        var user=stored.load("reviewer"); assertThat(user.isEnabled()).isTrue();
        assertThat(user.getAuthorities()).extracting(a -> a.getAuthority()).containsExactly("ROLE_OWNER");
        assertThat(encoder.matches("recovered-reviewer-password",user.getPassword())).isTrue();
        assertThat(db.queryForObject("SELECT actor FROM audit_events",String.class)).isEqualTo("offline-recovery");
    }
    @Test void unknownOrOtherBusinessAccountCannotBeRecovered() {
        assertThatThrownBy(() -> recovery.recover("unknown","replacement-password","Lost login")).isInstanceOf(IllegalArgumentException.class);
        db.update("INSERT INTO businesses (id,name,currency) VALUES (2,'Other','USD')");
        db.update("UPDATE business_memberships SET business_id=2 WHERE user_id=(SELECT id FROM app_users WHERE username='reviewer')");
        assertThatThrownBy(() -> recovery.recover("reviewer","replacement-password","Lost login")).isInstanceOf(IllegalArgumentException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM account_recoveries",Integer.class)).isZero();
    }
    @Test void invalidPasswordOrReasonDoesNotChangeStoredCredentials() {
        String hash=stored.load("test").getPassword();
        assertThatThrownBy(() -> recovery.recover("test","short","Lost login")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> recovery.recover("test","replacement-password"," ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> recovery.recover("test","replacement-password","x".repeat(241))).isInstanceOf(IllegalArgumentException.class);
        assertThat(stored.load("test").getPassword()).isEqualTo(hash);
    }
    @Test void auditFailureRollsBackPasswordRoleEnablementAndRecoveryHistory() {
        String hash=stored.load("reviewer").getPassword(); db.update("UPDATE app_users SET enabled=FALSE WHERE username='reviewer'");
        db.update("ALTER TABLE audit_events ADD CONSTRAINT reject_recovery_audit CHECK (action <> 'ACCOUNT_OWNER_RECOVERED')");
        try { assertThatThrownBy(() -> recovery.recover("reviewer","replacement-password","Lost login")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class); }
        finally { db.update("ALTER TABLE audit_events DROP CONSTRAINT reject_recovery_audit"); }
        var user=stored.load("reviewer"); assertThat(user.getPassword()).isEqualTo(hash); assertThat(user.isEnabled()).isFalse();
        assertThat(user.getAuthorities()).extracting(a -> a.getAuthority()).containsExactly("ROLE_REVIEWER");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM account_recoveries",Integer.class)).isZero();
    }
    @Test void configuredModeRejectsRecoveryAndThereIsNoHttpRecoveryRoute() throws Exception {
        var configured=new AccountRecovery(db,encoder,false);
        assertThatThrownBy(() -> configured.recover("test","replacement-password","Lost login")).hasMessageContaining("persistent");
        http.perform(post("/api/accounts/recover").with(httpBasic("test","owner-test-password")).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isNotFound());
    }
}
