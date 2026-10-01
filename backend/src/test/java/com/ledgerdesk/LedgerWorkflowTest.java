package com.ledgerdesk;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
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
class LedgerWorkflowTest {
    @Autowired LedgerService ledger;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc http;
    @Autowired InvoicePdf pdf;
    private final LocalDate date = LocalDate.of(2026, 9, 1);

    @BeforeEach void clean() { DatabaseFixture.reset(db); }

    private LedgerService.Invoice invoice(String amount) {
        return new LedgerService.Invoice("demo-customer", "Brand design", date, date.plusDays(30), amount);
    }
    private BigDecimal balance(String code) {
        return db.queryForObject("SELECT COALESCE(SUM(debit - credit), 0) FROM journal_lines WHERE account_code = ?", BigDecimal.class, code);
    }
    private int count(String table) { return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

    @Test void partialPaymentSeparatesCashRevenueAndReceivables() {
        String id = ledger.postInvoice(invoice("1200"), "invoice", "test");
        ledger.recordPayment(id, new LedgerService.Payment(date.plusDays(3), "700"), "payment", "test");
        assertThat(balance("1000")).isEqualByComparingTo("700");
        assertThat(balance("1100")).isEqualByComparingTo("500");
        assertThat(balance("4000")).isEqualByComparingTo("-1200");
        assertThat(db.queryForObject("SELECT SUM(debit-credit) FROM journal_lines", BigDecimal.class)).isEqualByComparingTo("0");
        assertThat(count("audit_events")).isEqualTo(2);
    }
    @Test void decimalPaymentsClearBalanceExactly() {
        String id = ledger.postInvoice(invoice("0.30"), "i", "test");
        ledger.recordPayment(id, new LedgerService.Payment(date, "0.10"), "p1", "test");
        ledger.recordPayment(id, new LedgerService.Payment(date, "0.20"), "p2", "test");
        assertThat(balance("1100")).isEqualByComparingTo("0");
        assertThat(balance("1000")).isEqualByComparingTo("0.30");
    }
    @Test void retryDoesNotDuplicateInvoiceOrPayment() {
        String id = ledger.postInvoice(invoice("1200"), "i", "test");
        assertThat(ledger.postInvoice(invoice("1200"), "i", "test")).isEqualTo(id);
        var payment = new LedgerService.Payment(date, "700");
        String pid = ledger.recordPayment(id, payment, "p", "test");
        assertThat(ledger.recordPayment(id, payment, "p", "test")).isEqualTo(pid);
        assertThat(count("journal_entries")).isEqualTo(2);
        assertThat(count("payments")).isEqualTo(1);
    }
    @Test void reusedKeyWithDifferentPayloadIsRejected() {
        ledger.postInvoice(invoice("1200"), "i", "test");
        assertThatThrownBy(() -> ledger.postInvoice(invoice("1300"), "i", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("invoices")).isEqualTo(1);
    }
    @Test void overpaymentLeavesAllRecordsUnchanged() {
        String id = ledger.postInvoice(invoice("1200"), "i", "test");
        assertThatThrownBy(() -> ledger.recordPayment(id, new LedgerService.Payment(date, "1200.01"), "p", "test"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(count("payments")).isZero();
        assertThat(count("journal_entries")).isEqualTo(1);
    }
    @Test void invalidAmountsAreRejected() {
        for (String amount : new String[]{"0", "-1", "1.001", "NaN", "1e3", "1000000000000", ""})
            assertThatThrownBy(() -> ledger.postInvoice(invoice(amount), "invalid", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("invoices")).isZero();
    }
    @Test void datesAndCustomerMustBeValid() {
        assertThatThrownBy(() -> ledger.postInvoice(new LedgerService.Invoice("missing", "Work", date, date, "20"), "x", "test"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ledger.postInvoice(new LedgerService.Invoice("demo-customer", "Work", date, date.minusDays(1), "20"), "y", "test"))
                .isInstanceOf(IllegalArgumentException.class);
        String id = ledger.postInvoice(invoice("20"), "i", "test");
        assertThatThrownBy(() -> ledger.recordPayment(id, new LedgerService.Payment(date.minusDays(1), "10"), "p", "test"))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void voidPreservesOriginalAndAddsReversal() {
        String id = ledger.postInvoice(invoice("1200"), "i", "test");
        ledger.voidInvoice(id, date.plusDays(1), "v", "test");
        assertThat(count("invoices")).isEqualTo(1);
        assertThat(count("journal_entries")).isEqualTo(2);
        assertThat(balance("1100")).isEqualByComparingTo("0");
        assertThat(balance("4000")).isEqualByComparingTo("0");
        assertThatThrownBy(() -> ledger.recordPayment(id, new LedgerService.Payment(date, "1"), "p", "test"))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void paidInvoiceCannotBeVoided() {
        String id = ledger.postInvoice(invoice("20"), "i", "test");
        ledger.recordPayment(id, new LedgerService.Payment(date, "1"), "p", "test");
        assertThatThrownBy(() -> ledger.voidInvoice(id, date, "v", "test")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void failureDuringPostingRollsBackDocumentAndLedger() {
        assertThatThrownBy(() -> ledger.postInvoice(invoice("20"), "i", "x".repeat(101)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(count("invoices")).isZero();
        assertThat(count("journal_entries")).isZero();
        assertThat(count("commands")).isZero();
    }
    @Test void competingPaymentsCannotOverpay() throws Exception {
        String id = ledger.postInvoice(invoice("1200"), "i", "test");
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var a = pool.submit(() -> payAfter(start, id, "p1"));
            var b = pool.submit(() -> payAfter(start, id, "p2"));
            start.countDown();
            assertThat(a.get() + b.get()).isEqualTo(1);
            assertThat(balance("1000")).isEqualByComparingTo("800");
            assertThat(balance("1100")).isEqualByComparingTo("400");
        } finally { pool.shutdownNow(); }
    }
    private int payAfter(CountDownLatch start, String id, String key) throws Exception {
        start.await();
        try { ledger.recordPayment(id, new LedgerService.Payment(date, "800"), key, "test"); return 1; }
        catch (IllegalArgumentException expected) { return 0; }
    }
    @Test void savedDraftCanBeEditedWithoutChangingBalances() {
        String draft = ledger.createDraft(invoice("1200"), "d", "test");
        assertThat(ledger.createDraft(invoice("1200"), "d", "test")).isEqualTo(draft);
        var change = new LedgerService.DraftChanges(invoice("1500"), 0);
        ledger.updateDraft(draft, change, "edit", "test");
        ledger.updateDraft(draft, change, "edit", "test");
        assertThat(count("invoices")).isZero();
        assertThat(count("journal_entries")).isZero();
        assertThat(count("invoice_numbers")).isZero();
        assertThat(db.queryForObject("SELECT amount FROM invoice_drafts WHERE id = ?", BigDecimal.class, draft)).isEqualByComparingTo("1500");
        assertThat(db.queryForObject("SELECT version FROM invoice_drafts WHERE id = ?", Long.class, draft)).isEqualTo(1L);
    }

    @Test void staleDraftCannotOverwriteOrPostNewerDetails() {
        String draft = ledger.createDraft(invoice("1200"), "d", "test");
        ledger.updateDraft(draft, new LedgerService.DraftChanges(invoice("1500"), 0), "edit", "test");
        assertThatThrownBy(() -> ledger.updateDraft(draft, new LedgerService.DraftChanges(invoice("1000"), 0), "stale-edit", "test"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ledger.postDraft(draft, 0, "stale-post", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("invoices")).isZero();
        String posted = ledger.postDraft(draft, 1, "post", "test");
        assertThat(db.queryForObject("SELECT amount FROM invoices WHERE id = ?", BigDecimal.class, posted)).isEqualByComparingTo("1500");
    }

    @Test void postingDraftIsIdempotentAndMakesTheSavedCopyImmutable() {
        String draft = ledger.createDraft(invoice("1200"), "d", "test");
        String posted = ledger.postDraft(draft, 0, "post", "test");
        assertThat(ledger.postDraft(draft, 0, "post", "test")).isEqualTo(posted);
        assertThatThrownBy(() -> ledger.postDraft(draft, 0, "again", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ledger.updateDraft(draft, new LedgerService.DraftChanges(invoice("1000"), 1), "edit", "test"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ledger.discardDraft(draft, 1, "discard", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("invoices")).isEqualTo(1);
        assertThat(count("journal_entries")).isEqualTo(1);
        assertThat(balance("1100")).isEqualByComparingTo("1200");
    }

    @Test void discardedDraftRetainsHistoryWithoutPosting() {
        String draft = ledger.createDraft(invoice("20"), "d", "test");
        ledger.discardDraft(draft, 0, "discard", "test");
        ledger.discardDraft(draft, 0, "discard", "test");
        assertThat(count("invoice_drafts")).isEqualTo(1);
        assertThat(count("journal_entries")).isZero();
        assertThat((java.util.List<?>) ledger.state().get("drafts")).isEmpty();
        assertThatThrownBy(() -> ledger.postDraft(draft, 1, "post", "test")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void invoiceNumbersAreStableAndRollbackWithPosting() {
        String first = ledger.postInvoice(invoice("20"), "first", "test");
        assertThatThrownBy(() -> ledger.postInvoice(invoice("20"), "fail", "x".repeat(101)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        String second = ledger.postInvoice(invoice("20"), "second", "test");
        ledger.voidInvoice(first, date, "void", "test");
        assertThat(db.queryForObject("SELECT number_value FROM invoice_numbers WHERE invoice_id = ?", Long.class, first)).isEqualTo(1L);
        assertThat(db.queryForObject("SELECT number_value FROM invoice_numbers WHERE invoice_id = ?", Long.class, second)).isEqualTo(2L);
        assertThat(db.queryForObject("SELECT next_invoice_number FROM businesses WHERE id = 1", Long.class)).isEqualTo(3L);
    }

    @Test void concurrentPostingAssignsDistinctNumbers() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var a = pool.submit(() -> { start.await(); return ledger.postInvoice(invoice("20"), "i1", "test"); });
            var b = pool.submit(() -> { start.await(); return ledger.postInvoice(invoice("30"), "i2", "test"); });
            start.countDown(); a.get(); b.get();
            assertThat(db.queryForList("SELECT number_value FROM invoice_numbers ORDER BY number_value", Long.class)).containsExactly(1L, 2L);
            assertThat(balance("1100")).isEqualByComparingTo("50");
        } finally { pool.shutdownNow(); }
    }

    @Test void concurrentDraftPostingCreatesOneInvoice() throws Exception {
        String draft = ledger.createDraft(invoice("20"), "d", "test");
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Integer> one = () -> {
                start.await();
                try { ledger.postDraft(draft, 0, java.util.UUID.randomUUID().toString(), "test"); return 1; }
                catch (IllegalArgumentException expected) { return 0; }
            };
            var a = pool.submit(one); var b = pool.submit(one); start.countDown();
            assertThat(a.get() + b.get()).isEqualTo(1);
            assertThat(count("invoices")).isEqualTo(1);
            assertThat(count("journal_entries")).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @SuppressWarnings("unchecked")
    @Test void customerBalancesExcludeDraftsAndVoids() {
        String empty = ledger.addCustomer(new LedgerService.Customer("Empty customer", "empty@example.com"), "c", "test");
        ledger.createDraft(invoice("9000"), "d", "test");
        String posted = ledger.postInvoice(invoice("1200"), "i", "test");
        ledger.recordPayment(posted, new LedgerService.Payment(date, "700"), "p", "test");
        String voided = ledger.postInvoice(invoice("100"), "v", "test"); ledger.voidInvoice(voided, date, "void", "test");
        var customers = (java.util.List<java.util.Map<String, Object>>) ledger.state().get("customers");
        var sample = customers.stream().filter(c -> c.get("id").equals("demo-customer")).findFirst().orElseThrow();
        assertThat((BigDecimal) sample.get("invoiced")).isEqualByComparingTo("1200");
        assertThat((BigDecimal) sample.get("paid")).isEqualByComparingTo("700");
        assertThat((BigDecimal) sample.get("outstanding")).isEqualByComparingTo("500");
        var none = customers.stream().filter(c -> c.get("id").equals(empty)).findFirst().orElseThrow();
        assertThat((BigDecimal) none.get("outstanding")).isEqualByComparingTo("0");
        assertThat((BigDecimal) sample.get("outstanding")).isEqualByComparingTo(balance("1100"));
    }

    @Test void pdfContainsNumberPaymentBalanceAndVoidedStatus() throws Exception {
        String posted = ledger.postInvoice(new LedgerService.Invoice("demo-customer", "Café design (phase 1)", date, date.plusDays(30), "1200"), "i", "test");
        ledger.recordPayment(posted, new LedgerService.Payment(date, "700"), "p", "test");
        http.perform(get("/api/invoices/" + posted + "/pdf")).andExpect(status().isUnauthorized());
        byte[] bytes = http.perform(get("/api/invoices/" + posted + "/pdf").with(httpBasic("test", "test-only")))
                .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"INV-000001.pdf\""))
                .andReturn().getResponse().getContentAsByteArray();
        try (var document = org.apache.pdfbox.Loader.loadPDF(bytes)) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
            assertThat(text).contains("INV-000001", "Café design (phase 1)", "Invoice total: USD 1200.00", "Amount due: USD 500.00", "PAYMENT HISTORY");
            assertThat(document.getNumberOfPages()).isEqualTo(1);
        }
        String voided = ledger.postInvoice(invoice("20"), "v", "test"); ledger.voidInvoice(voided, date, "void", "test");
        try (var document = org.apache.pdfbox.Loader.loadPDF(pdf.render(ledger.invoiceDocument(voided)))) {
            assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(document)).contains("VOID - no amount due", "Amount due: USD 0.00");
        }
    }

    @Test void longPdfContentWrapsAndPaymentHistoryContinuesAcrossPages() throws Exception {
        String posted = ledger.postInvoice(new LedgerService.Invoice("demo-customer", "W".repeat(240), date, date, "100"), "i", "test");
        for (int n = 0; n < 50; n++) ledger.recordPayment(posted, new LedgerService.Payment(date, "1"), "p" + n, "test");
        try (var document = org.apache.pdfbox.Loader.loadPDF(pdf.render(ledger.invoiceDocument(posted)))) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
            assertThat(document.getNumberOfPages()).isGreaterThan(1);
            assertThat(text).contains("Amount due: USD 50.00", "Page 2", "time of download");
        }
    }

    @Test void unsupportedPdfGlyphsRemainVisibleAsUnicodeCodes() throws Exception {
        String posted = ledger.postInvoice(new LedgerService.Invoice("demo-customer", "Design 中", date, date, "20"), "i", "test");
        try (var document = org.apache.pdfbox.Loader.loadPDF(pdf.render(ledger.invoiceDocument(posted)))) {
            assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(document))
                    .contains("Design [U+4E2D]", "Characters outside this font");
        }
    }

    @Test void existingDatabaseUpgradesWithoutChangingAccountingEntries() throws Exception {
        String schema = "upgrade_" + java.util.UUID.randomUUID().toString().replace("-", "");
        try (var connection = db.getDataSource().getConnection()) {
            String original = connection.getSchema();
            try (var sql = connection.createStatement()) { sql.execute("CREATE SCHEMA " + schema); }
            try {
                var source = new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true);
                org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target("1").load().migrate();
                var old = new JdbcTemplate(source);
                connection.setSchema(schema);
                old.update("INSERT INTO invoices VALUES ('old-invoice', 1, 'demo-customer', 'Earlier work', ?, ?, 20, 0, 'POSTED')", date, date);
                old.update("INSERT INTO journal_entries VALUES ('old-entry', 1, ?, 'Earlier work', 'old-invoice')", date);
                old.update("INSERT INTO journal_lines VALUES ('old-debit', 'old-entry', '1100', 20, 0), ('old-credit', 'old-entry', '4000', 0, 20)");
                org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
                connection.setSchema(schema);
                assertThat(old.queryForObject("SELECT COUNT(*) FROM invoices", Integer.class)).isEqualTo(1);
                assertThat(old.queryForObject("SELECT COUNT(*) FROM journal_lines", Integer.class)).isEqualTo(2);
                assertThat(old.queryForObject("SELECT number_value FROM invoice_numbers WHERE invoice_id = 'old-invoice'", Long.class)).isEqualTo(1L);
                assertThat(old.queryForObject("SELECT next_invoice_number FROM businesses WHERE id = 1", Long.class)).isEqualTo(2L);
            } finally {
                connection.setSchema(original);
                try (var sql = connection.createStatement()) { sql.execute("DROP SCHEMA " + schema + " CASCADE"); }
            }
        }
    }

    @Test void apiRequiresAuthenticationAndCsrfForWrites() throws Exception {
        http.perform(get("/api/state")).andExpect(status().isUnauthorized());
        http.perform(get("/api/state").with(httpBasic("test", "test-only"))).andExpect(status().isOk());
        String body = "{\"customerId\":\"demo-customer\",\"description\":\"Design\",\"issuedOn\":\"2026-09-01\",\"dueOn\":\"2026-09-30\",\"amount\":\"1200\"}";
        http.perform(post("/api/invoices").with(httpBasic("test", "test-only")).header("Idempotency-Key", "i")
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        http.perform(post("/api/invoices").with(httpBasic("test", "test-only")).with(csrf()).header("Idempotency-Key", "i")
                .contentType("application/json").content(body)).andExpect(status().isOk());
    }
}
