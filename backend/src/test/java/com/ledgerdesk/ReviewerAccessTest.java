package com.ledgerdesk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReviewerAccessTest {
    @Autowired MockMvc http;
    @Autowired JdbcTemplate db;
    @BeforeEach void clean() { DatabaseFixture.reset(db); }
    @Test void reviewerCanReadBooksAndReports() throws Exception {
        for (String path : new String[]{"/api/state", "/api/reports?startsOn=2026-10-01&endsOn=2026-10-31", "/api/reports/cash-activity?startsOn=2026-10-01&endsOn=2026-10-31"})
            http.perform(get(path).with(httpBasic("reviewer", "reviewer-test-only"))).andExpect(status().isOk());
        http.perform(get("/api/access").with(httpBasic("reviewer", "reviewer-test-only")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canWrite").value(false))
                .andExpect(jsonPath("$.role").value("REVIEWER")).andExpect(header().string("Cache-Control", "no-store"));
    }
    @Test void reviewerCannotWriteEvenWithValidCsrf() throws Exception {
        for (String path : new String[]{"/api/customers", "/api/vendors", "/api/invoices", "/api/expenses", "/api/equity", "/api/accruals", "/api/prepaid", "/api/assets", "/api/bank/imports", "/api/bank/reconciliations"})
            http.perform(post(path).with(httpBasic("reviewer", "reviewer-test-only")).with(csrf())
                    .contentType("application/json").content("{}").header("Idempotency-Key", "blocked"))
                    .andExpect(status().isForbidden());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM commands", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM audit_events", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM journal_entries", Integer.class)).isZero();
    }
    @Test void reviewerCannotPatchDeleteOrUseUnknownWriteRoutes() throws Exception {
        http.perform(patch("/api/invoices/example").with(httpBasic("reviewer", "reviewer-test-only")).with(csrf())).andExpect(status().isForbidden());
        http.perform(delete("/api/receipts/example").with(httpBasic("reviewer", "reviewer-test-only")).with(csrf())).andExpect(status().isForbidden());
        http.perform(put("/api/future-write").with(httpBasic("reviewer", "reviewer-test-only")).with(csrf())).andExpect(status().isForbidden());
    }
    @Test void ownerCanWriteButStillNeedsCsrf() throws Exception {
        http.perform(get("/api/access").with(httpBasic("test", "test-only"))).andExpect(status().isOk()).andExpect(jsonPath("$.canWrite").value(true));
        String body = "{\"name\":\"Harbor\",\"email\":\"accounts@example.test\"}";
        http.perform(post("/api/vendors").with(httpBasic("test", "test-only")).contentType("application/json").content(body).header("Idempotency-Key", "vendor")).andExpect(status().isForbidden());
        http.perform(post("/api/vendors").with(httpBasic("test", "test-only")).with(csrf()).contentType("application/json").content(body).header("Idempotency-Key", "vendor")).andExpect(status().isOk());
    }
    @Test void anonymousAndInvalidLoginsCannotReadIdentityOrBooks() throws Exception {
        http.perform(get("/api/access")).andExpect(status().isUnauthorized());
        http.perform(get("/api/state").with(httpBasic("reviewer", "wrong"))).andExpect(status().isUnauthorized());
        http.perform(get("/api/csrf")).andExpect(status().isOk());
    }
}
