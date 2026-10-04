package com.ledgerdesk;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConfiguredPasswordTest {
    @Autowired MockMvc http;
    @Test void configuredLoginDoesNotPretendToSaveAStoredPassword() throws Exception {
        http.perform(post("/api/me/password").with(httpBasic("reviewer","reviewer-test-only")).with(csrf())
            .contentType("application/json").content("{\"currentPassword\":\"reviewer-test-only\",\"password\":\"replacement-password\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Password changes require persistent account mode."));
        http.perform(get("/api/access").with(httpBasic("reviewer","reviewer-test-only"))).andExpect(status().isOk());
    }
}
