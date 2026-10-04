package com.ledgerdesk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.accounts.persistent=true","app.password=owner-test-password"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BookkeeperAccessTest {
    @Autowired MockMvc http;
    @Autowired JdbcTemplate db;
    @Autowired PersistentAccounts stored;
    @Autowired AccountManagement accounts;
    @Autowired PasswordEncoder encoder;
    String bookkeeperId;
    @BeforeEach void clean() {
        DatabaseFixture.reset(db);
        stored.bootstrap("test","owner-test-password","reviewer","reviewer-test-only",encoder);
        bookkeeperId=accounts.create(new AccountManagement.Create("bookkeeper","bookkeeper-test-password","BOOKKEEPER"),"test");
    }
    @Test void storedBookkeeperCanReadReportsAndHasAccurateIdentity() throws Exception {
        assertThat(stored.load("bookkeeper").getAuthorities()).extracting(a -> a.getAuthority()).containsExactly("ROLE_BOOKKEEPER");
        for(String path:new String[]{"/api/state","/api/reports?startsOn=2026-10-01&endsOn=2026-10-31","/api/accounting-periods"})
            http.perform(get(path).with(httpBasic("bookkeeper","bookkeeper-test-password"))).andExpect(status().isOk());
        http.perform(get("/api/access").with(httpBasic("bookkeeper","bookkeeper-test-password")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("BOOKKEEPER"))
            .andExpect(jsonPath("$.canWrite").value(true)).andExpect(header().string("Cache-Control","no-store"));
    }
    @Test void routinePostingRequiresCsrfAndRetainsBookkeeperActor() throws Exception {
        String body="{\"name\":\"Harbor Supplies\",\"email\":\"accounts@example.test\"}";
        http.perform(post("/api/vendors").with(httpBasic("bookkeeper","bookkeeper-test-password"))
            .contentType("application/json").content(body).header("Idempotency-Key","bookkeeper-vendor")).andExpect(status().isForbidden());
        for(int retry=0;retry<2;retry++)
            http.perform(post("/api/vendors").with(httpBasic("bookkeeper","bookkeeper-test-password")).with(csrf())
                .contentType("application/json").content(body).header("Idempotency-Key","bookkeeper-vendor")).andExpect(status().isOk());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM vendors",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM audit_events WHERE actor='bookkeeper'",Integer.class)).isEqualTo(1);
    }
    @Test void privilegedWritesAreRejectedBeforeBusinessValidation() throws Exception {
        int events=db.queryForObject("SELECT COUNT(*) FROM audit_events",Integer.class);
        for(String path:new String[]{"/api/accounts","/api/accounts/target/access","/api/accounts/target/password",
                "/api/opening-bank-balance","/api/equity","/api/equity/target/reverse",
                "/api/accounting-periods","/api/accounting-periods/target/reopen","/api/bank/reconciliations/target/reopen"})
            http.perform(post(path).with(httpBasic("bookkeeper","bookkeeper-test-password")).with(csrf())
                .contentType("application/json").content("{}").header("Idempotency-Key","blocked"))
                .andExpect(status().isForbidden());
        http.perform(get("/api/accounts").with(httpBasic("bookkeeper","bookkeeper-test-password"))).andExpect(status().isForbidden());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM commands",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM journal_entries",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM audit_events",Integer.class)).isEqualTo(events);
    }
    @Test void unlistedWritesAndOtherMethodsStayOwnerOnly() throws Exception {
        for(String path:new String[]{"/api/future-write","/api/assets/target/future-action","/api/accounts/target/extra","/api/bills/target/unknown"})
            http.perform(post(path).with(httpBasic("bookkeeper","bookkeeper-test-password")).with(csrf())).andExpect(status().isForbidden());
        http.perform(patch("/api/vendors/target").with(httpBasic("bookkeeper","bookkeeper-test-password")).with(csrf())).andExpect(status().isForbidden());
        http.perform(delete("/api/bills/target").with(httpBasic("bookkeeper","bookkeeper-test-password")).with(csrf())).andExpect(status().isForbidden());
    }
    @Test void routineModulesReachTheirValidationRatherThanPermissionDenial() throws Exception {
        // Incomplete requests should reach domain validation; none may create records.
        for(String path:new String[]{"/api/invoices","/api/bills","/api/expenses","/api/adjustments","/api/accruals",
                "/api/prepaid","/api/assets","/api/bank/imports/preview","/api/bank/reconciliations/preview"})
            http.perform(post(path).with(httpBasic("bookkeeper","bookkeeper-test-password")).with(csrf())
                .contentType("application/json").content("{}").header("Idempotency-Key","invalid"))
                .andExpect(status().isBadRequest());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM journal_entries",Integer.class)).isZero();
    }
    @Test void disablingAndDemotionTakeEffectOnNextAuthenticatedRequest() throws Exception {
        accounts.access(bookkeeperId,new AccountManagement.Access("BOOKKEEPER",false),"test");
        http.perform(get("/api/state").with(httpBasic("bookkeeper","bookkeeper-test-password"))).andExpect(status().isUnauthorized());
        accounts.access(bookkeeperId,new AccountManagement.Access("REVIEWER",true),"test");
        http.perform(post("/api/vendors").with(httpBasic("bookkeeper","bookkeeper-test-password")).with(csrf())
            .contentType("application/json").content("{}")).andExpect(status().isForbidden());
        http.perform(get("/api/access").with(httpBasic("bookkeeper","bookkeeper-test-password")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.canWrite").value(false));
    }
    @Test void ownerAdministrationStillProtectsLastOwnerAndRoleConstraint() {
        String ownerId=db.queryForObject("SELECT id FROM app_users WHERE username='test'",String.class);
        assertThatThrownBy(() -> accounts.access(ownerId,new AccountManagement.Access("BOOKKEEPER",true),"test"))
            .hasMessageContaining("at least one enabled owner");
        assertThatThrownBy(() -> db.update("UPDATE business_memberships SET role='ADMIN' WHERE user_id=?",bookkeeperId))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        stored.bootstrap("changed","changed-password","","",encoder);
        assertThat(stored.load("bookkeeper").getAuthorities()).extracting(a -> a.getAuthority()).containsExactly("ROLE_BOOKKEEPER");
        assertThatThrownBy(() -> accounts.create(new AccountManagement.Create("blocked","blocked-password","OWNER"),"bookkeeper"))
            .hasMessageContaining("enabled owner");
    }
}
