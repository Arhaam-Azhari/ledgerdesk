package com.ledgerdesk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
class CustomerStatementPdfTest {
    @Autowired CustomerStatementService statements;
    @Autowired CustomerStatementPdf pdf;
    @Autowired LedgerService ledger;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc http;
    private final LocalDate start = LocalDate.of(2030, 10, 1), end = LocalDate.of(2030, 10, 31);
    private final String path = "/api/reports/customers/demo-customer/statement/pdf?startsOn=2030-10-01&endsOn=2030-10-31";

    @BeforeEach void clean() { DatabaseFixture.reset(db); }
    private String invoice(String description, String amount, LocalDate date, String key) {
        return ledger.postInvoice(new LedgerService.Invoice("demo-customer", description, date, date, amount), key, "test");
    }
    private String text(byte[] bytes) throws Exception {
        try (var document = Loader.loadPDF(bytes)) { return new PDFTextStripper().getText(document); }
    }
    private void proof(String name, byte[] bytes) throws Exception {
        Path directory = Path.of("target", "statement-pdf-proof");
        Files.createDirectories(directory);
        Files.write(directory.resolve(name), bytes);
    }

    @Test void downloadedPdfRetainsDatedOpeningMovementsAndClosingWithoutWriting() throws Exception {
        db.update("UPDATE customers SET name='Cedar Design Partners', email='accounts@cedar.example' WHERE id='demo-customer'");
        String old = invoice("September design", "300.30", start.minusDays(1), "old");
        ledger.recordPayment(old, new LedgerService.Payment(start.minusDays(1), "100.10"), "opening-payment", "test");
        String current = invoice("October design", "200.20", start, "current");
        ledger.recordPayment(old, new LedgerService.Payment(start.plusDays(1), "50.05"), "old-payment", "test");
        String reversed = invoice("Cancelled design", "40", start.plusDays(9), "reversed");
        ledger.voidInvoice(reversed, start.plusDays(14), "void", "test");
        ledger.recordPayment(current, new LedgerService.Payment(end, "25.25"), "current-payment", "test");
        ledger.recordPayment(old, new LedgerService.Payment(end.plusDays(1), "150.15"), "future-old", "test");
        ledger.recordPayment(current, new LedgerService.Payment(end.plusDays(1), "174.95"), "future-current", "test");
        var lines = db.queryForList("SELECT * FROM journal_lines ORDER BY id");
        var audit = db.queryForList("SELECT * FROM audit_events ORDER BY id");
        var commands = db.queryForList("SELECT * FROM commands ORDER BY command_key");
        byte[] bytes = http.perform(get(path).with(httpBasic("test", "test-only")))
                .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"ledgerdesk-customer-statement-2030-10-01-2030-10-31.pdf\""))
                .andReturn().getResponse().getContentAsByteArray();
        String content = text(bytes);
        assertThat(content).contains("Cedar Design Partners", "accounts@cedar.example", "2030-10-01 to 2030-10-31",
                "Opening amount owed: USD 200.20", "Invoice charges: USD 240.20", "Payments received: USD 75.30",
                "Invoice reversals: USD 40.00", "Closing amount owed: USD 325.10",
                "2030-10-02  |  Payment  |  INV-000001", "Reduction USD 50.05  /  Balance USD 350.35",
                "2030-10-15  |  Invoice reversal  |  INV-000003").doesNotContain("2030-11-01");
        try (var document = Loader.loadPDF(bytes)) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            assertThat(document.getDocumentCatalog().getOpenAction()).isNull();
        }
        assertThat(db.queryForList("SELECT * FROM journal_lines ORDER BY id")).isEqualTo(lines);
        assertThat(db.queryForList("SELECT * FROM audit_events ORDER BY id")).isEqualTo(audit);
        assertThat(db.queryForList("SELECT * FROM commands ORDER BY command_key")).isEqualTo(commands);
        proof("customer-statement.pdf", bytes);
    }

    @Test void emptyPeriodCarriesEarlierBalanceAndPrintsDateLimits() throws Exception {
        invoice("Earlier work", "0.30", start.minusDays(1), "old");
        assertThat(text(pdf.render(statements.statement("demo-customer", start, end))))
                .contains("No activity in this period", "Opening amount owed: USD 0.30", "Closing amount owed: USD 0.30");
        assertThat(text(pdf.render(statements.statement("demo-customer", LocalDate.of(1, 1, 1), LocalDate.of(9999, 12, 31)))))
                .contains("0001-01-01 to 9999-12-31", "Closing amount owed: USD 0.30");
    }

    @Test void longContentAndManyMovementsStayInsidePagesWithNumberedPeriodFooters() throws Exception {
        db.update("UPDATE customers SET name=?, email=? WHERE id='demo-customer'", "W".repeat(120), "e".repeat(180) + "@example.test");
        String id = invoice("W".repeat(240), "50.00", start, "long");
        for (int n = 0; n < 30; n++) ledger.recordPayment(id, new LedgerService.Payment(start, "0.10"), "part-" + n, "test");
        byte[] bytes = pdf.render(statements.statement("demo-customer", start, end));
        try (var document = Loader.loadPDF(bytes)) {
            assertThat(document.getNumberOfPages()).isGreaterThan(2);
            PDFTextStripper bounds = new PDFTextStripper() {
                @Override protected void processTextPosition(TextPosition position) {
                    assertThat(position.getXDirAdj()).isGreaterThanOrEqualTo(49);
                    assertThat(position.getXDirAdj() + position.getWidthDirAdj()).isLessThanOrEqualTo(563);
                    assertThat(position.getYDirAdj()).isBetween(40f, 763f);
                    super.processTextPosition(position);
                }
            };
            assertThat(bounds.getText(document)).contains("Closing amount owed: USD 47.00", "Balance USD 47.00");
            for (int n = 1; n <= document.getNumberOfPages(); n++) {
                var page = new PDFTextStripper(); page.setStartPage(n); page.setEndPage(n);
                assertThat(page.getText(document)).contains("Customer statement  /  2030-10-01 to 2030-10-31", "Page " + n);
            }
        }
        proof("customer-statement-multipage.pdf", bytes);
    }

    @Test void unicodeFallbackAndControlCharactersRemainReadable() throws Exception {
        db.update("UPDATE customers SET name=?, email=? WHERE id='demo-customer'", "Café 中\nStudio", "accounts@example.test");
        invoice("Design 中\tphase", "20", start, "unicode");
        assertThat(text(pdf.render(statements.statement("demo-customer", start, end))))
                .contains("Café [U+4E2D] Studio", "Design [U+4E2D] phase", "Characters outside this font");
    }

    @Test void pdfRequiresLoginAllowsAllReadingRolesAndValidatesBeforeRendering() throws Exception {
        http.perform(get(path)).andExpect(status().isUnauthorized());
        for (String role : java.util.List.of("OWNER", "BOOKKEEPER", "REVIEWER"))
            http.perform(get(path).with(user("reader").roles(role)))
                    .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"))
                    .andExpect(header().string("Cache-Control", "no-store"));
        db.update("INSERT INTO businesses (id,name,currency) VALUES (2,'Other business','USD')");
        db.update("INSERT INTO customers VALUES ('private-customer',2,'Private','private@example.test')");
        for (String invalid : java.util.List.of(path.replace("2030-10-01", "2030-11-01"), path.replace("2030-10-01", "bad"),
                path.replace("&endsOn=2030-10-31", ""), path.replace("demo-customer", "missing"), path.replace("demo-customer", "private-customer")))
            http.perform(get(invalid).with(user("reader").roles("REVIEWER"))).andExpect(status().isBadRequest());
    }
}
