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
class AccountManagementTest {
    @Autowired AccountManagement accounts;
    @Autowired PersistentAccounts stored;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc http;
    @BeforeEach void clean() { DatabaseFixture.reset(db); stored.bootstrap("test","owner-test-password","reviewer","reviewer-test-only",encoder); }
    private String id(String name) { return db.queryForObject("SELECT id FROM app_users WHERE username=?",String.class,name); }
    private String create(String name,String role) { return accounts.create(new AccountManagement.Create(name,"new-account-password",role),"test"); }
    @Test void ownerListsAccountsWithoutPasswordHashes() throws Exception {
        assertThat(accounts.list("test")).hasSize(2).allMatch(row -> !row.containsKey("password_hash"));
        http.perform(get("/api/accounts").with(httpBasic("test","owner-test-password"))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        http.perform(get("/api/accounts").with(httpBasic("reviewer","reviewer-test-only"))).andExpect(status().isForbidden());
    }
    @Test void createPersistsHashAndRoleAndRejectsDuplicatesOrBadRoles() {
        String user=create("second-owner","OWNER"); assertThat(encoder.matches("new-account-password",stored.load("second-owner").getPassword())).isTrue();
        assertThat(stored.load("second-owner").getAuthorities()).extracting(a -> a.getAuthority()).containsExactly("ROLE_OWNER");
        assertThatThrownBy(() -> create("second-owner","REVIEWER")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> create("bad-role","ADMIN")).isInstanceOf(IllegalArgumentException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM audit_events WHERE record_id=?",Integer.class,user)).isEqualTo(1);
    }
    @Test void lastEnabledOwnerCannotBeDisabledOrDemoted() {
        assertThatThrownBy(() -> accounts.access(id("test"),new AccountManagement.Access("REVIEWER",true),"test")).hasMessageContaining("at least one enabled owner");
        assertThatThrownBy(() -> accounts.access(id("test"),new AccountManagement.Access("OWNER",false),"test")).hasMessageContaining("at least one enabled owner");
        String second=create("second-owner","OWNER"); accounts.access(id("test"),new AccountManagement.Access("REVIEWER",true),"test");
        assertThatThrownBy(() -> accounts.access(second,new AccountManagement.Access("OWNER",false),"second-owner")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void disableAndRoleChangesAffectSubsequentRealAuthentication() throws Exception {
        accounts.access(id("reviewer"),new AccountManagement.Access("REVIEWER",false),"test");
        http.perform(get("/api/state").with(httpBasic("reviewer","reviewer-test-only"))).andExpect(status().isUnauthorized());
        accounts.access(id("reviewer"),new AccountManagement.Access("OWNER",true),"test");
        http.perform(get("/api/accounts").with(httpBasic("reviewer","reviewer-test-only"))).andExpect(status().isOk());
    }
    @Test void passwordResetReplacesOldLoginAndSelfChangeNeedsCurrentPassword() throws Exception {
        accounts.password(id("reviewer"),new AccountManagement.Password("replacement-password",null),"test");
        http.perform(get("/api/state").with(httpBasic("reviewer","reviewer-test-only"))).andExpect(status().isUnauthorized());
        http.perform(get("/api/state").with(httpBasic("reviewer","replacement-password"))).andExpect(status().isOk());
        assertThatThrownBy(() -> accounts.password(id("test"),new AccountManagement.Password("replacement-password","wrong"),"test")).hasMessageContaining("current password");
        accounts.password(id("test"),new AccountManagement.Password("replacement-password","owner-test-password"),"test");
        assertThat(encoder.matches("replacement-password",stored.load("test").getPassword())).isTrue();
    }
    @Test void reviewerWritesAndOwnerWritesWithoutCsrfAreDenied() throws Exception {
        String body="{\"username\":\"new-reviewer\",\"password\":\"new-account-password\",\"role\":\"REVIEWER\"}";
        http.perform(post("/api/accounts").with(httpBasic("reviewer","reviewer-test-only")).with(csrf()).contentType("application/json").content(body)).andExpect(status().isForbidden());
        http.perform(post("/api/accounts").with(httpBasic("test","owner-test-password")).contentType("application/json").content(body)).andExpect(status().isForbidden());
        http.perform(post("/api/accounts").with(httpBasic("test","owner-test-password")).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk());
    }
    @Test void otherBusinessTargetsAndStaleOwnerActorsAreRejected() {
        String other=create("other-user","REVIEWER"); db.update("INSERT INTO businesses (id,name,currency) VALUES (2,'Other','USD')"); db.update("UPDATE business_memberships SET business_id=2 WHERE user_id=?",other);
        assertThatThrownBy(() -> accounts.access(other,new AccountManagement.Access("OWNER",true),"test")).hasMessageContaining("not found");
        assertThatThrownBy(() -> accounts.password(other,new AccountManagement.Password("replacement-password",null),"test")).hasMessageContaining("not found");
        assertThatThrownBy(() -> accounts.create(new AccountManagement.Create("blocked","new-account-password","OWNER"),"reviewer")).hasMessageContaining("enabled owner");
    }
    @Test void auditFailureRollsBackUserAndMembershipCreation() {
        db.update("ALTER TABLE audit_events ADD CONSTRAINT reject_account_audit CHECK (action <> 'ACCOUNT_CREATED')");
        try { assertThatThrownBy(() -> create("rolled-back","REVIEWER")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class); }
        finally { db.update("ALTER TABLE audit_events DROP CONSTRAINT reject_account_audit"); }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM app_users WHERE username='rolled-back'",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM business_memberships",Integer.class)).isEqualTo(2);
    }
}
