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
    private final LocalDate date = LocalDate.of(2026, 9, 1);

    @BeforeEach void clean() {
        for (String table : new String[]{"payments", "journal_lines", "journal_entries", "invoices", "commands", "audit_events"})
            db.update("DELETE FROM " + table);
    }

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
