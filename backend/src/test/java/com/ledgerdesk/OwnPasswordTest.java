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
class OwnPasswordTest {
    @Autowired AccountManagement accounts;
    @Autowired PersistentAccounts stored;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc http;
    @BeforeEach void clean() {
        DatabaseFixture.reset(db);
        stored.bootstrap("test","owner-test-password","reviewer","reviewer-test-only",encoder);
        accounts.create(new AccountManagement.Create("bookkeeper","bookkeeper-test-password","BOOKKEEPER"),"test");
    }
    private String body(String current,String replacement) {
        return "{\"currentPassword\":\""+current+"\",\"password\":\""+replacement+"\"}";
    }
    @Test void everyStoredRoleChangesOnlyItsOwnPasswordAndKeepsItsPermissions() throws Exception {
        for(String[] login:new String[][]{{"test","owner-test-password","OWNER"},{"reviewer","reviewer-test-only","REVIEWER"},{"bookkeeper","bookkeeper-test-password","BOOKKEEPER"}}) {
            var memberships=db.queryForList("SELECT * FROM business_memberships ORDER BY user_id,business_id");
            http.perform(post("/api/me/password").with(httpBasic(login[0],login[1])).with(csrf())
                .contentType("application/json").content(body(login[1],"replacement-password")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.message").value("Password changed. Sign in again."))
                .andExpect(jsonPath("$.password_hash").doesNotExist());
            http.perform(get("/api/access").with(httpBasic(login[0],login[1]))).andExpect(status().isUnauthorized());
            http.perform(get("/api/access").with(httpBasic(login[0],"replacement-password"))).andExpect(status().isOk()).andExpect(jsonPath("$.role").value(login[2]));
            assertThat(db.queryForList("SELECT * FROM business_memberships ORDER BY user_id,business_id")).isEqualTo(memberships);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM audit_events WHERE actor=? AND action='ACCOUNT_SELF_PASSWORD_CHANGED'",Integer.class,login[0])).isEqualTo(1);
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM commands",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM journal_entries",Integer.class)).isZero();
    }
    @Test void csrfAnonymousAndInvalidAuthenticationAreRejected() throws Exception {
        String request=body("reviewer-test-only","replacement-password");
        http.perform(post("/api/me/password").with(httpBasic("reviewer","reviewer-test-only")).contentType("application/json").content(request)).andExpect(status().isForbidden());
        http.perform(post("/api/me/password").with(csrf()).contentType("application/json").content(request)).andExpect(status().isUnauthorized());
        http.perform(post("/api/me/password").with(httpBasic("reviewer","wrong")).with(csrf()).contentType("application/json").content(request)).andExpect(status().isUnauthorized());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='ACCOUNT_SELF_PASSWORD_CHANGED'",Integer.class)).isZero();
    }
    @Test void wrongCurrentPasswordAndInvalidReplacementLeaveHashUnchanged() throws Exception {
        String hash=stored.load("reviewer").getPassword();
        for(String request:new String[]{body("wrong-current","replacement-password"),body("reviewer-test-only","short"),body("reviewer-test-only","é".repeat(37)),body("reviewer-test-only","reviewer-test-only"),"{\"password\":\"replacement-password\"}"})
            http.perform(post("/api/me/password").with(httpBasic("reviewer","reviewer-test-only")).with(csrf()).contentType("application/json").content(request)).andExpect(status().isBadRequest());
        assertThat(stored.load("reviewer").getPassword()).isEqualTo(hash);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='ACCOUNT_SELF_PASSWORD_CHANGED'",Integer.class)).isZero();
    }
    @Test void clientCannotSelectAnotherAccountOrEscalateThroughTheSelfRoute() throws Exception {
        String hash=stored.load("test").getPassword();
        for(String extra:new String[]{"id","username","role"})
            http.perform(post("/api/me/password").with(httpBasic("reviewer","reviewer-test-only")).with(csrf())
                .contentType("application/json").content("{\"currentPassword\":\"reviewer-test-only\",\"password\":\"replacement-password\",\""+extra+"\":\"test\"}"))
                .andExpect(status().isBadRequest());
        http.perform(post("/api/me/other/password").with(httpBasic("reviewer","reviewer-test-only")).with(csrf())).andExpect(status().isForbidden());
        assertThat(stored.load("test").getPassword()).isEqualTo(hash);
    }
    @Test void disabledOrOtherBusinessAccountCannotChangePassword() {
        accounts.access(db.queryForObject("SELECT id FROM app_users WHERE username='bookkeeper'",String.class),new AccountManagement.Access("BOOKKEEPER",false),"test");
        assertThatThrownBy(() -> accounts.ownPassword(new AccountManagement.Password("replacement-password","bookkeeper-test-password"),"bookkeeper")).hasMessageContaining("enabled account");
        db.update("INSERT INTO businesses (id,name,currency) VALUES (2,'Other','USD')");
        db.update("UPDATE business_memberships SET business_id=2 WHERE user_id=(SELECT id FROM app_users WHERE username='reviewer')");
        assertThatThrownBy(() -> accounts.ownPassword(new AccountManagement.Password("replacement-password","reviewer-test-only"),"reviewer")).hasMessageContaining("enabled account");
    }
    @Test void activityFailureRollsBackPasswordHash() {
        String hash=stored.load("reviewer").getPassword();
        db.update("ALTER TABLE audit_events ADD CONSTRAINT reject_self_password CHECK (action <> 'ACCOUNT_SELF_PASSWORD_CHANGED')");
        try { assertThatThrownBy(() -> accounts.ownPassword(new AccountManagement.Password("replacement-password","reviewer-test-only"),"reviewer")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class); }
        finally { db.update("ALTER TABLE audit_events DROP CONSTRAINT reject_self_password"); }
        assertThat(stored.load("reviewer").getPassword()).isEqualTo(hash);
    }
    @Test void unicodePasswordAtByteLimitWorksWithoutStoringPlaintext() throws Exception {
        String replacement="é".repeat(36);
        accounts.ownPassword(new AccountManagement.Password(replacement,"reviewer-test-only"),"reviewer");
        assertThat(stored.load("reviewer").getPassword()).isNotEqualTo(replacement);
        assertThat(encoder.matches(replacement,stored.load("reviewer").getPassword())).isTrue();
        http.perform(get("/api/access").with(httpBasic("reviewer",replacement))).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("REVIEWER"));
    }
}
