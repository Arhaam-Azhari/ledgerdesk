package com.ledgerdesk;

import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.servlet.AutoConfigureMockMvc;
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
class CustomerStatementTest {
    @Autowired CustomerStatementService statements;
    @Autowired LedgerService ledger;
    @Autowired ReportService reports;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc http;
    private final LocalDate start = LocalDate.of(2026, 10, 1), end = LocalDate.of(2026, 10, 31);
    @BeforeEach void clean() { DatabaseFixture.reset(db); }
    private String invoice(String amount, LocalDate date, String key) {
        return ledger.postInvoice(new LedgerService.Invoice("demo-customer", "Design " + key, date, date, amount), key, "test");
    }
    private String pay(String invoice, String amount, LocalDate date, String key) {
        return ledger.recordPayment(invoice, new LedgerService.Payment(date, amount), key, "test");
    }
    private CustomerStatementService.Statement statement() { return statements.statement("demo-customer", start, end); }

    @Test void statementCarriesOpeningAndTracesExactChargesPaymentsAndReversalsWithoutWriting() {
        String old = invoice("300.30", start.minusDays(1), "old");
        pay(old, "100.10", start.minusDays(1), "opening-payment");
        String current = invoice("200.20", start, "current");
        String oldPayment = pay(old, "50.05", start.plusDays(1), "old-payment");
        pay(current, "25.25", end, "new-payment");
        String reversed = invoice("40", start.plusDays(9), "reversed");
        ledger.voidInvoice(reversed, start.plusDays(14), "void", "test");
        var lines = db.queryForList("SELECT * FROM journal_lines ORDER BY id");
        var activity = db.queryForList("SELECT * FROM audit_events ORDER BY id");
        var commands = db.queryForList("SELECT * FROM commands ORDER BY command_key");
        var s = statement();
        assertThat(s.customer().id()).isEqualTo("demo-customer");
        assertThat(s.currency()).isEqualTo("USD");
        assertThat(s.openingBalance()).isEqualByComparingTo("200.20");
        assertThat(s.charges()).isEqualByComparingTo("240.20");
        assertThat(s.payments()).isEqualByComparingTo("75.30");
        assertThat(s.reversals()).isEqualByComparingTo("40.00");
        assertThat(s.closingBalance()).isEqualByComparingTo("325.10");
        assertThat(s.movements()).hasSize(5);
        assertThat(s.movements().get(1).paymentId()).isEqualTo(oldPayment);
        assertThat(s.movements().get(1).invoiceId()).isEqualTo(old);
        assertThat(s.movements().get(1).sourceId()).isEqualTo(oldPayment);
        assertThat(s.movements().get(1).reference()).isEqualTo("INV-000001");
        assertThat(s.movements().get(1).balance()).isEqualByComparingTo("350.35");
        assertThat(s.movements().get(4).balance()).isEqualByComparingTo(s.closingBalance());
        assertThat(s.closingBalance()).isEqualByComparingTo(reports.reports(start, end).receivables().total());
        assertThat(db.queryForList("SELECT * FROM journal_lines ORDER BY id")).isEqualTo(lines);
        assertThat(db.queryForList("SELECT * FROM audit_events ORDER BY id")).isEqualTo(activity);
        assertThat(db.queryForList("SELECT * FROM commands ORDER BY command_key")).isEqualTo(commands);
    }

    @Test void laterSettlementAndVoidsDoNotEraseAnEarlierStatement() {
        String paid = invoice("100", start, "paid-later");
        String voided = invoice("80", start, "void-later");
        var before = statement();
        pay(paid, "100", end.plusDays(1), "future-payment");
        ledger.voidInvoice(voided, end.plusDays(1), "future-void", "test");
        assertThat(statement()).isEqualTo(before);
        var later = statements.statement("demo-customer", end.plusDays(1), end.plusDays(1));
        assertThat(later.openingBalance()).isEqualByComparingTo("180");
        assertThat(later.payments()).isEqualByComparingTo("100");
        assertThat(later.reversals()).isEqualByComparingTo("80");
        assertThat(later.closingBalance()).isEqualByComparingTo("0");
    }

