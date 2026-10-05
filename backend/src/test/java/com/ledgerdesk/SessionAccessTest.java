package com.ledgerdesk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"app.auth.mode=session", "app.accounts.persistent=true", "app.password=owner-test-password"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SessionAccessTest {
    @Autowired MockMvc http;
    @Autowired JdbcTemplate db;
    @Autowired PersistentAccounts stored;
    @Autowired AccountManagement accounts;
    @Autowired PasswordEncoder encoder;
    @BeforeEach void clean() {
        DatabaseFixture.reset(db);
        stored.bootstrap("test", "owner-test-password", "reviewer", "reviewer-test-only", encoder);
        accounts.create(new AccountManagement.Create("bookkeeper", "bookkeeper-test-password", "BOOKKEEPER"), "test");
    }
    private MockHttpSession login(String name, String password) throws Exception {
        return (MockHttpSession) http.perform(post("/api/session/login").with(csrf())
                .param("username", name).param("password", password))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getRequest().getSession(false);
    }
    @Test void sessionReadsWorkAndBasicHeadersAreNotAccepted() throws Exception {
        http.perform(get("/api/auth")).andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("session"));
        http.perform(get("/api/state").with(httpBasic("test", "owner-test-password"))).andExpect(status().isUnauthorized());
        var session = login("test", "owner-test-password");
        http.perform(get("/api/access").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("OWNER"));
        http.perform(get("/api/state").session(session)).andExpect(status().isOk());
    }
    @Test void loginRequiresCsrfAndWrongPasswordsDoNotOpenBooks() throws Exception {
        http.perform(post("/api/session/login").param("username", "test").param("password", "owner-test-password")).andExpect(status().isForbidden());
        http.perform(post("/api/session/login").with(csrf()).param("username", "test").param("password", "wrong"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("Check your username and password."));
        http.perform(get("/api/state")).andExpect(status().isUnauthorized());
        var active = login("test", "owner-test-password");
        http.perform(post("/api/session/login").session(active).with(csrf()).param("username", "test").param("password", "wrong"))
                .andExpect(status().isUnauthorized());
        assertThat(active.isInvalid()).isTrue();
    }
    @Test void loginChangesTheExistingSessionIdAndLogoutInvalidatesIt() throws Exception {
        var initial = new MockHttpSession(); String oldId = initial.getId();
        var result = http.perform(post("/api/session/login").session(initial).with(csrf())
                .param("username", "test").param("password", "owner-test-password")).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session.getId()).isNotEqualTo(oldId);
        http.perform(post("/api/session/logout").session(session)).andExpect(status().isForbidden());
        http.perform(post("/api/session/logout").session(session).with(csrf())).andExpect(status().isOk());
        assertThat(session.isInvalid()).isTrue();
        http.perform(get("/api/state")).andExpect(status().isUnauthorized());
    }
    @Test void sessionsKeepRoleRestrictionsAndCsrfOnWrites() throws Exception {
        var reviewer = login("reviewer", "reviewer-test-only");
        http.perform(get("/api/state").session(reviewer)).andExpect(status().isOk());
        http.perform(post("/api/vendors").session(reviewer).with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        var bookkeeper = login("bookkeeper", "bookkeeper-test-password");
        http.perform(post("/api/opening-books").session(bookkeeper).with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        http.perform(post("/api/vendors").session(bookkeeper).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }
    @Test void passwordChangesInvalidateExistingSessions() throws Exception {
        var first = login("test", "owner-test-password"); var second = login("test", "owner-test-password");
        var staleReload = login("test", "owner-test-password");
        accounts.ownPassword(new AccountManagement.Password("replacement-password", "owner-test-password"), "test");
        for (var session : new MockHttpSession[]{first, second}) {
            http.perform(get("/api/state").session(session)).andExpect(status().isUnauthorized());
            assertThat(session.isInvalid()).isTrue();
        }
        http.perform(get("/api/auth").session(staleReload)).andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("session"));
        assertThat(staleReload.isInvalid()).isTrue();
        http.perform(get("/api/state").session(login("test", "replacement-password"))).andExpect(status().isOk());
    }
    @Test void roleChangesAndDisabledAccountsInvalidateSessions() throws Exception {
        var session = login("bookkeeper", "bookkeeper-test-password");
        String id = db.queryForObject("SELECT id FROM app_users WHERE username='bookkeeper'", String.class);
        accounts.access(id, new AccountManagement.Access("REVIEWER", true), "test");
        http.perform(get("/api/state").session(session)).andExpect(status().isUnauthorized());
        var replacement = login("bookkeeper", "bookkeeper-test-password");
        http.perform(get("/api/access").session(replacement)).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("REVIEWER"));
        accounts.access(id, new AccountManagement.Access("REVIEWER", false), "test");
        http.perform(get("/api/state").session(replacement)).andExpect(status().isUnauthorized());
    }
}
