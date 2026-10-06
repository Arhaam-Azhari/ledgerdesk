package com.ledgerdesk;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BusinessSettingsTest {
    @Autowired LedgerService ledger;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc http;
    @Autowired InvoicePdf pdf;

    @BeforeEach void clean() { DatabaseFixture.reset(db); }

    @Test void renameReachesWorkspaceAndInvoiceCopyWithoutChangingBooks() throws Exception {
        var date = LocalDate.of(2026, 9, 1);
        String invoice = ledger.postInvoice(new LedgerService.Invoice("demo-customer", "Design", date, date, "10.00"), "invoice", "owner");
        var before = db.queryForList("SELECT * FROM journal_lines ORDER BY id");
        ledger.updateBusiness(new LedgerService.BusinessChanges("  Harbor Design  ", 0), "rename", "owner");
        assertThat(ledger.state()).containsEntry("business", "Harbor Design").containsEntry("currency", "USD");
        assertThat(db.queryForList("SELECT * FROM journal_lines ORDER BY id")).isEqualTo(before);
        assertThat(db.queryForObject("SELECT next_invoice_number FROM businesses WHERE id = 1", Long.class)).isEqualTo(2);
        try (var document = Loader.loadPDF(pdf.render(ledger.invoiceDocument(invoice)))) {
            assertThat(new PDFTextStripper().getText(document)).contains("Harbor Design", "INV-000001").doesNotContain("Northline Design Studio");
        }
    }

    @Test void retriesDoNotReapplyAnOldNameOrAddAnotherAuditRecord() {
        var first = new LedgerService.BusinessChanges("Harbor Design", 0);
        ledger.updateBusiness(first, "first", "owner");
        ledger.updateBusiness(new LedgerService.BusinessChanges("Harbor Studio", 1), "second", "owner");
        ledger.updateBusiness(first, "first", "owner");
        assertThat(ledger.state()).containsEntry("business", "Harbor Studio");
        assertThat(db.queryForObject("SELECT settings_version FROM businesses WHERE id = 1", Long.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'BUSINESS_UPDATED'", Integer.class)).isEqualTo(2);
        assertThatThrownBy(() -> ledger.updateBusiness(new LedgerService.BusinessChanges("Other", 0), "first", "owner"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void staleAndInvalidNamesLeaveTheSavedDetailsUnchanged() {
        ledger.updateBusiness(new LedgerService.BusinessChanges("Harbor Design", 0), "saved", "owner");
        assertThatThrownBy(() -> ledger.updateBusiness(new LedgerService.BusinessChanges("Stale", 0), "stale", "owner"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Reload");
        for (String name : new String[]{" ", "x".repeat(121), "Harbor\nDesign"}) {
            assertThatThrownBy(() -> ledger.updateBusiness(new LedgerService.BusinessChanges(name, 1), "invalid", "owner"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(ledger.state()).containsEntry("business", "Harbor Design");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM commands", Integer.class)).isEqualTo(1);
    }

    @Test void changesRequireOwnerCsrfAndRequestKey() throws Exception {
        String body = "{\"name\":\"Harbor Design\",\"version\":0}";
        http.perform(post("/api/business").contentType("application/json").content(body)).andExpect(status().isForbidden());
        for (String role : new String[]{"BOOKKEEPER", "REVIEWER"}) {
            http.perform(post("/api/business").with(user("reader").roles(role)).with(csrf())
                    .header("Idempotency-Key", "denied").contentType("application/json").content(body)).andExpect(status().isForbidden());
        }
        http.perform(post("/api/business").with(user("owner").roles("OWNER"))
                .header("Idempotency-Key", "no-csrf").contentType("application/json").content(body)).andExpect(status().isForbidden());
        http.perform(post("/api/business").with(user("owner").roles("OWNER")).with(csrf())
                .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        http.perform(post("/api/business").with(user("owner").roles("OWNER")).with(csrf())
                .header("Idempotency-Key", "saved").contentType("application/json").content(body)).andExpect(status().isOk());
        assertThat(db.queryForObject("SELECT actor FROM audit_events WHERE action = 'BUSINESS_UPDATED'", String.class)).isEqualTo("owner");
    }
}