    @Test void sameDayInvoicesPrecedeReductionsAndEachSplitPaymentAppearsOnce() {
        String paid = invoice("0.30", start, "paid");
        pay(paid, "0.10", start, "part-one"); pay(paid, "0.20", start, "part-two");
        String voided = invoice("10", start, "voided");
        ledger.voidInvoice(voided, start, "void", "test");
        var s = statements.statement("demo-customer", start, start);
        assertThat(s.movements()).extracting(CustomerStatementService.Movement::kind)
                .containsExactly("INVOICE", "INVOICE", "PAYMENT", "PAYMENT", "INVOICE_REVERSAL");
        assertThat(s.movements()).extracting(CustomerStatementService.Movement::lineId).doesNotHaveDuplicates();
        assertThat(s.charges()).isEqualByComparingTo("10.30");
        assertThat(s.payments()).isEqualByComparingTo("0.30");
        assertThat(s.reversals()).isEqualByComparingTo("10");
        assertThat(s.closingBalance()).isEqualByComparingTo("0.00");
        assertThat(statement()).isEqualTo(statement());
    }

    @Test void emptyStatementsAcceptDateLimitsAndExcludeDraftsAndFutureInvoices() {
        var empty = statements.statement("demo-customer", LocalDate.of(1, 1, 1), LocalDate.of(9999, 12, 31));
        assertThat(empty.movements()).isEmpty();
        assertThat(empty.openingBalance()).isEqualByComparingTo("0.00");
        assertThat(empty.closingBalance()).isEqualByComparingTo("0.00");
        ledger.createDraft(new LedgerService.Invoice("demo-customer", "Draft", start, end, "500"), "draft", "test");
        invoice("100", end.plusDays(1), "future");
        assertThat(statement().movements()).isEmpty();
        assertThat(statement().closingBalance()).isEqualByComparingTo("0.00");
    }

    @Test void statementExcludesOtherCustomersAndBusinessesAndHidesUnknownCustomers() {
        invoice("10", start, "target");
        String other = ledger.addCustomer(new LedgerService.Customer("Other", "other@example.test"), "other-customer", "test");
        ledger.postInvoice(new LedgerService.Invoice(other, "Other work", start, end, "700"), "other-invoice", "test");
        db.update("INSERT INTO businesses (id,name,currency) VALUES (2,'Other business','USD')");
        db.update("INSERT INTO customers VALUES ('private-customer',2,'Private','private@example.test')");
        db.update("INSERT INTO invoices VALUES ('private-invoice',2,'private-customer','Private work',?,?,900,0,'POSTED')", start, end);
        db.update("INSERT INTO invoice_numbers VALUES ('private-invoice',9999)");
        db.update("INSERT INTO journal_entries VALUES ('private-entry',2,?,'Private work','private-invoice')", start);
        db.update("INSERT INTO journal_lines VALUES ('private-line','private-entry','1100',900,0)");
        db.update("INSERT INTO journal_lines VALUES ('private-offset','private-entry','4000',0,900)");
        assertThat(statement().movements()).hasSize(1);
        assertThat(statement().closingBalance()).isEqualByComparingTo("10");
        for (String id : java.util.List.of("missing", "private-customer"))
            assertThatThrownBy(() -> statements.statement(id, start, end)).isInstanceOf(IllegalArgumentException.class).hasMessage("Customer not found.");
    }

    @Test void endpointRequiresLoginAllowsReadingRolesAndRejectsInvalidDates() throws Exception {
        String path = "/api/reports/customers/demo-customer/statement?startsOn=2026-10-01&endsOn=2026-10-31";
        http.perform(get(path)).andExpect(status().isUnauthorized());
        for (String role : java.util.List.of("OWNER", "BOOKKEEPER", "REVIEWER"))
            http.perform(get(path).with(user("reader").roles(role))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.customer.name").value("Maple Coffee Co."))
                .andExpect(jsonPath("$.closingBalance").value("0.00"));
        http.perform(get(path).with(httpBasic("test", "test-only"))).andExpect(status().isOk());
        for (String invalid : java.util.List.of(path.replace("2026-10-01", "2026-11-01"), path.replace("2026-10-01", "bad"),
                path.replace("&endsOn=2026-10-31", ""), path.replace("demo-customer", "missing")))
            http.perform(get(invalid).with(user("reader").roles("REVIEWER"))).andExpect(status().isBadRequest());
        assertThatThrownBy(() -> statements.statement("demo-customer", null, end)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> statements.statement("demo-customer", start, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> statements.statement("demo-customer", LocalDate.of(0, 1, 1), end)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> statements.statement("demo-customer", start, LocalDate.of(10000, 1, 1))).isInstanceOf(IllegalArgumentException.class);
    }
}
